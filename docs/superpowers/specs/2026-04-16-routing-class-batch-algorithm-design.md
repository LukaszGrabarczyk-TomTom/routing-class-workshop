# Routing Class Batch Algorithm — Design Spec

**Date:** 2026-04-16
**Status:** Draft
**Scope:** Batch job prototype — compute RC 1–5 for Luxembourg PBF, compare against existing values

---

## 1. Goal

Build a standalone batch algorithm that assigns Routing Class (RC) values 1–5 to every road in a country-level PBF file. The algorithm must capture routing importance (which roads matter most for long-distance travel) without relying on an external routing engine. Output is a new PBF with road geometries and computed RC values, plus a comparison report against the existing RC values in the input.

This is the first of two planned processes:
- **Batch job (this spec):** Full graph available, no prior RC values, computes from scratch. Can be heavy.
- **Incremental service (future):** Road-by-road, local context, existing RC assumed correct. Must be fast.

The PBF is a prototype vehicle. Long-term, both processes consume data via REST APIs. The algorithm must be decoupled from the data source.

---

## 2. Input & Output

**Input:** Orbis PBF file (e.g., `orbis_nexventura_26160_000_global_lux.osm.pbf` — Luxembourg, 324K road ways, 6.8M nodes).

**Output:**
1. **New PBF file** (`<input_name>_computed_rc.osm.pbf`) containing road geometries + computed RC tag only. No other attributes. For side-by-side visualization in QGIS or similar.
2. **Comparison report** (console + file):
   - Structural metrics: connected components per RC level
   - Value agreement: overall match %, per-RC-level match %, 5x5 confusion matrix
   - RC1-specific precision and recall
   - Enforcement impact: promotions/demotions during Phase 3
3. **Per-road diff CSV:** road ID, highway type, geometry length, our RC, existing RC, match flag
4. **Aggregated diff summary CSV:** grouped by (our RC, existing RC) — segment count and total length (km)

---

## 3. Architecture

Three-phase pipeline operating on a shared in-memory graph model:

```
Input PBF
    |
    +-----------------------------+
    v                             v
Phase 1: PARSE & BUILD       Extract existing
         ROAD GRAPH           RC values
    |                             |
    v                             |
Phase 2: SEED & REFINE            |
    |                             |
    v                             |
Phase 3: ENFORCE &                |
         VALIDATE                 |
    |                             |
    +-----------------------------+
    |                             |
    v                             v
Write output PBF            Compare & Report
(geom + computed RC)        (metrics + CSVs)
```

Each phase reads/writes RC values on the shared graph model. The graph model has no dependency on PBF — a future REST API adapter produces the same data structure.

---

## 4. Graph Model (Core Data Structure)

The road network is a **directed graph**. Each edge is a road segment, each node is a connection point.

### RoadEdge
- `id` — original feature ID from PBF (`gers_identifier`)
- `geometry` — for writing output PBF
- `attributes` — highway type, oneway, controlled_access, dual_carriageway, maxspeed, lanes, navigability, nat_ref, int_ref, ferry flag, etc.
- `computedRc` — our output (initially unset)
- `existingRc` — from PBF routing_class tag (nullable, for comparison only)

### RoadNode
- `id` — OSM node ID
- `connectedEdges` — adjacency list (outgoing directed edges)
- `coordinates` — lat/lon

### Directionality
- Two-way roads produce two directed edges (one per direction)
- Oneway roads produce a single directed edge (`oneway=yes` forward, `oneway=-1` reverse)
- Roundabouts (`junction=roundabout`) are oneway
- This is required because connectivity is evaluated as strongly connected components (reachability must respect edge direction per the spec)

### Ferry Links
- Modeled as edges like any other road, with a ferry attribute flag
- Participate in connectivity analysis
- Luxembourg has only 1 ferry feature

### Turn Restrictions
- 6,549 restriction relations (from/to/via) in Luxembourg PBF
- Applied during SCC computation in Phase 3 (accessibility-aware connectivity)
- Only permanent restrictions in scope (seasonal/time-based excluded per spec)

---

## 5. Phase 1: Parse & Build Graph

**Responsibility:** Read PBF, construct the in-memory directed road graph, extract existing RC values.

### Steps
1. Read all road ways (features with `highway=*` tag) and the single ferry feature
2. Read all nodes referenced by road ways (for coordinates and shared-node connectivity)
3. Build directed edges per directionality rules above
4. Establish connectivity via shared node IDs between ways (1.66M intersection nodes in Luxembourg)
5. Read restriction relations for turn restriction modeling
6. Extract existing `routing_class` tag values and store separately on each edge

