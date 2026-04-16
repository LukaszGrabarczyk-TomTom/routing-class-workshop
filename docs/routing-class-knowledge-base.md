# Routing Class — Comprehensive Knowledge Base

> **Purpose of this document:** Provide a complete, self-contained reference for understanding Routing Class (RC) as a feature, its place within Orbis Routing, the end-to-end pipeline that produces it, its quality challenges, and the team's plans for improvement. A reader of this document alone should be able to reason about RC design decisions, debug pipeline issues, and engage with stakeholders knowledgeably.

---

## Table of Contents

1. [What is Routing Class?](#1-what-is-routing-class)
2. [Routing Class in the Orbis Data Model](#2-routing-class-in-the-orbis-data-model)
3. [The RC Scale (1–5)](#3-the-rc-scale-15)
4. [Connected and Closed Graph — The Core Structural Requirement](#4-connected-and-closed-graph--the-core-structural-requirement)
5. [Geographic Scope](#5-geographic-scope)
6. [Islands, Ferries, and the Complementary Layer Problem](#6-islands-ferries-and-the-complementary-layer-problem)
7. [Exceptions to Connectivity](#7-exceptions-to-connectivity)
8. [The RC Pipeline — End to End](#8-the-rc-pipeline--end-to-end)
9. [FRC Generator](#9-frc-generator)
10. [Conflation — Batch Process](#10-conflation--batch-process)
11. [Transformation Service — Incremental Maintenance](#11-transformation-service--incremental-maintenance)
12. [Quality Challenges and Root Causes](#12-quality-challenges-and-root-causes)
13. [Connectivity Analysis — Current State](#13-connectivity-analysis--current-state)
14. [Geometry Matching PoC](#14-geometry-matching-poc)
15. [Connectivity Validator and Spot Check Tools](#15-connectivity-validator-and-spot-check-tools)
16. [Quality Metrics and Checks](#16-quality-metrics-and-checks)
17. [Vision and Roadmap](#17-vision-and-roadmap)
18. [Customers and Stakeholders](#18-customers-and-stakeholders)
19. [Data Format and Layer Schema](#19-data-format-and-layer-schema)
20. [Environments and Infrastructure](#20-environments-and-infrastructure)
21. [Repositories](#21-repositories)
22. [Glossary](#22-glossary)
23. [Open Conflicts Between Sources](#23-open-conflicts-between-sources)

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
| **Has Oneway** | Directional restrictions affect RC graph connectivity validation |
| **Has Controlled Access** | Motorways/controlled-access roads are typically RC1-2 |
| **Has Dual Carriageway** | Both legs may carry different RC (known limitation, see §12) |
| **Has Toll Info** | Toll roads are often high-RC roads |
| **Has Speed Profile** | Historical speed data — not directly linked to RC but correlated |
| **Has Border Crossing Info** | Border crossings on Major Road Lines — critical for cross-border RC validation |

### Connectors

**Connector** features (Point geometry, `connector=yes`) indicate physical connections between Transportation Lines. They are important for RC because:

1. They show where two or more Transportation Lines are physically connected.
2. They mark dead-end Transportation Lines.
3. They are used in the connectivity graph analysis.

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
- The specification **intentionally does not map RC values to specific road types**. The mapping is determined by the RC assignment process (FRC Generator), not by definition.
- RC values are **integers** in the hard range **1–5**. No other values are valid.
- A road may have **no RC** (the tag is absent) — this means RC has not been assigned, not that it is RC5.

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

No closed/connected requirement exists **between** continents.

> **Design choice:** Although Africa and Eurasia are physically connected via the Sinai Peninsula, they are treated as separate continental routing graphs.

> **NOTE — Conflict with other sources:** The Confluence Functional Requirements page (FR5) states the geographic scope "mimics NDS products," which are only 3 regions: EuroAfrica, Americas, AsiaOceania. The product specification (2026-04-09) defines 5 regions. See [§23 Open Conflicts](#23-open-conflicts-between-sources).

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

### 5.5 China

China is a special case: **no RC values are generated for China** because China is excluded from NDS compilation due to quality issues. RC values in China present in the layer are stale artifacts from the Transformation Service and should not be trusted. This has been addressed by OROUTE-270.

### 5.6 India/Pakistan Border

No route possibility exists between NDS products at the India/Pakistan border. This creates a known disconnection.

---

## 6. Islands, Ferries, and the Complementary Layer Problem

### Ferry Requirements

All islands with navigable roads (roads supporting passenger car traffic) must be connected to their continental routing graph via **ferry links** carrying an appropriate RC assignment.

- The RC of a ferry link matches the **highest RC** of the roads it connects on either shore. A ferry connecting an RC1 road on one side to an RC3 road on the other receives **RC1**.
- Missing ferry links or incorrect ferry RC assignments are treated as **closed/connected violations**.
- Ferry data in Orbis originates from **OSM**, not the basemap.

### The Complementary Layer Problem

This is one of the most subtle issues in RC. The FRC Generator runs on **NDS**, which is compiled from Orbis. NDS treats certain features from the **Complementary Layer** (layer ID 21263) as ferries — specifically features tagged `service=car_shuttle`.

The most critical example is the **Eurotunnel** (Channel Tunnel), which:
- Is represented as `service=car_shuttle` in the Complementary Layer
- Gets compiled into NDS as a ferry and receives RC1 from FRC
- **Cannot receive RC in the Orbis Routing Class layer** because RC can only be applied to BaseMap features, not Complementary Layer features

This means the Eurotunnel — an RC1 link connecting the UK to continental Europe — **has no RC in Orbis**, creating a major connectivity gap.

Other complementary layer features affected:
- `bridge:movable=transporter` — low-class links (RC5), low risk
- `highway=Path` — isolated case, low risk

### Eurotunnel-Specific Solutions

The team has implemented special handling for the Eurotunnel region:

- **ARGPI (Artificial Routing Golden Path Intelligence):** Changes the Transformation Service logic so that when road network changes occur in the Eurotunnel region, RC1 is assigned by default for adding/merging roads, maintaining RC1 connectivity.
- **Connectivity Spot Check Tool:** A sidecar tool for verification of RC1 connectivity in the Eurotunnel region, including road directionality.

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

## 8. The RC Pipeline — End to End

This is the complete flow from map data to RC values on the layer:

```
┌─────────────────┐    ┌─────────────┐    ┌──────────────┐    ┌───────────────┐    ┌────────────────────┐
│  Orbis BaseMap   │───>│     OPC     │───>│     NDS      │───>│ FRC Generator │───>│    Conflation      │
│  (all layers)    │    │  (1-2 days) │    │  (5 days)    │    │   (2 days)    │    │    (~1 hour)       │
└─────────────────┘    └─────────────┘    └──────────────┘    └───────────────┘    └────────┬───────────┘
                                                                                            │
                                                                                            v
                                                                                  ┌────────────────────┐
                                                                                  │  Routing Class     │
                                                                                  │  Layer (16971)     │
                                                                                  └────────┬───────────┘
                                                                                            │
                                                                              ┌─────────────┴──────────────┐
                                                                              │  Transformation Service    │
                                                                              │  (24/7 incremental)        │
                                                                              │  maintains RC until next   │
                                                                              │  conflation cycle          │
                                                                              └────────────────────────────┘
```

### Timeline

| Step | Duration | Notes |
|------|----------|-------|
| Orbis Product Create (OPC) | 1–2 days | Creates PBF product from all Orbis layers |
| Format Conversion | ~1 day | Produces ID mappings (ProductId ↔ OrbisId) |
| MCP Release | 30 min – 1 day | Requires manual action |
| NDS Compilation | ~5 days | Preprocess 52h, core 31h, attribution 12h |
| FRC Generator | ~2 days | Includes manual triggering delay |
| Conflation | ~1 hour | Was 1–2 days before optimization (Sept 2025) |
| **Total lead time** | **~12 days** | **Often ~2–3 weeks in practice** |

### The Lead Time Problem

The BaseMap changes at approximately **40,000 changes per hour**. With a 2–3 week pipeline, the RC values applied are approximately **13 million changes behind** the current state of the map. This is the fundamental quality challenge.

### Three NDS Products

The NDS compiler produces three regional products:
1. **EuroAfrica** (Europe + Africa)
2. **Americas** (North + South America)
3. **AsiaOceania** (Asia + Oceania)

The FRC Generator runs independently on each product.

---

## 9. FRC Generator

The FRC (Functional Road Class) Generator is the component that actually **computes** Routing Class values. It is **not owned by the Orbis Routing team** — it is owned by the Routing API / Aether team.

### How It Works

- Takes the compiled **NDS map** as input
- Removes existing FRC values and plans multiple routes to determine road importance
- Uses the **Asterix routing engine** (with ideas to switch to NEWHAVEN)
- Produces CSV files with `ProductId, Net2Class, CountryCode` tuples
- Outputs three files, one per NDS product region

### Key Contacts

- EM: Alina Ushakova
- SE: Vladimir Shapranov (backups: Manjusree, Simon Rotter)
- Slack: #directions-routing-aether-public
- Pipeline: Batch FRC Generation Pipeline (Azure DevOps)

### FRC Output Statistics

- Total RC entries: ~313 million (as of late 2025)
- The vast majority are RC5 (least important roads)

### Current Component Counts from FRC

The FRC Generator itself does not produce a perfectly connected graph:

| Level | Components from FRC |
|-------|-------------------|
| RC1 | ~67 |
| RC1+RC2 | ~346 |
| RC1+RC2+RC3 | ~1,909 |

These numbers **degrade significantly** after conflation lag applies them to the current map state (see §13).

---

## 10. Conflation — Batch Process

Conflation is the weekly batch process that takes FRC Generator output and applies it to the Orbis Routing Class layer.

### Architecture

```
FRC Calculator CSV files (ProductId, RoutingClass pairs)
        +
IDMHB (Id Mapping Historical Backup database)
        │
        v
[Databricks Spark Job — 6 sequential steps]
  1. CreateIdMapping       — ProductId → OrbisId mapping from IDMHB
  2. UnzipDeliveryFiles    — Decompress XZ-compressed FRC delivery files
  3. CreateSnapshot        — Snapshot latest Routing Class layer state
  4. MergeAndDeduplicate   — Join mappings with delivery records
  5. FilteringSameValues   — Remove unchanged routing class values
  6. SendRCToKafka         — Publish filtered records to Kafka
        │
        v
     Kafka topic
        │
        v
Conflation Application (Spring Boot)
  - Reads Kafka messages
  - Applies routing class tags to transportation lines
  - Handles feedback overrides (feedback tags win over conflation)
  - Publishes changelets via GSS API → Orbis Routing Class layer
```

### Why ID Mapping Is Needed

OPC (Orbis Product Create) produces a PBF product with a **different ID space** — ProductIds. NDS is compiled from this PBF. The FRC Generator outputs ProductIds. To apply RC back to Orbis, the conflation must **map ProductIds back to OrbisIds** using the Id Mapping Historical Backup (IDMHB).

### Missing IDs Problem

During conflation, approximately **30–40 ProductIds** cannot be found in the ID mapping. Categories:

1. **Complementary Layer features** (layer_id=21263) — features like `service=car_shuttle` (Eurotunnel) and `bridge:movable=transporter` that NDS treats as ferries but aren't in BaseMap.
2. **Artificial features** — IDs introduced by the NDS compiler for handling "onion issues" (toll booth areas). Not a problem for the RC graph.

### Conflation Statistics (typical run)

- FRC Delivery count: ~313 million
- Changes detected: ~627,000
- Changes that **cannot be applied** (ID not found): ~344,000 (~50% of detected changes, ~0.1% of total delivery)
  - Of which RC1: ~17,700
  - Of which RC2: ~9,400
  - Of which RC3: ~27,000
  - Of which RC5: ~283,000

### Feedback Override

The Routing Class layer supports **manual overrides** via a feedback mechanism (e.g., through Vertex tool):

- Tag: `feedback:property:routing_class`
- Feedback **always wins** over conflation unless its revision is older than the current delivery revision
- When feedback is overwritten, a `FeedbackOverwrittenEvent` is published

### Performance History

Conflation processing time has improved dramatically:

| Period | Duration | Notes |
|--------|----------|-------|
| Sept 2025 | 32–72 hours | Before optimization |
| Oct 2025+ | < 1 hour (DB job) + ~3 min (Data Flow) | After snapshot-based filtering |

---

## 11. Transformation Service — Incremental Maintenance

The Transformation Service is a 24/7 incremental process running as an Orbis layer. It reacts to BaseMap changes and attempts to maintain RC values between conflation cycles.

### What It Does

1. **Preserves RC values** when BaseMap produces insignificant changes (road geometry tweaks, attribute changes that don't affect routing).
2. **Estimates RC for new roads** based on the road's type and neighboring roads' RC values.

### Operation Detection

The service detects different types of BaseMap changes:

| Operation | Handler |
|-----------|---------|
| DELETE | DefaultDeleteTagToChangeletHandler |
| UPDATE | Custom UpdateHandler |
| CREATE | Custom CreateHandler |
| MERGE, SPLIT, ONE_TO_ONE_REPLACEMENT, OTHER_REPLACEMENT | Custom ReplacementOperationHandler |

The detection uses `DefaultOperationDetector` which runs:
1. DeltaReplacementService (MERGE, SPLIT, ONE_TO_ONE, OTHER_REPLACEMENT)
2. CreateDeleteDetector
3. FlipDetection
4. UpdateDetection
5. FeatureTypeChangeDetection

### Limitations

The Transformation Service has **limited effectiveness**:

- It produces **different RC values** than FRC would, so its estimates are approximations.
- It's somewhat successful at reassigning correct RC when a road is deleted and re-added.
- It **cannot reliably maintain** the connected graph property.
- The longer the period between conflation and map release, the worse the RC quality.
- Not all transportation lines receive values (~100k `RoutingClassTagAddedEvent` not applied historically).

### Region-Specific Logic (ARGPI)

For critical regions like the Eurotunnel, special logic has been implemented:

- **ARGPI (Artificial Routing Golden Path Intelligence):** When road network changes occur in defined critical regions, RC1 is assigned by default for adding/merging roads to maintain RC1 connectivity.
- Status: In progress (OROUTE-1249)

---

## 12. Quality Challenges and Root Causes

### 12.1 Lead Time (Primary Challenge)

The RC pipeline takes 2–3 weeks. During this time, ~13 million BaseMap changes occur. When conflation applies FRC values:

- New roads don't have RC values
- Roads deleted and re-added with different IDs don't get RC
- Some applied RC values are already incorrect (outdated)

### 12.2 FRC Is NDS-Based

NDS ≠ Orbis. The NDS compiler transforms Orbis data in ways that create mismatches:

- **Complementary Layer features** (Eurotunnel, car shuttles) exist in NDS but cannot receive RC in Orbis
- **No RC for China** (excluded from NDS)
- **No RC for Under Construction** roads (filtered out in NDS)
- **Missing RC1s on NDS product borders** (gaps between the 3 NDS product regions)
- ID remapping introduces lossy translation

### 12.3 RC Is Not Linearly Referenced

NDS produces sectioned roads; FRC produces RC values per section. But in Orbis, RC is **not a Linear Referenced attribute** — the entire road gets a single RC value. When conflating back, the system picks the most important RC for the entire road, which degrades quality.

### 12.4 Stale Values

Old RC values are not cleaned up:
- Roads that no longer exist in the delivery retain their old RC
- Stale RC1 count (excluding China and construction): ~445 roads
- OROUTE-270 addresses stale RC cleanup

### 12.5 Conflation Runs Alongside Transformation Service

The conflation process is **nondeterministic** because the Transformation Service continues running during conflation, potentially modifying values that conflation is also trying to update.

### 12.6 Dual Carriageway RC Asymmetry

The two legs of a dual carriageway (e.g., northbound and southbound motorway) are **not required** to share the same RC value. Many customers expect them to be identical (this is the convention in Genesis and HERE). This is a **deferred topic** — not yet addressed.

---

## 13. Connectivity Analysis — Current State

### Component Counts (RC1)

The number of "islands" (disconnected components) in the RC graph is the primary quality metric:

| State | RC1 Components | RC1+RC2 Components |
|-------|---------------|-------------------|
| **From FRC delivery** (idealized) | 67 | 346 |
| **After conflation** (applied to current map) | 1,346 | 2,885 |

This represents a **1,900% increase** in RC1 components due to conflation lag. This is the core problem.

### Key Findings from Analysis

- **Conflation revision:** 3,610 total islands found
- **Current revision:** 4,004 islands (Transformation Service introduces further regression)
- After filtering out China: 1,344 (conflation) → 1,673 (current)
- **North/South America:** No road or reasonable ferry connection expected (Darién Gap)
- **Under Construction roads** have RC in the layer but are not in NDS, creating phantom islands

### Ways Not Found During Conflation

- Total RCs delivered: ~298.8 million
- RC1 delivered: ~2.97 million
- RC1 not found in conflation: ~10,725
- Stale RC1 (in map but not in delivery, excl. China/construction): ~445

---

## 14. Geometry Matching PoC

When ProductIds cannot be found during conflation (because the road was modified in BaseMap during the lag period), the team investigated using **geometry** to match FRC delivery to the current map state.

### Approach

- Buffer (5m) around roads
- Intersection threshold (50%)
- Optional angle matching

### Results

| Strategy | Matches Found | RC1 Components After | Decrease |
|----------|--------------|---------------------|----------|
| Missing Source IDs + Intersection only | 10,627 | 243 | **82%** |
| Missing Source IDs + Intersection + angle | 7,332 | 614 | 54% |
| Clustering by Target + Intersection only | 11,794 | 419 | 69% |
| Clustering by Target + Intersection + angle | 7,575 | 629 | 53% |

### Conclusions

- Simple geometry matching achieves **up to 82% reduction** in disconnected components.
- Requires significant computing power.
- Wrong matches can worsen the situation.
- Significant unmatched cases need more complex approaches (routing-based matching, potentially AI).

---

## 15. Connectivity Validator and Spot Check Tools

### Connectivity Validator

A CLI tool in the `orbis-directions-routing-class` repository that validates RC graph connectivity.

**Architecture decisions (ADRs):**
- **ADR-001:** Dedicated QA layer for connectivity validator (layer separation)
- **ADR-002:** Product layer validation via Discos
- **ADR-003:** Eurotunnel connectivity solution — validation and prevention strategy
- **ADR-004:** Turn restrictions — incremental approach to validation scope

### Connectivity Spot Check Tool

- Verifies bi-directional route availability in specific regions
- Includes road directionality checking
- Currently used for Eurotunnel region monitoring
- Status: Done (26Q1)

### Verification Options Evaluated

| Option | Status | Notes |
|--------|--------|-------|
| Aggregates (SQL/Databricks on ADE data) | CONSIDERING | Part of go/no-go decision |
| Value Stream validation (own process on product) | CONSIDERING | Can use existing PoCs |
| Cohesion Checks (platform validation) | REJECTED | Not suitable for connected graph |
| Metrics (spot-check quality) | REJECTED | Not suitable for connected graph |

---

## 16. Quality Metrics and Checks

### Structural Graph Checks

**Connectivity (per RC level, per continent):**
- Run connected components on each RC subgraph (RC1-only, RC1+2, RC1+2+3, etc.)
- Target: exactly 1 component per continental region
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

### Target Metrics

| Metric | Target |
|--------|--------|
| RC1 component count per continent | 1 |
| RC1 isolated segment count | 0 (excluding documented exceptions) |
| Cross-border gap count (RC1/RC2) | 0 |
| Ferry coverage rate | 100% of islands with navigable roads |
| Restriction-induced connectivity breaks | 0 |

### Layer Statistics

| Statistic | Purpose |
|-----------|---------|
| Count of entries grouped by RC | Baseline distribution |
| Count of conflated vs unconflated entries | Conflation effectiveness |
| % of RC values from conflation (vs artificial) | Source quality |
| % of correct Transformation Service guesses | Incremental quality |
| Count of entries grouped by highway type and RC | Distribution analysis |

---

## 16.5. Sygic Sample — Connected Graph Proof of Concept

Sygic explicitly requested that RC roads form connected graphs. A sample was negotiated and built for **Slovakia only**, demonstrating:

- **RC=1** as a connected graph
- **RC=(1,2)** as a connected graph

### Approach Used

The sample was built using Databricks notebooks with the following algorithm:

1. Take a PBF country cut from Map Content Portal (Orbis Sectioned/External Slovakia Bundle00)
2. Convert PBF into Parquet
3. Extract all highways with a `routing_class` except `highway=construction`
4. Compute connected components ("islands") for RC=1
5. **Downgrade** all but the largest component to RC2 (making RC1 a single connected graph)
6. Compute connected components for RC≤2
7. **Downgrade** all but the largest component to RC3 (making RC1+2 a single connected graph)
8. Create a patch PBF

This is a **top-down downgrading approach**: start with the most important class, find the main component, demote all disconnected islands to the next class, then repeat at the next level. It demonstrates that connectivity can be enforced algorithmically, but it changes RC values from what FRC computed (trading precision for connectivity).

The sample was validated with a separate verification notebook confirming the resulting PBF had the expected connected graph properties.

---

## 17. Vision and Roadmap

### Short Term (26Q1) — Done or In Progress

| Initiative | Description | Status |
|-----------|-------------|--------|
| ARGPI (region-specific defaults) | RC1 assigned by default in critical regions | In progress |
| Connectivity Spot Check Tool | Bi-directional route verification for Eurotunnel region | Done |

### Mid Term

| Initiative | Description | Size | Impact |
|-----------|-------------|------|--------|
| Standardize Connectivity Spot Check | Make it automated in production | SMALL | LARGE |
| Auto-fixing of connectivity | Automatically fix connectivity problems | MEDIUM | LARGE |
| Non-blocking Routing Quality Gate | Quality checks on bound product | MEDIUM | LARGE |
| Routing Class Metrics | Count strongly connected components per RC level | LARGE | MEDIUM |
| Conflation geometry matching | Advanced geometry matching (see §14) | LARGE | LARGE |
| Timing of conflation | Run conflation closer to OPC kick-off | SMALL | LARGE |
| Batch default value calculation | Improve defaults to prevent disconnected components | LARGE | MEDIUM |
| Conflation connects components | Conflation inspects network and promotes RC values | LARGE | LARGE |

### Long Term

**RATS2 Initiative:**
- Migrate FRC from NDS to RATS2 (Routing Tiles compiled directly from Orbis)
- Expected to reduce lead time from 2–3 weeks to **under 1 week**
- RATS2 compiler started Q4'25, expected to finalize ~Q2'26
- Eliminates the NDS dependency and ID remapping problem
- Jira Epic: OM2NDS-4011

**NDS Classic using RATS2/FRC directly:**
- NDS should consume FRC values directly from RATS2
- Removes NDS as a downstream of the Routing Class layer

**RC on uncompiled product:**
- Deliver Routing Class as a sidefile to uncompiled customers within 1–2 days of ON release

### Improved Conflation (Planned)

| Item | Description |
|------|-------------|
| RC Statistics | Invest in gathering statistics to reason about quality |
| One-time layer cleanup | Remove suspect values from old Transformation Service |
| RC Source Tracking | Distinguish artificial vs FRC-sourced values |
| Batch default computation | Conflation as a "layer reset" mechanism |
| Geometry matching | Match roads by geometry when IDs fail |
| Performance improvement | Make conflation purely batch (no TS interference) |
| Atomic conflation | Rework as an atomic operation |

### Improved Transformation Service (Planned)

Reimplement as a **pure decremental service** — store FRC output in a queryable state as reference for TS to use when maintaining values.

---

## 18. Customers and Stakeholders

### External Customers

Customers who have expressed or implied dependency on closed and connected routing graphs:

- **Sygic** — Explicitly requested RC connected graphs; a sample was created for Slovakia (SVK) demonstrating RC1 and RC1+2 connected graphs
- **PTV** — Migration customer
- **Michelin** — Migration customer
- **Toyota** — Migration customer
- **CARIAD** (VW Group) — Migration customer
- **Esri** — Migration customer
- **Microsoft** — Identified as intended customer in functional requirements

### Internal Consumers

- **NDS Classic** — Needs RC to calculate display class and place features in correct zoom levels. Some NDS maps are used in on-board navigation, some sent to Online Routing.
- **Online Routing** — Currently does NOT use RC from the Orbis layer; instead gets values generated directly by FRC Generator. This is considered temporary and unacceptable.

### Stakeholders

| Role | Person | Responsibility |
|------|--------|---------------|
| Product Manager | Erwin Perremans | Gathering user needs, defining quality requirements |
| Engineering Manager | Jaroslaw Kacprzak | Owner of Orbis Routing |
| Previous EM | Olivier Verstraete | Previous owner, co-owner of tools |
| Tech Lead | Norbert Nogacki | Technical leadership |
| Previous Tech Lead | Christian Redl | Previous technical lead |
| Specs | Alan Hurford | Specification management |

### SLA Status

**No formal SLA currently exists for RC in Orbis.** Quality metrics and acceptance criteria are internal guidelines only — they must not be referenced in customer-facing documents until formally established through the commercial process.

---

## 19. Data Format and Layer Schema

### Layer Tags

| Tag Key | Description | Example Value |
|---------|-------------|---------------|
| `routing_class` | The RC value (integer 1–5) | `3` |
| `oprod:source:property:routing_class` | Source attribution | `conflation_20241201` |
| `feedback:property:routing_class` | Manual override value | `1` |
| `meta:source` | Legacy debugging tag | — |
| `routing_class:step` | RC in Linear Referenced format (legacy, unused) | — |

### Tag Counts (Revision 13642130, Layer 16971)

| Tag Key | Count |
|---------|-------|
| `routing_class` | 340,149,152 |
| `oprod:source:property:routing_class` | 299,379 |
| `meta:source` | 29,308 |
| `routing_class:step` | 19,651 |
| `feedback:property:routing_class` | 8,256 |

### Spark/Parquet Schema

When working with Spark, the data uses this schema:

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
- **`layerId` in RoutingClassDto:** Always the basemap layer ID (e.g., `6`)
- **`delivery.revision`:** Current revision ID of the RC layer, supplied by operator at conflation launch
- **`deliveryId`:** Derived from Kafka topic name by stripping `_full_world.*$` suffix
- **Linear reference type:** Always `LinearReferenceType.NONE` for `routing_class`
- **Feedback precedence:** `feedback:property:routing_class` overrides conflation unless revision is older

---

## 20. Environments and Infrastructure

### Incremental Service (orbis-directions-routing-class)

| Resource | Dev | Prod |
|----------|-----|------|
| Orbis Layer | 29125 | 16971 |
| Databricks | `dbws-routing-class-dev` | `databricks-nav-conf-prod` |
| Spring Dataflow | — | `https://dataflow.inc-conf.prod.maps-amf.tomtom.com` |

### Conflation (orbis-directions-routing-class-conflation)

| Resource | Dev | Prod |
|----------|-----|------|
| Azure Subscription | orbis-routing-dev | maps-amf-production |
| Databricks Workspace | `adb-1791661381914295.15.azuredatabricks.net` | `adb-132835279355663.3.azuredatabricks.net` |
| K8s Cluster | aks-routing-class-dev | aks-amf-incremental-conflation-prod |
| Spring Dataflow | — | `https://dataflow.inc-conf.prod.maps-amf.tomtom.com` |
| Storage Account | `saroutingclassweudev.blob.core.windows.net/routingclass` | `navconfprod.blob.core.windows.net/routing-class` |
| Layer ID | 29125 | 16971 |

### Authentication

Discos authentication is used for accessing layers and services. The flow is documented in `docs/discos-auth-flow.md` in the incremental repo.

---

## 21. Repositories

| Repository | Purpose | Language/Stack |
|-----------|---------|---------------|
| **orbis-directions-routing-class** | Incremental updates (Transformation Service, feedback, connectivity validator) | Java 17, Spring Boot 3, Terraform, ArgoCD |
| **orbis-directions-routing-class-conflation** | Weekly batch conflation process | Java 17, Scala 2.12, Spark 3.5, Terraform |
| orbis-routing-class-graph-analysis | Validation for connected graph (not actively used) | — |
| orbis-routing-infrastructure | Shared infrastructure for Orbis Routing | Terraform |

### Incremental Repo Modules

| Module | Language | Purpose |
|--------|----------|---------|
| `connectivity-validator` | — | CLI tool for RC graph connectivity validation |
| `feedback-service` | — | Service for handling manual RC overrides |
| `manual-endpoints` | — | REST endpoints for manual RC updates |
| `transformation-service` | — | Incremental RC maintenance |

### Conflation Repo Modules

| Module | Language | Purpose |
|--------|----------|---------|
| `conflation-application` | Java 17 / Spring Boot 3 | Kafka consumer; applies changelets to Orbis layer |
| `conflation-spark` | Scala 2.12 / Spark 3.5 | Batch job replacing Databricks notebooks (not yet in prod) |
| `conflation-common` | Java 17 | Shared DTOs (`RoutingClassDto`) |
| `idmhb-io` | Scala 2.12 / Spark 3.5 | Custom Spark DataSource for IDMHB reads |
| `infra/` | Terraform | Databricks notebooks and jobs (current production path) |
| `analysis/` | Python / Jupyter | Ad-hoc data analysis notebooks |

---

## 22. Glossary

| Term | Definition |
|------|-----------|
| **BaseMap** | The foundational Orbis layer containing the road network data. Changes at ~40,000 changes/hour. |
| **Changelet** | A unit of change applied to an Orbis layer — a set of tag modifications on features. |
| **Complementary Layer** | An Orbis layer (ID 21263) containing features like car shuttles and movable bridges that NDS treats as ferries. |
| **Conflation** | The weekly batch process that takes FRC output and applies it to the RC layer. |
| **Connected Component** | In graph theory, a group of road segments where you can travel from any segment to any other within the group. An "island." |
| **Connector** | A point feature indicating physical connection between Transportation Lines, or a dead-end. |
| **Dead End** | An RC_n road from which a vehicle cannot continue on RC_n or higher without reversal or descent. |
| **Discos** | Authentication system used for accessing Orbis layers and services. |
| **FRC (Functional Road Class)** | The classification system and the generator that computes it. Produces RC values from NDS maps. |
| **GSS API** | The API used to persist changelets to Orbis layers. |
| **IDMHB** | Id Mapping Historical Backup — database mapping ProductIds to OrbisIds. |
| **Linear Referencing (LR)** | A method of specifying positions along a road by distance from the start. RC is NOT linearly referenced in Orbis. |
| **MCP** | Map Content Portal — used for releasing map products. |
| **NDS** | Navigation Data Standard — compiled map format used by navigation systems. FRC runs on NDS. |
| **Net2Class (N2C)** | The Genesis-era equivalent of Routing Class, which enforced closed/connected graphs. |
| **ODP** | Orbis Data Platform — used to fetch existing transportation lines. |
| **ON (Orbis NexVentura)** | The Orbis product release, identified by week number (e.g., ON_25340.000). |
| **OPC (Orbis Product Create)** | Process that creates a bound PBF product from all Orbis layers. |
| **ProductId** | The ID space used in OPC PBF products and NDS. Different from OrbisId. |
| **RATS2** | Next-generation routing tile format compiled directly from Orbis, expected to replace NDS dependency. |
| **Strongly Connected Component (SCC)** | A subgraph where every node is reachable from every other node following edge directions. |
| **Transformation Service** | The 24/7 incremental process that maintains RC values between conflation cycles. |
| **Vertex** | A tool used for manual feedback/override of RC values. |

---

## 23. Open Conflicts Between Sources

The following inconsistencies exist between the product specification (2026-04-09), the Confluence documentation, and the functional requirements. These should be resolved with the Product Manager.

### Conflict 1: Number of Continental Regions

| Source | Regions | Count |
|--------|---------|-------|
| Product Spec (§4.1) | Eurasia, Africa, N. America, S. America, Oceania | **5** |
| Functional Requirements (FR5) | Mimics NDS products: EuroAfrica, Americas, AsiaOceania | **3** |

**Impact:** Determines whether Africa must be its own connected graph or can share with Europe. Determines the worldwide component count target.

### Conflict 2: Maximum Connected Components Worldwide

| Source | Target |
|--------|--------|
| Product Spec (§8.4) | 1 per continent = **5 worldwide** |
| Functional Requirements (FR14) | Max **3** worldwide |

### Conflict 3: Which RC Levels Require Connectivity

| Source | Requirement |
|--------|------------|
| Product Spec (§3.2) | **All levels** (RC1 through RC5) hierarchically |
| Functional Requirements (FR2, FR4) | RC3 agreed first (RC1, 1+2, 1+2+3), RC4+ agreed separately |

The product spec appears to be the newer and more authoritative source, but the functional requirements may reflect what is practically achievable or contractually agreed.

---

*Last updated: 2026-04-16*
*Sources: routing-class-spec.md, Confluence Orbis Routing space, specs.tomtomgroup.com Feature Spec 12.11-1.0, orbis-directions-routing-class repo docs, orbis-directions-routing-class-conflation repo docs*
