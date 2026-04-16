# Routing Class — Knowledge Base

> **Purpose of this document:** Provide a complete reference for understanding Routing Class (RC) — its requirements, its place within Orbis Routing, and the lessons learned from the current implementation. This document is written to enable designing and implementing RC from scratch while avoiding the mistakes of the past.

---

## Table of Contents

1. [What is Routing Class?](#1-what-is-routing-class)
2. [Routing Class in the Orbis Data Model](#2-routing-class-in-the-orbis-data-model)
3. [The RC Scale (1–5)](#3-the-rc-scale-15)
4. [Connected and Closed Graph — The Core Structural Requirement](#4-connected-and-closed-graph--the-core-structural-requirement)
5. [Geographic Scope](#5-geographic-scope)
6. [Islands, Ferries, and the Complementary Layer Problem](#6-islands-ferries-and-the-complementary-layer-problem)
7. [Exceptions to Connectivity](#7-exceptions-to-connectivity)
8. [How RC Is Currently Computed](#8-how-rc-is-currently-computed)
9. [Lessons from Conflation](#9-lessons-from-conflation)
10. [Lessons from Incremental Maintenance](#10-lessons-from-incremental-maintenance)
11. [Quality Challenges and Root Causes](#11-quality-challenges-and-root-causes)
12. [Connectivity Analysis — Scale of the Problem](#12-connectivity-analysis--scale-of-the-problem)
13. [Proven Techniques](#13-proven-techniques)
14. [Quality Metrics and Checks](#14-quality-metrics-and-checks)
15. [Customers and Stakeholders](#15-customers-and-stakeholders)
16. [Data Format and Layer Schema](#16-data-format-and-layer-schema)
17. [Glossary](#17-glossary)
18. [Resolved Conflicts Between Sources](#18-resolved-conflicts-between-sources)

---

## 1. What is Routing Class?

Routing Class (RC) expresses the **relative importance of a road within a larger routing graph** — national, international, or continental. It is an abstraction away from legal road classifications (motorway, secondary road, etc.) and instead reflects **routing efficiency and hierarchy**.

RC enables navigation applications to build efficient routes by:

- Quickly elevating the user onto higher-RC roads at the start of a route.
- Keeping the user on the highest applicable RC for as long as possible.
- Descending to lower-RC roads only as the destination is approached.

This matters technically because NDS-based systems and many uncompiled customer implementations exploit this property by **progressively loading only the RC subgraphs** needed for a given route length, reducing the number of arcs to evaluate. This makes the structural integrity of RC subgraphs a **hard functional requirement**, not a quality nicety.

### Historical Context

In Genesis (TomTom's previous map platform), this capability was delivered through **Net2Class (N2C)**, which enforced closed and connected routing graphs through formal rules. HERE offers equivalent guarantees on their routing hierarchy. Customers migrating to Orbis from either Genesis or HERE arrive with an established expectation of closed and connected routing graphs. The absence of this guarantee in Orbis has been identified as a **critical product gap**.

RC and Net2Class are **not equivalent features**, but from a customer perspective they serve the same need.

---

## 2. Routing Class in the Orbis Data Model

### Feature Model Hierarchy

The Orbis Feature Model organizes transportation data in a hierarchy:

```
Transportation Line (Line geometry)
├── Road Line
│   ├── Major Road Line (14 features: Motorway, Trunk Road, Primary Road, etc.)
│   └── Minor Road Line (Service Road, Track Road, etc.)
├── Ferry Line
│   └── Ferry Route
└── Railway Line
    ├── Core Railway Line
    └── Other Railway Line
```

**Major Road Line features** (14 total): Living Street, Motorway, Motorway Link, Primary Link, Primary Road, Residential Road, Road Under Construction (deprecated), Secondary Link, Secondary Road, Tertiary Link, Tertiary Road, Trunk Link, Trunk Road, Unclassified Road.

### Where RC Lives

**`Has Routing Class`** is a Feature Group (property group) that applies to:

- **Road Line** (all sub-types: Major and Minor)
- **Ferry Line**

It does **not** apply to Railway Line.

### The Tag

```
routing_class = {1, 2, 3, 4, 5}   Cardinality: [0..1]
```

The `routing_class` property indicates the importance of a Road Line or Ferry Line during routing. It speeds up routing algorithms by allowing them to consider only the most important roads first.

### Related Properties on Road Line

RC does not exist in isolation. Other properties on Road Line that interact with or affect routing:

| Property Group | Key Relevance to RC |
|---|---|
| **Has Oneway** | Directional restrictions affect RC graph connectivity validation. Values: `yes` (digitization direction), `-1` (opposite), `no` (bidirectional). Absence = bidirectional. Supports vehicle-type namespaces (motor_vehicle, hgv, bus, etc.) and conditional restrictions. |
| **Has Controlled Access** | Motorways/controlled-access roads are typically RC1-2. Values: `yes` (main carriageway), `connection` (slip road between controlled-access roads), `entrance_exit` (slip road for entering/exiting). |
| **Has Dual Carriageway** | `dual_carriageway=yes` marks one leg of a divided road. Both legs may carry different RC (known limitation, see §11). Applies only to Major Road Line. Link roads and roundabouts are excluded. |
| **Has Toll Info** | Toll roads are often high-RC roads. Values: `yes`/`no`, with vehicle-type and direction namespaces. Toll methods: `electronic_toll`, `electronic_toll_subscription`, `vignette_toll`. |
| **Has Speed Profile** | Historical speed data (free_flow, week, weekday, weekend, profile_ids) — not directly linked to RC but correlated with road importance. Major Road Lines MUST have speed profile data. |
| **Has Border Crossing Info** | `border_crossing=2` (country) or `=4` (subdivision). `border_crossing_info` provides neighboring admin area codes. Critical for cross-border RC validation. Only on Major Road Line. |
| **Has Surface Info** | `surface=paved` (default) or `surface=unpaved`. Unpaved roads are unlikely to be high-RC. |
| **Has Traffic Regulation** | `emission_regulation=yes/no` for low emission zones. Not directly RC-related but affects routing decisions. |
| **Has Hazmat Information** | Hazardous materials transport restrictions. Affects routing but not RC assignment. |
| **Has Lane Properties** | Lane count, guidance, connectivity, HOV lanes, toll lanes, divider types. Lane Connectivity is a separate Complex Feature. |
| **Has Speed Restriction** | Maximum speed, advisory speed, variable speed. Major Road Lines guaranteed to have maximum speed data. |
| **Has Truck Route Information** | Truck route designations (`generic`, `b_double`, `national_network`, `state_network`). Applies to Road Line and Ferry Line. |

### Connectors

**Connector** features (Point geometry, `connector=yes`) indicate physical connections between Transportation Lines. They are important for RC because:

1. They show where two or more Transportation Lines are physically connected at a common node, independent of road restrictions and turn restrictions.
2. They mark the end node of a dead-end Transportation Line.
3. They identify positions of `highway=crossing` nodes.
4. They are used in the connectivity graph analysis.

### Turn Restrictions

**Turn Restriction** features (`type=restriction`) represent maneuvers that are physically impossible, not advised, or not allowed. They use `from`/`via`/`to` relation members referencing Road Lines and nodes. Values: `no_left_turn`, `no_right_turn`, `no_straight_on`, `no_u_turn`. Support vehicle-type namespaces and conditional restrictions.

Turn restrictions are critical for RC because they affect **accessibility-aware connectivity validation** — a subgraph may appear topologically connected but be inaccessible due to turn restriction conflicts.

### Road Intersections

**Road Intersection** (`type=road_intersection`) represents junctions/interchange complexes with `exit` members (Connectors) and optional `centre` member (Road Intersection Center). Important for understanding the physical topology of the routing graph.

---

## 3. The RC Scale (1–5)

| Class | Importance | Typical Roads (indicative, not prescriptive) |
|-------|-----------|----------------------------------------------|
| **RC1** | Highest — continental/international backbone | Motorways, major trunk roads connecting countries |
| **RC2** | High — national importance | Major national routes, important trunk roads |
| **RC3** | Medium — regional importance | Secondary roads, regional connectors |
| **RC4** | Lower — local importance | Tertiary roads, local connectors |
| **RC5** | Lowest — neighborhood level | Residential roads, minor local roads |

**Key points:**

- The number of classes (5) and direction (RC1 = most important) are **fixed per the Orbis specification** and are not subject to change.
- The specification **intentionally does not map RC values to specific road types**. The mapping is determined by the RC assignment process, not by definition.
- RC values are **integers** in the hard range **1–5**. No other values are valid.
- A road may have **no RC** (the tag is absent) — this means RC has not been assigned, not that it is RC5.
- **There is no requirement for smooth transitions** between RC values on the road network. An RC1 road can connect directly to an RC5 road — it is not required to pass through RC2, RC3, RC4 in sequence.

---

## 4. Connected and Closed Graph — The Core Structural Requirement

This is the most important section of this document. The connected and closed graph requirement is the central engineering challenge of Routing Class.

### 4.1 Definitions

**Connected:** Every road segment of a given RC class can reach, and be reached from, every other segment of the same class via roads of the same or higher class — in both directions. No physically isolated RC subgraph islands are permitted.

> **Formal definition:** The RC_n subgraph (containing all roads of class n and above) must form a **single strongly connected component** when edge direction is respected.

**Closed:** No RC subgraph has mandatory detours via lower-class roads. A route using only RC_n and above must not require a lower-class road to remain valid.

**Dead end:** Any RC_n road from which a vehicle cannot continue on RC_n or higher roads to reach another part of the network — forcing either a reversal or descent to a lower RC to exit. Dead ends within an RC subgraph are **not acceptable**.

### 4.2 Hierarchical Integrity

The closed and connected requirements apply **hierarchically**:

```
RC1 alone              → must be closed and connected
RC1 + RC2              → must be closed and connected
RC1 + RC2 + RC3        → must be closed and connected
RC1 + RC2 + RC3 + RC4  → must be closed and connected
RC1 + RC2 + RC3 + RC4 + RC5 (full graph) → must be closed and connected
```

Each RC level adds branches to the graph above it but **must not be required** to close or connect the higher levels.

The **RC1-only graph** being closed and connected is the **hardest requirement**. NDS-based systems and offline routing customers depend on the RC1 graph as a self-sufficient backbone.

### 4.3 Assignment Principle

RC assignment is a **graph-aware process**. It is not sufficient to assign RC values road-by-road and validate the result afterwards. The assignment algorithm must evaluate and preserve closure and connectivity as roads are classified. Treating quality checks as the sole enforcement mechanism is not acceptable — **structural integrity must be built in, not inspected in**.

### 4.4 Accessibility

Closed and connected is evaluated under **real-world, permanent access conditions**. The RC subgraph must remain closed and connected when permanent oneways, turn restrictions, and access restrictions are applied.

**Only permanent restrictions are in scope.** Seasonal, time-based, and temporary restrictions are explicitly excluded.

### 4.5 On-Ramps

On-ramps (slip roads connecting lower-class roads to higher-class roads) are considered part of the RC subgraph of the road they connect to:

- An on-ramp connecting to an RC1 road is evaluated as part of the RC1 subgraph.
- A missing or incorrectly attributed on-ramp that forces a vehicle onto a lower RC road is a closed/connected violation.

---

## 5. Geographic Scope

### 5.1 Continental Boundaries

RC graphs are evaluated **per continent**, defined by practical routability (connected by driveable road), not political or geographic boundaries.

| Region | Scope |
|--------|-------|
| **Eurasia** | Europe and Asia as one landmass |
| **Africa** | African continent |
| **North America** | North American landmass |
| **South America** | South American landmass |
| **Oceania** | Australia and surrounding region |

No closed/connected requirement exists **between** continents. The aspiration is 1 connected component globally, but real-world disconnections make this impractical — the target is as few components as possible worldwide (see [§18](#18-resolved-conflicts-between-sources)).

> **Design choice:** Although Africa and Eurasia are physically connected via the Sinai Peninsula, they are treated as separate continental routing graphs.

### 5.2 The Darién Gap

North America and South America are **not** connected by a driveable road (the Darién Gap). They are treated as two separate continental routing graphs.

### 5.3 Cross-Border Requirements

| RC Level | Cross-Border Requirement |
|----------|--------------------------|
| RC1 | Closed and connected across national borders within the continent |
| RC2 | Closed and connected across national borders within the continent |
| RC3–RC5 | Not a contractual requirement (handled case-by-case) |

RC1 and RC2 must not produce routing discontinuities at national borders.

### 5.4 Microstates and Enclaves

Small countries and territories geographically embedded within larger countries (Vatican, Monaco, Andorra, Lesotho, San Marino, Liechtenstein, etc.) are **included in the routing graph of all bordering neighbors**. A microstate road is valid from any bordering nation's perspective.

### 5.5 Known Regional Issues

- **China:** No RC values are generated because China is excluded from NDS compilation due to quality issues. Any RC values present in the China region are stale artifacts.
- **India/Pakistan border:** No route possibility exists between NDS products at this border, creating a known disconnection.

---

## 6. Islands, Ferries, and the Complementary Layer Problem

### Ferry Requirements

All islands with navigable roads (roads supporting passenger car traffic) must be connected to their continental routing graph via **ferry links** carrying an appropriate RC assignment.

- The RC of a ferry link matches the **highest RC** of the roads it connects on either shore. A ferry connecting an RC1 road on one side to an RC3 road on the other receives **RC1**.
- Missing ferry links or incorrect ferry RC assignments are treated as **closed/connected violations**.
- Ferry data in Orbis originates from **OSM**, not the basemap.

### The Complementary Layer Problem

This is one of the most subtle issues in RC and a **critical design lesson**. The current FRC Generator runs on **NDS**, which is compiled from Orbis. NDS treats certain features from the **Complementary Layer** (layer ID 21263) as ferries — specifically features tagged `service=car_shuttle`.

The most critical example is the **Eurotunnel** (Channel Tunnel), which:
- Is represented as `service=car_shuttle` in the Complementary Layer
- Gets compiled into NDS as a ferry and receives RC1 from FRC
- **Cannot receive RC in the Orbis Routing Class layer** because RC can only be applied to BaseMap features, not Complementary Layer features

This means the Eurotunnel — an RC1 link connecting the UK to continental Europe — **has no RC in Orbis**, creating a major connectivity gap.

Other complementary layer features affected:
- `bridge:movable=transporter` — low-class links (RC5), low risk
- `highway=Path` — isolated case, low risk

**Design lesson:** Any RC implementation must account for transportation links that exist outside the BaseMap layer. If the RC computation source (NDS, RATS2, or any future format) includes features from layers that RC cannot be applied to, the system needs a strategy for handling these gaps — especially when those features carry high RC values like the Eurotunnel.

### Eurotunnel-Specific Solutions (Current Implementation)

The current system handles this with workarounds:
- **ARGPI (Artificial Routing Golden Path Intelligence):** Special Transformation Service logic that assigns RC1 by default when road network changes occur in the Eurotunnel region.
- **Connectivity Spot Check Tool:** Sidecar tool verifying RC1 connectivity in the Eurotunnel region.

These are band-aids. A proper implementation should handle Complementary Layer features natively.

---

## 7. Exceptions to Connectivity

Geographic dead-ends and real-world anomalies exist where the road network physically cannot satisfy the closed/connected requirement. These are acceptable if individually justified and registered.

### Acceptable Dead End Categories

**For all RC levels:**
- **Peninsula or geographical cul-de-sac:** Road serves a peninsula, fjord, or similar feature with only one entry point.
- **Mountain or summit terminus:** Road terminates at a dead-end geographic feature.
- **Dead-end administrative boundary:** Road ends at a restricted zone boundary (military area, national park with no through route).

**For RC1 and RC2 only:**
- **Urban termination:** Motorway/high-importance road ends as it enters an urban area where continuation is absorbed into lower-class urban roads.
- **Closed or missing border crossing:** Road terminates at a national border where no crossing exists or is permanently closed.

### Exception Registration

- All exceptions must be **manually identified and validated**.
- Registered as false positives in the quality rules database, scoped to the specific location.
- Each entry includes a justification referencing a category above.
- **Reviewed annually** to verify real-world conditions haven't changed.

---

## 8. How RC Is Currently Computed

Understanding the current pipeline is essential for knowing what works, what breaks, and why.

### The Pipeline

```
Orbis BaseMap → OPC → NDS Compilation → FRC Generator → Conflation → RC Layer
                                                                        ↑
                                                          Transformation Service
                                                          (24/7 incremental maintenance)
```

| Step | Duration | What It Does |
|------|----------|-------------|
| OPC (Orbis Product Create) | 1–2 days | Creates PBF product from all Orbis layers, using a different ID space (ProductIds) |
| Format Conversion | ~1 day | Produces ID mappings (ProductId ↔ OrbisId) |
| NDS Compilation | ~5 days | Compiles PBF into 3 NDS products: EuroAfrica, Americas, AsiaOceania |
| FRC Generator | ~2 days | Runs routing algorithms on NDS to compute RC values per road |
| Conflation | ~1 hour | Maps FRC output (ProductIds) back to OrbisIds and applies to RC layer |
| **Total lead time** | **~12 days** | **Often ~2–3 weeks in practice** |

### The FRC Generator

The FRC (Functional Road Class) Generator actually **computes** RC values. It is owned by a separate team (Routing API / Aether), not the Orbis Routing team.

- Takes compiled **NDS map** as input
- Removes existing FRC values and plans multiple routes to determine road importance
- Produces CSV files with `ProductId, Net2Class, CountryCode` tuples
- Outputs ~313 million entries across three files (one per NDS product region)

The FRC Generator itself does not produce a perfectly connected graph:

| Level | Components from FRC |
|-------|-------------------|
| RC1 | ~67 |
| RC1+RC2 | ~346 |
| RC1+RC2+RC3 | ~1,909 |

### The Lead Time Problem

The BaseMap changes at approximately **40,000 changes per hour**. With a 2–3 week pipeline, the RC values applied are approximately **13 million changes behind** the current state of the map. This is the fundamental quality challenge and the root cause of most problems described in this document.

---

## 9. Lessons from Conflation

The conflation batch process reveals several important lessons for any future RC implementation.

### The ID Mapping Problem

OPC produces a PBF product with a **different ID space** (ProductIds). The FRC Generator outputs ProductIds. Conflation must map these back to OrbisIds using the Id Mapping Historical Backup (IDMHB). This translation is **lossy** — approximately 30–40 ProductIds per run cannot be found, including critical features like the Eurotunnel.

**Lesson:** Any design that requires translating between ID spaces introduces data loss. A system that computes RC directly on Orbis IDs (like the planned RATS2 approach) eliminates this entire class of errors.

### The Stale Data Problem

~50% of detected RC changes cannot be applied because the roads have changed during the 2–3 week lag. Typical conflation statistics:

- FRC Delivery: ~313 million entries
- Changes detected: ~627,000
- **Cannot be applied** (ID not found): ~344,000
  - Of which RC1: ~17,700 (most damaging to connectivity)

Additionally, roads that no longer exist in the FRC delivery retain their old RC values, creating phantom islands. Stale RC1 count (excluding China and construction): ~445 roads.

**Lesson:** Long lag times between RC computation and application cause massive quality degradation. Minimizing lead time should be a primary design goal.

### The Feedback Override Mechanism

The RC layer supports manual overrides via `feedback:property:routing_class` tags (e.g., through the Vertex tool). Feedback always wins over conflation unless its revision is older. This is a useful escape hatch for critical corrections.

**Lesson:** A manual override mechanism is essential for operational flexibility, but it must be integrated with automated processes so overrides aren't silently lost during batch updates.

### Nondeterministic Conflation

The Transformation Service continues running during conflation, creating race conditions where both processes modify the same values.

**Lesson:** Batch updates and incremental maintenance must be coordinated. Either pause incremental updates during batch processing, or make the batch process atomic.

---

## 10. Lessons from Incremental Maintenance

Between conflation cycles, the Transformation Service attempts to maintain RC values as the BaseMap changes. Its limitations are instructive.

### What It Tries to Do

1. **Preserve RC values** when BaseMap produces insignificant changes (geometry tweaks, non-routing attribute changes).
2. **Estimate RC for new roads** based on road type and neighboring roads' RC values.

### Why It Fails

- It produces **different RC values** than FRC would — its estimates are approximations based on local context, not global routing analysis.
- It **cannot reliably maintain** the connected graph property because it lacks visibility into the full graph topology.
- Not all transportation lines receive values (~100k events not applied historically).
- The longer the period between conflation and map release, the worse the quality.
- It introduced regression: analysis showed islands increasing from 3,610 (at conflation) to 4,004 (after incremental maintenance).

**Lesson:** Incremental maintenance without access to the full graph context will always degrade connectivity. A future system needs either (a) much shorter batch cycles so incremental maintenance matters less, or (b) an incremental process that can reason about graph connectivity, not just local road context.

### The ARGPI Workaround

For critical regions (Eurotunnel), the team implemented special logic: when road changes occur in defined critical regions, RC1 is assigned by default. This is a targeted band-aid, not a scalable solution.

**Lesson:** Region-specific hardcoded rules don't scale. A proper solution should handle connectivity preservation generically.

---

## 11. Quality Challenges and Root Causes

### 11.1 Lead Time (Primary Challenge)

The RC pipeline takes 2–3 weeks. During this time, ~13 million BaseMap changes occur. When conflation applies FRC values:

- New roads don't have RC values
- Roads deleted and re-added with different IDs don't get RC
- Some applied RC values are already incorrect (outdated)

### 11.2 FRC Is NDS-Based

NDS ≠ Orbis. The NDS compiler transforms Orbis data in ways that create mismatches:

- **Complementary Layer features** (Eurotunnel, car shuttles) exist in NDS but cannot receive RC in Orbis
- **No RC for China** (excluded from NDS)
- **No RC for Under Construction** roads (filtered out in NDS)
- **Missing RC1s on NDS product borders** (gaps between the 3 NDS product regions)
- ID remapping introduces lossy translation

### 11.3 RC Is Not Linearly Referenced

NDS produces sectioned roads; FRC produces RC values per section. But in Orbis, RC is **not a Linear Referenced attribute** — the entire road gets a single RC value. When conflating back, the system picks the most important RC for the entire road, which degrades quality.

### 11.4 Stale Values

Old RC values are not cleaned up. Roads that no longer exist in the delivery retain their old RC, creating phantom disconnected islands.

### 11.5 Conflation Runs Alongside Transformation Service

The conflation process is **nondeterministic** because the Transformation Service continues running during conflation, potentially modifying values that conflation is also trying to update.

### 11.6 Dual Carriageway RC Asymmetry

The two legs of a dual carriageway (e.g., northbound and southbound motorway) are **not required** to share the same RC value. Many customers expect them to be identical (this is the convention in Genesis and HERE). This is a **known gap** that hasn't been addressed.

---

## 12. Connectivity Analysis — Scale of the Problem

### Component Counts

The number of "islands" (disconnected components) in the RC graph:

| State | RC1 Components | RC1+RC2 Components | RC1+RC2+RC3 Components |
|-------|---------------|-------------------|----------------------|
| **From FRC delivery** (idealized) | 67 | 346 | 1,909 |
| **After conflation** (applied to current map) | 1,346 | 2,885 | 43,343,675 |

This represents a **1,900% increase** in RC1 components due to conflation lag. The Transformation Service introduces further regression (3,610 → 4,004 islands).

### Key Takeaways

- The FRC Generator itself produces a reasonably connected graph (~67 RC1 components). The problem is overwhelmingly in the **application** of these values, not their computation.
- After filtering out China: 1,344 (conflation) → 1,673 (current) RC1 islands.
- **Under Construction roads** have RC in the layer but are not in NDS, creating phantom islands.
- **North/South America:** No road or ferry connection expected (Darién Gap).
- Stale RC1 (in map but not in delivery, excl. China/construction): ~445 roads.

---

## 13. Proven Techniques

### 13.1 Geometry Matching

When ProductIds cannot be found during conflation, geometry can be used to match FRC delivery to the current map state.

**Approach:** Buffer (5m) around roads, intersection threshold (50%), optional angle matching.

| Strategy | Matches Found | RC1 Components After | Decrease |
|----------|--------------|---------------------|----------|
| Missing Source IDs + Intersection only | 10,627 | 243 | **82%** |
| Missing Source IDs + Intersection + angle | 7,332 | 614 | 54% |
| Clustering by Target + Intersection only | 11,794 | 419 | 69% |
| Clustering by Target + Intersection + angle | 7,575 | 629 | 53% |

**Takeaway:** Simple geometry matching achieves up to 82% reduction in disconnected components but requires significant computing power and wrong matches can worsen the situation.

### 13.2 Top-Down Downgrading (Sygic Sample Approach)

A proof of concept for Slovakia demonstrated an algorithmic approach to enforcing connectivity:

1. Extract all highways with RC, excluding construction
2. Compute connected components for RC=1
3. **Downgrade** all but the largest component to RC2
4. Compute connected components for RC≤2
5. **Downgrade** all but the largest component to RC3
6. Repeat for each level

**Takeaway:** Connectivity can be enforced algorithmically by identifying the main component at each level and demoting disconnected islands. This trades precision (some roads get a different RC than FRC computed) for connectivity guarantees.

### 13.3 RATS2 — Computing RC Directly on Orbis

The planned RATS2 initiative would compile routing tiles directly from Orbis (not via NDS), eliminating:
- The NDS compilation step (~5 days saved)
- The ProductId ↔ OrbisId translation (no more ID mapping losses)
- The Complementary Layer mismatch

This would reduce lead time from 2–3 weeks to under 1 week and remove an entire class of conflation errors.

**Takeaway:** The closer RC computation is to the source data (Orbis), the fewer translation layers, the fewer errors, and the shorter the lag.

---

## 14. Quality Metrics and Checks

### Structural Graph Checks

**Connectivity (per RC level, per continent):**
- Run connected components on each RC subgraph (RC1-only, RC1+2, RC1+2+3, etc.)
- Target: as few components as possible (aspirationally 1 globally)
- Output: component count + list of isolated segments with coordinates

**Closure check:**
- For each RC level n: identify roads in RC≥n that can only reach another road in RC≥n by traversing a lower-class road
- These are "bridge" roads — their existence means the higher graph is not closed

**Accessibility-aware reachability:**
- Apply permanent oneways and turn restrictions, then re-run connectivity
- Delta = restrictions that break connectivity (defect candidates)

**Cross-border gap detection:**
- For RC1 and RC2: at every border crossing, verify subgraph remains connected

### Attribution Checks

- **Downward exclusion:** Every RC_n road must be reachable via RC_n or higher roads only
- **Microstate absorption:** No microstate has an isolated RC subgraph
- **Ferry RC consistency:** Every island has at least one ferry link with appropriate RC

### Precision and Recall

- **Recall failure:** A road is assigned a lower RC but connectivity at a higher level depends on it being elevated. Detection: find the minimum set of lower-class roads whose elevation would fix each violation.
- **Precision:** Over-assignment is not a defect in itself — a road can legitimately hold RC1 because of its importance even if its removal wouldn't disconnect the graph. A high proportion of removable RC1 roads is a signal to investigate, not an automatic failure.

---

## 15. Customers and Stakeholders

### External Customers

Customers who have expressed or implied dependency on closed and connected routing graphs:

- **Sygic** — Explicitly requested RC connected graphs; a proof of concept was created for Slovakia
- **PTV** — Migration customer
- **Michelin** — Migration customer
- **Toyota** — Migration customer
- **CARIAD** (VW Group) — Migration customer
- **Esri** — Migration customer
- **Microsoft** — Identified as intended customer

These customers arrive from Genesis (Net2Class) or HERE with an established expectation of closed and connected routing graphs. This is considered a **baseline expectation**, not an optimization.

### Internal Consumers

- **NDS Classic** — Needs RC to calculate display class and place features in correct zoom levels. Some NDS maps are used in on-board navigation.
- **Online Routing** — Currently does NOT use RC from the Orbis layer; instead gets values generated directly by FRC Generator. This is considered a temporary state.

### SLA Status

**No formal SLA currently exists for RC in Orbis.** Quality metrics and acceptance criteria are internal guidelines only.

---

## 16. Data Format and Layer Schema

### Layer Tags

| Tag Key | Description | Example Value |
|---------|-------------|---------------|
| `routing_class` | The RC value (integer 1–5) | `3` |
| `oprod:source:property:routing_class` | Source attribution | `conflation_20241201` |
| `feedback:property:routing_class` | Manual override value | `1` |
| `meta:source` | Legacy debugging tag | — |
| `routing_class:step` | RC in Linear Referenced format (legacy, unused) | — |

### Tag Counts (representative snapshot)

| Tag Key | Count |
|---------|-------|
| `routing_class` | 340,149,152 |
| `oprod:source:property:routing_class` | 299,379 |
| `meta:source` | 29,308 |
| `routing_class:step` | 19,651 |
| `feedback:property:routing_class` | 8,256 |

### Spark/Parquet Schema

When working with Spark, the Orbis layer data uses this schema:

```yaml
id:
  layerId: integer      # Always the basemap layer ID (e.g., 6)
  high: long
  low: long
revisionId: long
elementType: byte
tags:
  - tagKey:
      layerId: integer
      key: string
    value: string
    semanticIds:
      - integer
lat: double
lng: double
nodes:
  - layerId: integer
    high: long
    low: long
wkt: string
members:
  - id:
      layerId: integer
      high: long
      low: long
    role: string
    semanticIds:
      - integer
```

### Key Domain Invariants

- **RC values:** Integers in the hard range **1–5** only
- **Linear reference type:** RC is NOT linearly referenced in the current Orbis implementation — the entire road gets one RC value
- **Feedback precedence:** `feedback:property:routing_class` overrides automated RC assignment unless the feedback revision is older

---

## 17. Glossary

| Term | Definition |
|------|-----------|
| **BaseMap** | The foundational Orbis layer containing the road network data. Changes at ~40,000 changes/hour. |
| **Changelet** | A unit of change applied to an Orbis layer — a set of tag modifications on features. |
| **Complementary Layer** | An Orbis layer (ID 21263) containing features like car shuttles and movable bridges that NDS treats as ferries but RC cannot be applied to. |
| **Conflation** | The batch process that takes externally computed RC values and applies them to the RC layer. |
| **Connected Component** | In graph theory, a group of road segments where you can travel from any segment to any other within the group. An "island." |
| **Connector** | A point feature indicating physical connection between Transportation Lines, or a dead-end. |
| **Dead End** | An RC_n road from which a vehicle cannot continue on RC_n or higher without reversal or descent. |
| **FRC (Functional Road Class)** | The classification system and the generator that computes it. Produces RC values from compiled maps. |
| **IDMHB** | Id Mapping Historical Backup — database mapping ProductIds to OrbisIds. Required because NDS uses a different ID space. |
| **Linear Referencing (LR)** | A method of specifying positions along a road by distance from the start. RC is NOT linearly referenced in Orbis. |
| **NDS** | Navigation Data Standard — compiled map format. The current FRC Generator runs on NDS, creating a dependency that introduces lag and ID translation errors. |
| **Net2Class (N2C)** | The Genesis-era equivalent of Routing Class, which enforced closed/connected graphs. Customers expect the same from Orbis RC. |
| **OPC (Orbis Product Create)** | Process that creates a bound PBF product from all Orbis layers, introducing the ProductId space. |
| **ProductId** | The ID space used in OPC PBF products and NDS. Different from OrbisId — translation between them is lossy. |
| **RATS2** | Next-generation routing tile format compiled directly from Orbis, expected to eliminate the NDS dependency. |
| **Strongly Connected Component (SCC)** | A subgraph where every node is reachable from every other node following edge directions. The RC requirement. |

---

## 18. Resolved Conflicts Between Sources

During documentation synthesis, the following inconsistencies were found between sources. They have been clarified with the team.

### 1. Number of Continental Regions

| Source | Document | Regions | Count |
|--------|----------|---------|-------|
| Product Spec §4.1 | `routing-class-spec.md` (2026-04-09, Erwin Perremans) | Eurasia, Africa, N. America, S. America, Oceania | **5** |
| Confluence Functional Requirements FR5 | [Routing Class - Connected Graph - Functional Requirements](https://tomtom.atlassian.net/wiki/spaces/ORBR/pages/791355655) (2025-09-15) | Mimics NDS products: EuroAfrica, Americas, AsiaOceania | **3** |

**Resolution:** Both are correct in their own context. **Orbis defines 5 continental regions** — this is the authoritative model for RC connectivity validation. The 3 regions in the Functional Requirements reflect the NDS product split, which is how FRC delivers its output. The FRC Calculator produces 3 files (one per NDS product), but the Orbis RC team evaluates connectivity against 5 regions.

### 2. Maximum Connected Components Worldwide

| Source | Document | Target |
|--------|----------|--------|
| Product Spec §8.4 | `routing-class-spec.md` (2026-04-09, Erwin Perremans) | 1 per continent = **5 worldwide** |
| Confluence Functional Requirements FR14 | [Routing Class - Connected Graph - Functional Requirements](https://tomtom.atlassian.net/wiki/spaces/ORBR/pages/791355655) (2025-09-15) | Max **3** worldwide |

**Resolution:** The aspiration is **1 connected component globally** (the entire world as a single connected graph). This may not be achievable in practice due to real-world disconnections (e.g., Darién Gap, no India-Pakistan route), so the practical target is simply **as few components as possible worldwide** — the fewer the better. The "max 3" in the Functional Requirements was an earlier, more relaxed target based on the 3 NDS product regions.

### 3. RC Value Transition Smoothness

| Source | Document | Statement |
|--------|----------|-----------|
| Product Spec §3.2 | `routing-class-spec.md` (2026-04-09, Erwin Perremans) | All RC levels must be hierarchically closed and connected |
| Confluence Functional Requirements FR2, FR4 | [Routing Class - Connected Graph - Functional Requirements](https://tomtom.atlassian.net/wiki/spaces/ORBR/pages/791355655) (2025-09-15) | RC3 connectivity agreed first (RC1, 1+2, 1+2+3), RC4+ agreed separately |

**Resolution:** Both sources agree that each RC subgraph must be connected. The Functional Requirements simply reflect implementation priority (RC1-3 first, then RC4+). An important clarification: **there is no requirement for smooth transitions between RC values on the road network**. An RC1 road can connect directly to an RC5 road — it is not required to pass through RC2, RC3, RC4 in sequence. The hierarchical connectivity requirement (§4.2 of this document) is about each *subgraph* being connected, not about adjacent roads having adjacent RC values.

---

*Last updated: 2026-04-16*
*Sources: routing-class-spec.md, Confluence Orbis Routing space, specs.tomtomgroup.com Feature Spec 12.11-1.0, orbis-directions-routing-class repo docs, orbis-directions-routing-class-conflation repo docs*