### Filtering
- Only features with `highway=*` or `ferry=*` are included
- Features with `navigability=closed` or `navigability=prohibited` are excluded from the routing graph and from comparison (they cannot be assigned RC by our algorithm, so comparing would skew metrics)
- Pedestrian-only features (`highway=footway`, `highway=steps`, `highway=path`, `highway=cycleway`, `highway=pedestrian`) are excluded from graph construction and from comparison — they're irrelevant to vehicle routing and are universally RC5

### Source Abstraction
- `GraphBuilder` interface with method: `RoadGraph build(InputStream source)`
- `PbfGraphBuilder` implements this for PBF files
- Future: `RestApiGraphBuilder` for the production system

### PBF Library
- `osm4j-pbf` for reading, `osm4j-pbf` or `osmosis` for writing
- Final choice during implementation based on read+write support

---

## 6. Phase 2: Seed & Refine

The core algorithm. Two steps: attribute-based seeding, then centrality-based refinement.

### Step 1: Attribute Seed

Assign initial RC candidate based on road attributes. The mapping is derived from the actual tag distributions observed in the Luxembourg PBF:

| Condition (evaluated top-to-bottom, first match wins) | Seed RC |
|---|---|
| `highway=motorway` AND `controlled_access=yes` | 1 |
| `highway=motorway` (without controlled access) | 2 |
| `highway=trunk` AND (`controlled_access=yes` OR `dual_carriageway=yes`) | 2 |
| `highway=trunk` | 3 |
| `highway=primary` AND `int_ref` present | 2 |
| `highway=primary` AND `dual_carriageway=yes` | 3 |
| `highway=primary` | 3 |
| `highway=secondary` AND `dual_carriageway=yes` | 3 |
| `highway=secondary` | 4 |
| `highway=tertiary` | 4 |
| `highway=motorway_link` | 4 |
| `highway=trunk_link` | 4 |
| `highway=primary_link` | 4 |
| `highway=secondary_link` | 5 |
| `highway=residential` | 5 |
| `highway=unclassified` | 5 |
| `highway=living_street` | 5 |
| `highway=service` | 5 |
| `highway=track` | 5 |
| `highway=road` | 5 |
| `highway=construction` | 5 |
| `ferry=*` | max RC of connected roads at endpoints |
| Everything else | 5 |

This table is a starting point. It will be tuned after initial comparison results.

**Design note:** The seed table intentionally over-assigns RC5 for ambiguous cases. It's better to promote deserving roads via centrality than to demote over-seeded ones.

### Step 2: Centrality Refinement

Use betweenness centrality to adjust seed values based on actual routing importance within the graph.

**Algorithm: Sampled Betweenness Centrality**
1. Select a random sample of N source nodes (configurable; start with ~2,000 for Luxembourg's graph size)
2. From each source, run Dijkstra's shortest path to all reachable nodes
3. During shortest-path backtracking, accumulate centrality score on each edge traversed
4. Normalize scores to [0, 1] range

**Edge Weighting:**
- Use `speed:free_flow:forward` / `speed:free_flow:backward` where available (88K roads have this)
- Fall back to `maxspeed` (146K roads) where free-flow speed is missing
- Fall back to road-type-based speed estimate as last resort
- Weight = edge length / speed (travel time). Lower travel time = preferred path = higher centrality for roads used.

**Adjustment Logic:**
1. Group edges by seed RC
2. Within each group, compute centrality percentile for each edge
3. **Promote** (RC - 1): edges above the 85th percentile of their seed group. These are roads whose routing importance exceeds what their attributes suggest.
4. **Demote** (RC + 1): edges below the 15th percentile of their seed group. These are roads whose attributes overstate their routing importance.
5. Clamp to [1, 5] range
6. Promotions/demotions are limited to 1 level per edge. A secondary road can go from RC4 to RC3 but not to RC2 in this step.

**Thresholds (85th/15th percentile) are configurable** and will be tuned based on comparison results.

---

## 7. Phase 3: Enforce & Validate

After Phase 2, RC values reflect attributes + routing importance but may violate connectivity and closure constraints. Phase 3 repairs this.

### Connectivity Enforcement (Top-Down)

For each RC level starting from the top:

**RC1:**
1. Extract the RC1 subgraph (all edges with computedRc = 1)
2. Compute strongly connected components (SCCs) using Tarjan's algorithm on the directed graph
3. Keep the largest SCC as RC1
4. Edges in smaller SCCs: demote to RC2

**RC1+RC2:**
1. Extract the RC <= 2 subgraph
2. Compute SCCs
3. Keep the largest SCC intact
4. Edges in smaller SCCs that are RC2: demote to RC3 (RC1 edges untouched — already validated)

**Repeat for RC <= 3, RC <= 4.** RC5 is the catch-all.

### Closure Enforcement

After connectivity enforcement, check closure: no RC subgraph should require detours through lower-class roads.

For each RC level n (starting from 1):
1. In the RC <= n subgraph, find edges that are only reachable from the main SCC via edges with RC > n
2. Promote those bridge edges to RC n (they're structurally necessary)

This is a small corrective pass — most closure violations are already prevented by connectivity enforcement.

### Accessibility Awareness

Both SCC computation and closure checks use the **directed** graph with:
- Oneway restrictions applied (edge direction)
- Turn restrictions from PBF relations applied (prohibited from/to/via sequences removed from adjacency)

This ensures the connectivity guarantee holds under real-world permanent driving conditions.

### Validation Metrics (produced, not enforced)

After enforcement, compute and report:
- Number of SCCs per RC level (target: 1 each for Luxembourg)
- Number of dead ends per RC level
- Closure violations remaining (should be 0)
- Number of promotions/demotions this phase made (high count = Phase 2 needs tuning)

---

## 8. Comparison

### Value Agreement
- Overall match rate: % of roads where computed RC = existing RC
- Per-RC-level match rate
- 5x5 confusion matrix (computed RC rows x existing RC columns)
- RC1-specific: precision (what % of our RC1 is also their RC1) and recall (what % of their RC1 did we also call RC1)

### Structural Quality (our result only)
- Connected components per RC subgraph (RC1-only, RC1+2, RC1+2+3, RC1+2+3+4, all)
- Dead ends per RC level

### Enforcement Impact
- Total promotions and demotions during Phase 3
- Breakdown by source RC level

### Per-Road Diff (CSV)
Columns: road_id, highway_type, geometry_length_m, computed_rc, existing_rc, match

### Aggregated Summary (separate CSV)
Grouped by (computed_rc, existing_rc):
- segment_count
- total_length_km

---

## 9. Project Structure

### Package Layout

```
com.tomtom.routing/
  model/
    RoadGraph              — graph data structure
    RoadEdge               — edge with attributes, RC values
    RoadNode               — node with adjacency
    RoutingClass            — enum: RC1-RC5
  io/
    GraphBuilder            — interface: build RoadGraph from source
    PbfGraphBuilder         — PBF implementation of GraphBuilder
    PbfWriter               — write output PBF (geometry + computed RC)
  algorithm/
    AttributeSeeder         — Phase 2 step 1: attributes to seed RC
    CentralityComputer      — Phase 2 step 2: sampled betweenness centrality
    RcRefiner               — Phase 2: combine seed + centrality adjustments
    ConnectivityEnforcer    — Phase 3: SCC-based top-down enforcement
    ClosureEnforcer         — Phase 3: closure repair via bridge promotion
  comparison/
    RcComparator            — compare computed vs existing RC
    ReportWriter            — metrics report, per-road diff CSV, aggregated summary CSV
  BatchJob                  — main entry point, orchestrates phases 1-3 + comparison
```

### Dependencies

| Dependency | Purpose |
|---|---|
| `osm4j-pbf` (or `osmosis-pbf`) | PBF read/write |
| JUnit 4.13.2 (existing) | Tests |

Graph algorithms (Tarjan's SCC, Dijkstra, betweenness centrality) implemented in-project. No external graph library.

### Test Strategy
- **Unit tests** for graph algorithms using small hand-crafted graphs (5–20 nodes)
- **Integration test** using the Luxembourg PBF — full pipeline, assert structural invariants (RC1 forms single SCC)
- No mocking — algorithms operate on real RoadGraph instances

---

## 10. Configurable Parameters

| Parameter | Default | Purpose |
|---|---|---|
| Centrality sample size | 2,000 | Number of source nodes for sampled betweenness |
| Promote threshold | 85th percentile | Centrality above this triggers promotion |
| Demote threshold | 15th percentile | Centrality below this triggers demotion |
| Max promotion/demotion steps | 1 | How many RC levels an edge can shift in refinement |
| Speed fallback (by road type) | Configurable map | Default speeds when no speed tags available |

All parameters are tunable. Initial values are starting points to be adjusted based on comparison results.

---

## 11. What This Spec Does NOT Cover

- **Incremental service:** Future work. The architecture (source-agnostic graph model, separated algorithm packages) is designed to support it, but the incremental algorithm is a separate spec.
- **REST API integration:** PBF is the prototype data source. REST API adapter is a future GraphBuilder implementation.
- **Multi-country / continental scope:** This prototype targets a single country PBF. Cross-border connectivity enforcement is out of scope.
- **Complementary Layer / Eurotunnel:** Not relevant for Luxembourg prototype.
- **Exception registration:** Dead-end exceptions (peninsulas, military areas, etc.) are not modeled. The enforcement pass will demote genuine dead ends.
- **Linear referencing:** Entire road gets single RC value per spec. No sub-segment RC.
