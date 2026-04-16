# RC Connectivity Enforcement Module — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a format-agnostic graph module that detects and repairs Routing Class connectivity violations using a bridge-first cascade strategy.

**Architecture:** Abstract graph core (nodes, edges, RC labels) with pluggable input adapters (FRC CSV + Orbis topology) and output writers (Parquet, JSON report). Two-pass connectivity analysis (undirected Union-Find, optional directed Tarjan SCC) feeds a repair engine that tries bridge promotion before island downgrading, processing RC levels top-down.

**Tech Stack:** Java 17, Maven, JUnit 4 (existing), Apache Parquet (to add), Jackson (JSON report)

**Spec:** [`docs/superpowers/specs/2026-04-16-rc-connectivity-enforcement-design.md`](../specs/2026-04-16-rc-connectivity-enforcement-design.md)

---

## Prerequisites: Update pom.xml

Before starting tasks, add required dependencies. This is done once in Task 1.

---

## File Structure

```
src/main/java/com/tomtom/routing/
├── model/
│   ├── Node.java                    # Graph node (junction point)
│   ├── Edge.java                    # Graph edge (road segment with RC + traversal mode)
│   ├── TraversalMode.java           # Enum: FORWARD, REVERSE, BOTH
│   ├── RcGraph.java                 # Graph container with subgraph extraction
│   ├── RcChange.java                # Single RC modification record
│   └── EnforcementReport.java       # Aggregated report of all changes
├── exception/
│   └── ExceptionRegistry.java       # Set of known acceptable dead-end edge IDs
├── analysis/
│   ├── ConnectivityResult.java      # Output of connectivity analysis (components, main, islands)
│   ├── UndirectedAnalyzer.java      # Pass 1: Union-Find weakly connected components
│   └── DirectedAnalyzer.java        # Pass 2: Tarjan SCC (optional)
├── repair/
│   ├── RepairStrategy.java          # Interface for repair strategies
│   ├── BridgeFirstCascadeRepair.java # Bridge search + downgrade cascade
│   └── RepairConfig.java            # Configuration (maxBridgeHops, maxPromotions, etc.)
├── adapter/
│   ├── GraphAdapter.java            # Interface: builds RcGraph from a data source
│   ├── FrcCsvAdapter.java           # Reads FRC delivery CSVs
│   ├── ExceptionFileAdapter.java    # Reads exception registry file
│   └── IdMapping.java               # ProductId → EdgeId translation
├── writer/
│   ├── ResultWriter.java            # Interface for output writers
│   ├── ParquetResultWriter.java     # Writes repaired assignments to Parquet
│   └── JsonReportWriter.java        # Writes diagnostic report to JSON
└── ConnectivityEnforcer.java        # Orchestrator: wires adapters → analysis → repair → writers

src/test/java/com/tomtom/routing/
├── model/
│   ├── RcGraphTest.java
│   └── EnforcementReportTest.java
├── analysis/
│   ├── UndirectedAnalyzerTest.java
│   └── DirectedAnalyzerTest.java
├── repair/
│   └── BridgeFirstCascadeRepairTest.java
├── adapter/
│   ├── FrcCsvAdapterTest.java
│   └── ExceptionFileAdapterTest.java
├── writer/
│   ├── ParquetResultWriterTest.java
│   └── JsonReportWriterTest.java
└── ConnectivityEnforcerTest.java

src/test/resources/
├── frc-sample.csv
├── frc-malformed.csv
├── exceptions-sample.txt
└── exceptions-malformed.txt
```

---

## Task 1: Project Setup — Add Dependencies

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add Parquet and Jackson dependencies to pom.xml**

Replace the `<dependencies>` section with:

```xml
<dependencies>
    <dependency>
        <groupId>org.apache.parquet</groupId>
        <artifactId>parquet-avro</artifactId>
        <version>1.14.4</version>
    </dependency>
    <dependency>
        <groupId>org.apache.hadoop</groupId>
        <artifactId>hadoop-common</artifactId>
        <version>3.4.1</version>
        <exclusions>
            <exclusion>
                <groupId>org.slf4j</groupId>
                <artifactId>slf4j-reload4j</artifactId>
            </exclusion>
        </exclusions>
    </dependency>
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
        <version>2.18.2</version>
    </dependency>
    <dependency>
        <groupId>junit</groupId>
        <artifactId>junit</artifactId>
        <version>4.13.2</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

- [ ] **Step 2: Verify build compiles**

Run: `mvn compile -q`
Expected: BUILD SUCCESS, no errors

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "chore: add Parquet, Hadoop, and Jackson dependencies"
```

---

## Task 2: Core Model — TraversalMode, Node, Edge

**Files:**
- Create: `src/main/java/com/tomtom/routing/model/TraversalMode.java`
- Create: `src/main/java/com/tomtom/routing/model/Node.java`
- Create: `src/main/java/com/tomtom/routing/model/Edge.java`

- [ ] **Step 1: Create TraversalMode enum**

```java
package com.tomtom.routing.model;

public enum TraversalMode {
    FORWARD,
    REVERSE,
    BOTH
}
```

- [ ] **Step 2: Create Node record**

```java
package com.tomtom.routing.model;

import java.util.Objects;

public record Node(String id) {
    public Node {
        Objects.requireNonNull(id, "Node id must not be null");
    }
}
```

- [ ] **Step 3: Create Edge record**

```java
package com.tomtom.routing.model;

import java.util.Objects;
import java.util.OptionalInt;

public final class Edge {

    private final String id;
    private final String sourceNodeId;
    private final String targetNodeId;
    private final TraversalMode traversalMode;
    private int routingClass;
    private boolean hasRoutingClass;

    public Edge(String id, String sourceNodeId, String targetNodeId, TraversalMode traversalMode) {
        this.id = Objects.requireNonNull(id);
        this.sourceNodeId = Objects.requireNonNull(sourceNodeId);
        this.targetNodeId = Objects.requireNonNull(targetNodeId);
        this.traversalMode = Objects.requireNonNull(traversalMode);
        this.hasRoutingClass = false;
    }

    public Edge(String id, String sourceNodeId, String targetNodeId, TraversalMode traversalMode, int routingClass) {
        this(id, sourceNodeId, targetNodeId, traversalMode);
        setRoutingClass(routingClass);
    }

    public String id() { return id; }
    public String sourceNodeId() { return sourceNodeId; }
    public String targetNodeId() { return targetNodeId; }
    public TraversalMode traversalMode() { return traversalMode; }

    public OptionalInt routingClass() {
        return hasRoutingClass ? OptionalInt.of(routingClass) : OptionalInt.empty();
    }

    public void setRoutingClass(int rc) {
        if (rc < 1 || rc > 5) {
            throw new IllegalArgumentException("Routing class must be 1-5, got: " + rc);
        }
        this.routingClass = rc;
        this.hasRoutingClass = true;
    }

    public void clearRoutingClass() {
        this.hasRoutingClass = false;
    }
}
```

- [ ] **Step 4: Verify build compiles**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tomtom/routing/model/TraversalMode.java \
        src/main/java/com/tomtom/routing/model/Node.java \
        src/main/java/com/tomtom/routing/model/Edge.java
git commit -m "feat: add core model — TraversalMode, Node, Edge"
```

---

## Task 3: Core Model — RcGraph with Subgraph Extraction

**Files:**
- Create: `src/test/java/com/tomtom/routing/model/RcGraphTest.java`
- Create: `src/main/java/com/tomtom/routing/model/RcGraph.java`

- [ ] **Step 1: Write failing tests for RcGraph**

```java
package com.tomtom.routing.model;

import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class RcGraphTest {

    @Test
    public void emptyGraphHasNoNodesOrEdges() {
        RcGraph graph = new RcGraph();
        assertTrue(graph.nodes().isEmpty());
        assertTrue(graph.edges().isEmpty());
    }

    @Test
    public void addNodeAndEdge() {
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("n1"));
        graph.addNode(new Node("n2"));
        graph.addEdge(new Edge("e1", "n1", "n2", TraversalMode.BOTH, 1));

        assertEquals(2, graph.nodes().size());
        assertEquals(1, graph.edges().size());
    }

    @Test
    public void subgraphFiltersEdgesByRcLevel() {
        RcGraph graph = buildSampleGraph();

        RcGraph rc1Only = graph.subgraph(1);
        Set<String> rc1Edges = rc1Only.edges().stream().map(Edge::id).collect(Collectors.toSet());
        assertEquals(Set.of("e1"), rc1Edges);

        RcGraph rc1and2 = graph.subgraph(2);
        Set<String> rc12Edges = rc1and2.edges().stream().map(Edge::id).collect(Collectors.toSet());
        assertEquals(Set.of("e1", "e2"), rc12Edges);

        RcGraph rc123 = graph.subgraph(3);
        assertEquals(3, rc123.edges().size());
    }

    @Test
    public void subgraphIncludesOnlyTouchedNodes() {
        RcGraph graph = buildSampleGraph();

        RcGraph rc1Only = graph.subgraph(1);
        Set<String> nodeIds = rc1Only.nodes().stream().map(Node::id).collect(Collectors.toSet());
        assertEquals(Set.of("n1", "n2"), nodeIds);
    }

    @Test
    public void edgesWithoutRcAreExcludedFromAllSubgraphs() {
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("n1"));
        graph.addNode(new Node("n2"));
        graph.addEdge(new Edge("e1", "n1", "n2", TraversalMode.BOTH));

        for (int level = 1; level <= 5; level++) {
            assertTrue(graph.subgraph(level).edges().isEmpty());
        }
    }

    @Test
    public void neighborsReturnsAdjacentEdges() {
        RcGraph graph = buildSampleGraph();
        List<Edge> neighbors = graph.edgesFrom("n2");
        Set<String> edgeIds = neighbors.stream().map(Edge::id).collect(Collectors.toSet());
        assertEquals(Set.of("e1", "e2", "e3"), edgeIds);
    }

    private RcGraph buildSampleGraph() {
        // n1 --RC1-- n2 --RC2-- n3 --RC3-- n4
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("n1"));
        graph.addNode(new Node("n2"));
        graph.addNode(new Node("n3"));
        graph.addNode(new Node("n4"));
        graph.addEdge(new Edge("e1", "n1", "n2", TraversalMode.BOTH, 1));
        graph.addEdge(new Edge("e2", "n2", "n3", TraversalMode.BOTH, 2));
        graph.addEdge(new Edge("e3", "n3", "n4", TraversalMode.BOTH, 3));
        return graph;
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.model.RcGraphTest -q 2>&1 | tail -5`
Expected: Compilation failure — `RcGraph` does not exist

- [ ] **Step 3: Implement RcGraph**

```java
package com.tomtom.routing.model;

import java.util.*;
import java.util.stream.Collectors;

public class RcGraph {

    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, Edge> edges = new LinkedHashMap<>();
    private final Map<String, List<Edge>> adjacency = new HashMap<>();

    public void addNode(Node node) {
        nodes.put(node.id(), node);
        adjacency.putIfAbsent(node.id(), new ArrayList<>());
    }

    public void addEdge(Edge edge) {
        edges.put(edge.id(), edge);
        adjacency.computeIfAbsent(edge.sourceNodeId(), k -> new ArrayList<>()).add(edge);
        adjacency.computeIfAbsent(edge.targetNodeId(), k -> new ArrayList<>()).add(edge);
    }

    public Collection<Node> nodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    public Collection<Edge> edges() {
        return Collections.unmodifiableCollection(edges.values());
    }

    public Edge edge(String id) {
        return edges.get(id);
    }

    public List<Edge> edgesFrom(String nodeId) {
        return Collections.unmodifiableList(adjacency.getOrDefault(nodeId, List.of()));
    }

    public RcGraph subgraph(int maxRcLevel) {
        RcGraph sub = new RcGraph();
        for (Edge edge : edges.values()) {
            if (edge.routingClass().isPresent() && edge.routingClass().getAsInt() <= maxRcLevel) {
                sub.addNode(new Node(edge.sourceNodeId()));
                sub.addNode(new Node(edge.targetNodeId()));
                sub.addEdge(edge);
            }
        }
        return sub;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.model.RcGraphTest -q`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tomtom/routing/model/RcGraph.java \
        src/test/java/com/tomtom/routing/model/RcGraphTest.java
git commit -m "feat: add RcGraph with subgraph extraction and adjacency"
```

---

## Task 4: Core Model — RcChange and EnforcementReport

**Files:**
- Create: `src/main/java/com/tomtom/routing/model/RcChange.java`
- Create: `src/main/java/com/tomtom/routing/model/EnforcementReport.java`
- Create: `src/test/java/com/tomtom/routing/model/EnforcementReportTest.java`

- [ ] **Step 1: Write failing test for EnforcementReport**

```java
package com.tomtom.routing.model;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class EnforcementReportTest {

    @Test
    public void emptyReportHasZeroCounts() {
        EnforcementReport report = new EnforcementReport();
        assertEquals(0, report.totalUpgrades());
        assertEquals(0, report.totalDowngrades());
        assertTrue(report.changes().isEmpty());
        assertTrue(report.unresolvableIslands().isEmpty());
    }

    @Test
    public void recordChangesAndCounts() {
        EnforcementReport report = new EnforcementReport();
        report.addChange(new RcChange("e1", 3, 1, RcChange.Reason.UPGRADE, "bridge from island-7 to main"));
        report.addChange(new RcChange("e2", 1, 2, RcChange.Reason.DOWNGRADE, "island-3 at RC1, no bridge found"));

        assertEquals(1, report.totalUpgrades());
        assertEquals(1, report.totalDowngrades());
        assertEquals(2, report.changes().size());
    }

    @Test
    public void recordComponentCounts() {
        EnforcementReport report = new EnforcementReport();
        report.recordComponentCount(1, 67, 1);

        assertEquals(67, report.componentCountBefore(1));
        assertEquals(1, report.componentCountAfter(1));
    }

    @Test
    public void recordUnresolvableIslands() {
        EnforcementReport report = new EnforcementReport();
        report.addUnresolvableIsland(5, List.of("e10", "e11"));

        assertEquals(1, report.unresolvableIslands().size());
        assertEquals(List.of("e10", "e11"), report.unresolvableIslands().get(0).edgeIds());
    }

    @Test
    public void recordExceptionHits() {
        EnforcementReport report = new EnforcementReport();
        report.addExceptionHit("e5", "Peninsula dead end");

        assertEquals(1, report.exceptionHits().size());
        assertEquals("e5", report.exceptionHits().get(0).edgeId());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.model.EnforcementReportTest -q 2>&1 | tail -5`
Expected: Compilation failure — `RcChange` and `EnforcementReport` do not exist

- [ ] **Step 3: Implement RcChange**

```java
package com.tomtom.routing.model;

public record RcChange(String edgeId, int oldRc, int newRc, Reason reason, String context) {

    public enum Reason {
        UPGRADE,
        DOWNGRADE
    }
}
```

- [ ] **Step 4: Implement EnforcementReport**

```java
package com.tomtom.routing.model;

import java.util.*;

public class EnforcementReport {

    public record UnresolvableIsland(int rcLevel, List<String> edgeIds) {}
    public record ExceptionHit(String edgeId, String justification) {}

    private final List<RcChange> changes = new ArrayList<>();
    private final Map<Integer, int[]> componentCounts = new LinkedHashMap<>();
    private final List<UnresolvableIsland> unresolvableIslands = new ArrayList<>();
    private final List<ExceptionHit> exceptionHits = new ArrayList<>();

    public void addChange(RcChange change) {
        changes.add(change);
    }

    public List<RcChange> changes() {
        return Collections.unmodifiableList(changes);
    }

    public int totalUpgrades() {
        return (int) changes.stream().filter(c -> c.reason() == RcChange.Reason.UPGRADE).count();
    }

    public int totalDowngrades() {
        return (int) changes.stream().filter(c -> c.reason() == RcChange.Reason.DOWNGRADE).count();
    }

    public void recordComponentCount(int rcLevel, int before, int after) {
        componentCounts.put(rcLevel, new int[]{before, after});
    }

    public int componentCountBefore(int rcLevel) {
        return componentCounts.getOrDefault(rcLevel, new int[]{0, 0})[0];
    }

    public int componentCountAfter(int rcLevel) {
        return componentCounts.getOrDefault(rcLevel, new int[]{0, 0})[1];
    }

    public Map<Integer, int[]> componentCounts() {
        return Collections.unmodifiableMap(componentCounts);
    }

    public void addUnresolvableIsland(int rcLevel, List<String> edgeIds) {
        unresolvableIslands.add(new UnresolvableIsland(rcLevel, List.copyOf(edgeIds)));
    }

    public List<UnresolvableIsland> unresolvableIslands() {
        return Collections.unmodifiableList(unresolvableIslands);
    }

    public void addExceptionHit(String edgeId, String justification) {
        exceptionHits.add(new ExceptionHit(edgeId, justification));
    }

    public List<ExceptionHit> exceptionHits() {
        return Collections.unmodifiableList(exceptionHits);
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.model.EnforcementReportTest -q`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/tomtom/routing/model/RcChange.java \
        src/main/java/com/tomtom/routing/model/EnforcementReport.java \
        src/test/java/com/tomtom/routing/model/EnforcementReportTest.java
git commit -m "feat: add RcChange and EnforcementReport for change tracking"
```

---

## Task 5: Exception Registry

**Files:**
- Create: `src/test/java/com/tomtom/routing/adapter/ExceptionFileAdapterTest.java`
- Create: `src/test/resources/exceptions-sample.txt`
- Create: `src/test/resources/exceptions-malformed.txt`
- Create: `src/main/java/com/tomtom/routing/exception/ExceptionRegistry.java`
- Create: `src/main/java/com/tomtom/routing/adapter/ExceptionFileAdapter.java`

- [ ] **Step 1: Create test fixture files**

`src/test/resources/exceptions-sample.txt`:
```
# Peninsula dead ends — reviewed 2026-01-15
e100 # Nordkapp peninsula
e101 # Gibraltar terminus

# Mountain terminus
e200 # Mont Blanc summit road
```

`src/test/resources/exceptions-malformed.txt`:
```
e100
   
e200 # valid
not an id with spaces
```

- [ ] **Step 2: Write failing tests**

```java
package com.tomtom.routing.adapter;

import com.tomtom.routing.exception.ExceptionRegistry;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class ExceptionFileAdapterTest {

    @Test
    public void parsesSampleFile() throws IOException {
        Path path = Path.of("src/test/resources/exceptions-sample.txt");
        ExceptionRegistry registry = ExceptionFileAdapter.load(path);

        assertTrue(registry.isException("e100"));
        assertTrue(registry.isException("e101"));
        assertTrue(registry.isException("e200"));
        assertFalse(registry.isException("e999"));
        assertEquals(3, registry.size());
    }

    @Test
    public void skipsBlankLinesAndCommentOnlyLines() throws IOException {
        Path path = Path.of("src/test/resources/exceptions-sample.txt");
        ExceptionRegistry registry = ExceptionFileAdapter.load(path);

        // Only 3 actual entries, despite blank lines and comment-only lines
        assertEquals(3, registry.size());
    }

    @Test
    public void emptyRegistryWhenPathIsNull() {
        ExceptionRegistry registry = ExceptionFileAdapter.empty();
        assertFalse(registry.isException("e100"));
        assertEquals(0, registry.size());
    }

    @Test
    public void justificationIsAvailable() throws IOException {
        Path path = Path.of("src/test/resources/exceptions-sample.txt");
        ExceptionRegistry registry = ExceptionFileAdapter.load(path);

        assertEquals("Nordkapp peninsula", registry.justification("e100").orElse(""));
        assertEquals("Gibraltar terminus", registry.justification("e101").orElse(""));
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.adapter.ExceptionFileAdapterTest -q 2>&1 | tail -5`
Expected: Compilation failure

- [ ] **Step 4: Implement ExceptionRegistry**

```java
package com.tomtom.routing.exception;

import java.util.*;

public class ExceptionRegistry {

    private final Map<String, String> exceptions;

    public ExceptionRegistry(Map<String, String> exceptions) {
        this.exceptions = Map.copyOf(exceptions);
    }

    public ExceptionRegistry() {
        this.exceptions = Map.of();
    }

    public boolean isException(String edgeId) {
        return exceptions.containsKey(edgeId);
    }

    public Optional<String> justification(String edgeId) {
        return Optional.ofNullable(exceptions.get(edgeId));
    }

    public int size() {
        return exceptions.size();
    }
}
```

- [ ] **Step 5: Implement ExceptionFileAdapter**

```java
package com.tomtom.routing.adapter;

import com.tomtom.routing.exception.ExceptionRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class ExceptionFileAdapter {

    public static ExceptionRegistry load(Path path) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int commentIdx = trimmed.indexOf('#');
            if (commentIdx > 0) {
                String edgeId = trimmed.substring(0, commentIdx).trim();
                String justification = trimmed.substring(commentIdx + 1).trim();
                entries.put(edgeId, justification);
            } else {
                entries.put(trimmed, "");
            }
        }
        return new ExceptionRegistry(entries);
    }

    public static ExceptionRegistry empty() {
        return new ExceptionRegistry();
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.adapter.ExceptionFileAdapterTest -q`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/tomtom/routing/exception/ExceptionRegistry.java \
        src/main/java/com/tomtom/routing/adapter/ExceptionFileAdapter.java \
        src/test/java/com/tomtom/routing/adapter/ExceptionFileAdapterTest.java \
        src/test/resources/exceptions-sample.txt \
        src/test/resources/exceptions-malformed.txt
git commit -m "feat: add ExceptionRegistry and file adapter"
```

---

## Task 6: Undirected Connectivity Analysis (Pass 1)

**Files:**
- Create: `src/main/java/com/tomtom/routing/analysis/ConnectivityResult.java`
- Create: `src/test/java/com/tomtom/routing/analysis/UndirectedAnalyzerTest.java`
- Create: `src/main/java/com/tomtom/routing/analysis/UndirectedAnalyzer.java`

- [ ] **Step 1: Create ConnectivityResult**

```java
package com.tomtom.routing.analysis;

import java.util.List;
import java.util.Set;

public record ConnectivityResult(
    int rcLevel,
    Set<String> mainComponent,
    List<Set<String>> islands
) {
    public int totalComponents() {
        return 1 + islands.size();
    }

    public boolean isConnected() {
        return islands.isEmpty();
    }
}
```

The `mainComponent` and `islands` contain **edge IDs**, not node IDs.

- [ ] **Step 2: Write failing tests for UndirectedAnalyzer**

```java
package com.tomtom.routing.analysis;

import com.tomtom.routing.model.*;
import org.junit.Test;

import static org.junit.Assert.*;

public class UndirectedAnalyzerTest {

    @Test
    public void singleConnectedComponent() {
        // A --RC1-- B --RC1-- C (all connected)
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("A"));
        graph.addNode(new Node("B"));
        graph.addNode(new Node("C"));
        graph.addEdge(new Edge("e1", "A", "B", TraversalMode.BOTH, 1));
        graph.addEdge(new Edge("e2", "B", "C", TraversalMode.BOTH, 1));

        ConnectivityResult result = UndirectedAnalyzer.analyze(graph, 1);

        assertTrue(result.isConnected());
        assertEquals(1, result.totalComponents());
        assertEquals(2, result.mainComponent().size());
    }

    @Test
    public void twoDisconnectedIslands() {
        // A --RC1-- B    C --RC1-- D (two separate components)
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("A"));
        graph.addNode(new Node("B"));
        graph.addNode(new Node("C"));
        graph.addNode(new Node("D"));
        graph.addEdge(new Edge("e1", "A", "B", TraversalMode.BOTH, 1));
        graph.addEdge(new Edge("e2", "C", "D", TraversalMode.BOTH, 1));

        ConnectivityResult result = UndirectedAnalyzer.analyze(graph, 1);

        assertFalse(result.isConnected());
        assertEquals(2, result.totalComponents());
        // Main component is the largest (or first if equal size)
        assertEquals(1, result.mainComponent().size());
        assertEquals(1, result.islands().size());
        assertEquals(1, result.islands().get(0).size());
    }

    @Test
    public void largestComponentBecomesMain() {
        // A --RC1-- B --RC1-- C    D --RC1-- E (3-edge vs 1-edge)
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("A"));
        graph.addNode(new Node("B"));
        graph.addNode(new Node("C"));
        graph.addNode(new Node("D"));
        graph.addNode(new Node("E"));
        graph.addEdge(new Edge("e1", "A", "B", TraversalMode.BOTH, 1));
        graph.addEdge(new Edge("e2", "B", "C", TraversalMode.BOTH, 1));
        graph.addEdge(new Edge("e3", "D", "E", TraversalMode.BOTH, 1));

        ConnectivityResult result = UndirectedAnalyzer.analyze(graph, 1);

        assertEquals(2, result.mainComponent().size()); // e1, e2
        assertEquals(1, result.islands().size());
        assertEquals(1, result.islands().get(0).size()); // e3
    }

    @Test
    public void subgraphFilteringRespectsRcLevel() {
        // A --RC1-- B --RC3-- C (at level 1, only e1 exists)
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("A"));
        graph.addNode(new Node("B"));
        graph.addNode(new Node("C"));
        graph.addEdge(new Edge("e1", "A", "B", TraversalMode.BOTH, 1));
        graph.addEdge(new Edge("e2", "B", "C", TraversalMode.BOTH, 3));

        ConnectivityResult rc1Result = UndirectedAnalyzer.analyze(graph, 1);
        assertEquals(1, rc1Result.totalComponents());

        ConnectivityResult rc3Result = UndirectedAnalyzer.analyze(graph, 3);
        assertEquals(1, rc3Result.totalComponents());
        assertEquals(2, rc3Result.mainComponent().size());
    }

    @Test
    public void emptyGraphProducesNoComponents() {
        RcGraph graph = new RcGraph();
        ConnectivityResult result = UndirectedAnalyzer.analyze(graph, 1);

        assertTrue(result.isConnected());
        assertTrue(result.mainComponent().isEmpty());
        assertTrue(result.islands().isEmpty());
    }

    @Test
    public void onewayEdgesTreatedAsBidirectional() {
        // A --RC1(forward)--> B --RC1(forward)--> C
        // In undirected analysis, still one component
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("A"));
        graph.addNode(new Node("B"));
        graph.addNode(new Node("C"));
        graph.addEdge(new Edge("e1", "A", "B", TraversalMode.FORWARD, 1));
        graph.addEdge(new Edge("e2", "B", "C", TraversalMode.FORWARD, 1));

        ConnectivityResult result = UndirectedAnalyzer.analyze(graph, 1);
        assertTrue(result.isConnected());
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.analysis.UndirectedAnalyzerTest -q 2>&1 | tail -5`
Expected: Compilation failure

- [ ] **Step 4: Implement UndirectedAnalyzer using Union-Find**

```java
package com.tomtom.routing.analysis;

import com.tomtom.routing.model.Edge;
import com.tomtom.routing.model.RcGraph;

import java.util.*;

public class UndirectedAnalyzer {

    public static ConnectivityResult analyze(RcGraph fullGraph, int rcLevel) {
        RcGraph sub = fullGraph.subgraph(rcLevel);
        Collection<Edge> edges = sub.edges();

        if (edges.isEmpty()) {
            return new ConnectivityResult(rcLevel, Set.of(), List.of());
        }

        // Union-Find on node IDs
        Map<String, String> parent = new HashMap<>();
        Map<String, Integer> rank = new HashMap<>();

        for (Edge edge : edges) {
            parent.putIfAbsent(edge.sourceNodeId(), edge.sourceNodeId());
            parent.putIfAbsent(edge.targetNodeId(), edge.targetNodeId());
            rank.putIfAbsent(edge.sourceNodeId(), 0);
            rank.putIfAbsent(edge.targetNodeId(), 0);
            union(parent, rank, edge.sourceNodeId(), edge.targetNodeId());
        }

        // Group edges by their component root
        Map<String, Set<String>> componentEdges = new LinkedHashMap<>();
        for (Edge edge : edges) {
            String root = find(parent, edge.sourceNodeId());
            componentEdges.computeIfAbsent(root, k -> new LinkedHashSet<>()).add(edge.id());
        }

        // Find the largest component
        Set<String> mainComponent = null;
        int maxSize = -1;
        for (Set<String> component : componentEdges.values()) {
            if (component.size() > maxSize) {
                maxSize = component.size();
                mainComponent = component;
            }
        }

        List<Set<String>> islands = new ArrayList<>();
        for (Set<String> component : componentEdges.values()) {
            if (component != mainComponent) {
                islands.add(Collections.unmodifiableSet(component));
            }
        }

        return new ConnectivityResult(rcLevel, Collections.unmodifiableSet(mainComponent), islands);
    }

    private static String find(Map<String, String> parent, String x) {
        while (!parent.get(x).equals(x)) {
            parent.put(x, parent.get(parent.get(x))); // path compression
            x = parent.get(x);
        }
        return x;
    }

    private static void union(Map<String, String> parent, Map<String, Integer> rank, String a, String b) {
        String rootA = find(parent, a);
        String rootB = find(parent, b);
        if (rootA.equals(rootB)) return;

        int rankA = rank.get(rootA);
        int rankB = rank.get(rootB);
        if (rankA < rankB) {
            parent.put(rootA, rootB);
        } else if (rankA > rankB) {
            parent.put(rootB, rootA);
        } else {
            parent.put(rootB, rootA);
            rank.put(rootA, rankA + 1);
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.analysis.UndirectedAnalyzerTest -q`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/tomtom/routing/analysis/ConnectivityResult.java \
        src/main/java/com/tomtom/routing/analysis/UndirectedAnalyzer.java \
        src/test/java/com/tomtom/routing/analysis/UndirectedAnalyzerTest.java
git commit -m "feat: add undirected connectivity analysis using Union-Find"
```

---

## Task 7: Directed Connectivity Analysis (Pass 2)

**Files:**
- Create: `src/test/java/com/tomtom/routing/analysis/DirectedAnalyzerTest.java`
- Create: `src/main/java/com/tomtom/routing/analysis/DirectedAnalyzer.java`

- [ ] **Step 1: Write failing tests for DirectedAnalyzer**

```java
package com.tomtom.routing.analysis;

import com.tomtom.routing.model.*;
import org.junit.Test;

import static org.junit.Assert.*;

public class DirectedAnalyzerTest {

    @Test
    public void bidirectionalEdgesFormSingleScc() {
        // A <--RC1--> B <--RC1--> C (strongly connected)
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("A"));
        graph.addNode(new Node("B"));
        graph.addNode(new Node("C"));
        graph.addEdge(new Edge("e1", "A", "B", TraversalMode.BOTH, 1));
        graph.addEdge(new Edge("e2", "B", "C", TraversalMode.BOTH, 1));

        ConnectivityResult result = DirectedAnalyzer.analyze(graph, 1);

        assertTrue(result.isConnected());
        assertEquals(2, result.mainComponent().size());
    }

    @Test
    public void onewayChainBreaksStrongConnectivity() {
        // A --RC1--> B --RC1--> C (not strongly connected: can't go C→A)
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("A"));
        graph.addNode(new Node("B"));
        graph.addNode(new Node("C"));
        graph.addEdge(new Edge("e1", "A", "B", TraversalMode.FORWARD, 1));
        graph.addEdge(new Edge("e2", "B", "C", TraversalMode.FORWARD, 1));

        ConnectivityResult result = DirectedAnalyzer.analyze(graph, 1);

        assertFalse(result.isConnected());
    }

    @Test
    public void onewayLoopIsStronglyConnected() {
        // A --RC1--> B --RC1--> C --RC1--> A (cycle = one SCC)
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("A"));
        graph.addNode(new Node("B"));
        graph.addNode(new Node("C"));
        graph.addEdge(new Edge("e1", "A", "B", TraversalMode.FORWARD, 1));
        graph.addEdge(new Edge("e2", "B", "C", TraversalMode.FORWARD, 1));
        graph.addEdge(new Edge("e3", "C", "A", TraversalMode.FORWARD, 1));

        ConnectivityResult result = DirectedAnalyzer.analyze(graph, 1);

        assertTrue(result.isConnected());
        assertEquals(3, result.mainComponent().size());
    }

    @Test
    public void reverseTraversalModeRespected() {
        // Edge source=A, target=B, mode=REVERSE means traversal B→A only
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("A"));
        graph.addNode(new Node("B"));
        graph.addNode(new Node("C"));
        graph.addEdge(new Edge("e1", "A", "B", TraversalMode.REVERSE, 1)); // B→A
        graph.addEdge(new Edge("e2", "B", "C", TraversalMode.FORWARD, 1)); // B→C

        ConnectivityResult result = DirectedAnalyzer.analyze(graph, 1);

        // A←B→C: three separate SCCs (no cycles)
        assertFalse(result.isConnected());
    }

    @Test
    public void emptyGraphIsConnected() {
        RcGraph graph = new RcGraph();
        ConnectivityResult result = DirectedAnalyzer.analyze(graph, 1);

        assertTrue(result.isConnected());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.analysis.DirectedAnalyzerTest -q 2>&1 | tail -5`
Expected: Compilation failure

- [ ] **Step 3: Implement DirectedAnalyzer using Kosaraju's algorithm**

```java
package com.tomtom.routing.analysis;

import com.tomtom.routing.model.Edge;
import com.tomtom.routing.model.RcGraph;
import com.tomtom.routing.model.TraversalMode;

import java.util.*;

public class DirectedAnalyzer {

    public static ConnectivityResult analyze(RcGraph fullGraph, int rcLevel) {
        RcGraph sub = fullGraph.subgraph(rcLevel);
        Collection<Edge> edges = sub.edges();

        if (edges.isEmpty()) {
            return new ConnectivityResult(rcLevel, Set.of(), List.of());
        }

        // Build directed adjacency lists (forward and reverse)
        Map<String, List<String[]>> forward = new HashMap<>();  // nodeId → [(neighborId, edgeId)]
        Map<String, List<String>> reverse = new HashMap<>();     // nodeId → [neighborId]
        Set<String> allNodes = new LinkedHashSet<>();

        for (Edge edge : edges) {
            allNodes.add(edge.sourceNodeId());
            allNodes.add(edge.targetNodeId());

            if (edge.traversalMode() == TraversalMode.FORWARD || edge.traversalMode() == TraversalMode.BOTH) {
                forward.computeIfAbsent(edge.sourceNodeId(), k -> new ArrayList<>())
                       .add(new String[]{edge.targetNodeId(), edge.id()});
                reverse.computeIfAbsent(edge.targetNodeId(), k -> new ArrayList<>())
                       .add(edge.sourceNodeId());
            }
            if (edge.traversalMode() == TraversalMode.REVERSE || edge.traversalMode() == TraversalMode.BOTH) {
                forward.computeIfAbsent(edge.targetNodeId(), k -> new ArrayList<>())
                       .add(new String[]{edge.sourceNodeId(), edge.id()});
                reverse.computeIfAbsent(edge.sourceNodeId(), k -> new ArrayList<>())
                       .add(edge.targetNodeId());
            }
        }

        // Kosaraju's step 1: compute finish order via DFS on forward graph
        Deque<String> finishOrder = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        for (String node : allNodes) {
            if (!visited.contains(node)) {
                dfsForward(node, forward, visited, finishOrder);
            }
        }

        // Kosaraju's step 2: DFS on reverse graph in reverse finish order
        visited.clear();
        List<Set<String>> nodeComponents = new ArrayList<>();
        while (!finishOrder.isEmpty()) {
            String node = finishOrder.pop();
            if (!visited.contains(node)) {
                Set<String> component = new LinkedHashSet<>();
                dfsReverse(node, reverse, visited, component);
                nodeComponents.add(component);
            }
        }

        // Map node components to edge components
        Map<String, Integer> nodeToComponent = new HashMap<>();
        for (int i = 0; i < nodeComponents.size(); i++) {
            for (String node : nodeComponents.get(i)) {
                nodeToComponent.put(node, i);
            }
        }

        List<Set<String>> edgeComponents = new ArrayList<>();
        for (int i = 0; i < nodeComponents.size(); i++) {
            edgeComponents.add(new LinkedHashSet<>());
        }
        for (Edge edge : edges) {
            int srcComp = nodeToComponent.get(edge.sourceNodeId());
            int tgtComp = nodeToComponent.get(edge.targetNodeId());
            // Assign edge to its source node's component
            // (edges spanning components are assigned to the smaller component)
            if (srcComp == tgtComp) {
                edgeComponents.get(srcComp).add(edge.id());
            } else {
                // Cross-component edge — assign to each endpoint's component
                edgeComponents.get(srcComp).add(edge.id());
                edgeComponents.get(tgtComp).add(edge.id());
            }
        }

        // Remove empty components and find the largest
        edgeComponents.removeIf(Set::isEmpty);

        if (edgeComponents.isEmpty()) {
            return new ConnectivityResult(rcLevel, Set.of(), List.of());
        }

        Set<String> mainComponent = null;
        int maxSize = -1;
        for (Set<String> component : edgeComponents) {
            if (component.size() > maxSize) {
                maxSize = component.size();
                mainComponent = component;
            }
        }

        List<Set<String>> islands = new ArrayList<>();
        for (Set<String> component : edgeComponents) {
            if (component != mainComponent) {
                islands.add(Collections.unmodifiableSet(component));
            }
        }

        return new ConnectivityResult(rcLevel, Collections.unmodifiableSet(mainComponent), islands);
    }

    private static void dfsForward(String node, Map<String, List<String[]>> forward,
                                   Set<String> visited, Deque<String> finishOrder) {
        Deque<String[]> stack = new ArrayDeque<>();
        stack.push(new String[]{node, "enter"});

        while (!stack.isEmpty()) {
            String[] frame = stack.pop();
            String current = frame[0];
            String phase = frame[1];

            if (phase.equals("exit")) {
                finishOrder.push(current);
                continue;
            }

            if (visited.contains(current)) continue;
            visited.add(current);

            stack.push(new String[]{current, "exit"});
            for (String[] neighbor : forward.getOrDefault(current, List.of())) {
                if (!visited.contains(neighbor[0])) {
                    stack.push(new String[]{neighbor[0], "enter"});
                }
            }
        }
    }

    private static void dfsReverse(String node, Map<String, List<String>> reverse,
                                   Set<String> visited, Set<String> component) {
        Deque<String> stack = new ArrayDeque<>();
        stack.push(node);

        while (!stack.isEmpty()) {
            String current = stack.pop();
            if (visited.contains(current)) continue;
            visited.add(current);
            component.add(current);

            for (String neighbor : reverse.getOrDefault(current, List.of())) {
                if (!visited.contains(neighbor)) {
                    stack.push(neighbor);
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.analysis.DirectedAnalyzerTest -q`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tomtom/routing/analysis/DirectedAnalyzer.java \
        src/test/java/com/tomtom/routing/analysis/DirectedAnalyzerTest.java
git commit -m "feat: add directed connectivity analysis using Kosaraju's algorithm"
```

---

## Task 8: Repair Config and Strategy Interface

**Files:**
- Create: `src/main/java/com/tomtom/routing/repair/RepairConfig.java`
- Create: `src/main/java/com/tomtom/routing/repair/RepairStrategy.java`

- [ ] **Step 1: Create RepairConfig**

```java
package com.tomtom.routing.repair;

import java.util.Arrays;

public class RepairConfig {

    private final int maxBridgeHops;
    private final int maxPromotions;
    private final int maxRcJump;
    private final int[] rcLevelsToProcess;
    private final boolean enableDirectedPass;

    private RepairConfig(Builder builder) {
        this.maxBridgeHops = builder.maxBridgeHops;
        this.maxPromotions = builder.maxPromotions;
        this.maxRcJump = builder.maxRcJump;
        this.rcLevelsToProcess = builder.rcLevelsToProcess.clone();
        this.enableDirectedPass = builder.enableDirectedPass;
    }

    public int maxBridgeHops() { return maxBridgeHops; }
    public int maxPromotions() { return maxPromotions; }
    public int maxRcJump() { return maxRcJump; }
    public int[] rcLevelsToProcess() { return rcLevelsToProcess.clone(); }
    public boolean enableDirectedPass() { return enableDirectedPass; }

    public static Builder builder() { return new Builder(); }

    public static RepairConfig defaults() { return new Builder().build(); }

    public static class Builder {
        private int maxBridgeHops = 10;
        private int maxPromotions = 5;
        private int maxRcJump = 2;
        private int[] rcLevelsToProcess = {1, 2, 3, 4, 5};
        private boolean enableDirectedPass = false;

        public Builder maxBridgeHops(int v) { this.maxBridgeHops = v; return this; }
        public Builder maxPromotions(int v) { this.maxPromotions = v; return this; }
        public Builder maxRcJump(int v) { this.maxRcJump = v; return this; }
        public Builder rcLevelsToProcess(int... v) { this.rcLevelsToProcess = v.clone(); return this; }
        public Builder enableDirectedPass(boolean v) { this.enableDirectedPass = v; return this; }
        public RepairConfig build() { return new RepairConfig(this); }
    }
}
```

- [ ] **Step 2: Create RepairStrategy interface**

```java
package com.tomtom.routing.repair;

import com.tomtom.routing.exception.ExceptionRegistry;
import com.tomtom.routing.model.EnforcementReport;
import com.tomtom.routing.model.RcGraph;

public interface RepairStrategy {

    EnforcementReport enforce(RcGraph graph, ExceptionRegistry exceptions, RepairConfig config);
}
```

- [ ] **Step 3: Verify build compiles**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/tomtom/routing/repair/RepairConfig.java \
        src/main/java/com/tomtom/routing/repair/RepairStrategy.java
git commit -m "feat: add RepairConfig and RepairStrategy interface"
```

---

## Task 9: Bridge-First Cascade Repair Engine

**Files:**
- Create: `src/test/java/com/tomtom/routing/repair/BridgeFirstCascadeRepairTest.java`
- Create: `src/main/java/com/tomtom/routing/repair/BridgeFirstCascadeRepair.java`

- [ ] **Step 1: Write failing tests**

```java
package com.tomtom.routing.repair;

import com.tomtom.routing.exception.ExceptionRegistry;
import com.tomtom.routing.model.*;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class BridgeFirstCascadeRepairTest {

    private final RepairStrategy repair = new BridgeFirstCascadeRepair();
    private final ExceptionRegistry noExceptions = new ExceptionRegistry();

    @Test
    public void connectedGraphNoChanges() {
        // A --RC1-- B --RC1-- C (already connected)
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("A"));
        graph.addNode(new Node("B"));
        graph.addNode(new Node("C"));
        graph.addEdge(new Edge("e1", "A", "B", TraversalMode.BOTH, 1));
        graph.addEdge(new Edge("e2", "B", "C", TraversalMode.BOTH, 1));

        RepairConfig config = RepairConfig.builder().rcLevelsToProcess(1).build();
        EnforcementReport report = repair.enforce(graph, noExceptions, config);

        assertEquals(0, report.totalUpgrades());
        assertEquals(0, report.totalDowngrades());
    }

    @Test
    public void islandDowngradedWhenNoBridgeExists() {
        // A --RC1-- B    C --RC1-- D (no connecting road at any level)
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("A"));
        graph.addNode(new Node("B"));
        graph.addNode(new Node("C"));
        graph.addNode(new Node("D"));
        graph.addEdge(new Edge("e1", "A", "B", TraversalMode.BOTH, 1));
        graph.addEdge(new Edge("e2", "C", "D", TraversalMode.BOTH, 1));

        RepairConfig config = RepairConfig.builder().rcLevelsToProcess(1).build();
        EnforcementReport report = repair.enforce(graph, noExceptions, config);

        assertEquals(0, report.totalUpgrades());
        assertEquals(1, report.totalDowngrades());

        RcChange change = report.changes().get(0);
        assertEquals("e2", change.edgeId());
        assertEquals(1, change.oldRc());
        assertEquals(2, change.newRc());
        assertEquals(RcChange.Reason.DOWNGRADE, change.reason());
    }

    @Test
    public void bridgePromotedToReconnectIsland() {
        // A --RC1-- B --RC3-- C --RC1-- D
        // B-C is RC3, can be promoted to RC1 to connect the two RC1 islands
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("A"));
        graph.addNode(new Node("B"));
        graph.addNode(new Node("C"));
        graph.addNode(new Node("D"));
        graph.addEdge(new Edge("e1", "A", "B", TraversalMode.BOTH, 1));
        graph.addEdge(new Edge("e2", "B", "C", TraversalMode.BOTH, 3));
        graph.addEdge(new Edge("e3", "C", "D", TraversalMode.BOTH, 1));

        RepairConfig config = RepairConfig.builder()
                .rcLevelsToProcess(1)
                .maxRcJump(3)
                .build();
        EnforcementReport report = repair.enforce(graph, noExceptions, config);

        assertEquals(1, report.totalUpgrades());
        assertEquals(0, report.totalDowngrades());

        RcChange change = report.changes().get(0);
        assertEquals("e2", change.edgeId());
        assertEquals(3, change.oldRc());
        assertEquals(1, change.newRc());
    }

    @Test
    public void bridgeRejectedWhenRcJumpTooLarge() {
        // A --RC1-- B --RC5-- C --RC1-- D
        // B-C is RC5, needs jump of 4 to become RC1. maxRcJump=2 rejects it.
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("A"));
        graph.addNode(new Node("B"));
        graph.addNode(new Node("C"));
        graph.addNode(new Node("D"));
        graph.addEdge(new Edge("e1", "A", "B", TraversalMode.BOTH, 1));
        graph.addEdge(new Edge("e2", "B", "C", TraversalMode.BOTH, 5));
        graph.addEdge(new Edge("e3", "C", "D", TraversalMode.BOTH, 1));

        RepairConfig config = RepairConfig.builder()
                .rcLevelsToProcess(1)
                .maxRcJump(2)
                .build();
        EnforcementReport report = repair.enforce(graph, noExceptions, config);

        // Bridge rejected, smaller island downgraded
        assertEquals(0, report.totalUpgrades());
        assertEquals(1, report.totalDowngrades());
    }

    @Test
    public void exceptionSkipsIsland() {
        // A --RC1-- B    C --RC1-- D (island, but C-D is excepted)
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("A"));
        graph.addNode(new Node("B"));
        graph.addNode(new Node("C"));
        graph.addNode(new Node("D"));
        graph.addEdge(new Edge("e1", "A", "B", TraversalMode.BOTH, 1));
        graph.addEdge(new Edge("e2", "C", "D", TraversalMode.BOTH, 1));

        ExceptionRegistry exceptions = new ExceptionRegistry(Map.of("e2", "Peninsula dead end"));
        RepairConfig config = RepairConfig.builder().rcLevelsToProcess(1).build();
        EnforcementReport report = repair.enforce(graph, exceptions, config);

        assertEquals(0, report.totalUpgrades());
        assertEquals(0, report.totalDowngrades());
        assertEquals(1, report.exceptionHits().size());
    }

    @Test
    public void cascadeFromRc1ToRc2() {
        // A --RC1-- B    C --RC1-- D --RC2-- E
        // At RC1: C-D is an island → downgraded to RC2
        // At RC2: D-E and C-D(now RC2) should be connected
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("A"));
        graph.addNode(new Node("B"));
        graph.addNode(new Node("C"));
        graph.addNode(new Node("D"));
        graph.addNode(new Node("E"));
        graph.addEdge(new Edge("e1", "A", "B", TraversalMode.BOTH, 1));
        graph.addEdge(new Edge("e2", "C", "D", TraversalMode.BOTH, 1));
        graph.addEdge(new Edge("e3", "D", "E", TraversalMode.BOTH, 2));

        RepairConfig config = RepairConfig.builder().rcLevelsToProcess(1, 2).build();
        EnforcementReport report = repair.enforce(graph, noExceptions, config);

        // RC1: e2 downgraded to RC2
        assertEquals(1, report.totalDowngrades());
        // RC2: e2(now RC2) + e3 should form one component — no further changes
        assertEquals(0, report.totalUpgrades());
    }

    @Test
    public void rc5IslandReportedAsUnresolvable() {
        // A --RC5-- B    C --RC5-- D (no lower level to downgrade to)
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("A"));
        graph.addNode(new Node("B"));
        graph.addNode(new Node("C"));
        graph.addNode(new Node("D"));
        graph.addEdge(new Edge("e1", "A", "B", TraversalMode.BOTH, 5));
        graph.addEdge(new Edge("e2", "C", "D", TraversalMode.BOTH, 5));

        RepairConfig config = RepairConfig.builder().rcLevelsToProcess(5).build();
        EnforcementReport report = repair.enforce(graph, noExceptions, config);

        assertEquals(0, report.totalDowngrades());
        assertEquals(1, report.unresolvableIslands().size());
    }

    @Test
    public void bridgeSearchRespectsMaxHops() {
        // A --RC1-- B --RC3-- X1 --RC3-- X2 --RC3-- C --RC1-- D
        // Bridge path is 3 hops. maxBridgeHops=2 should reject it.
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("A"));
        graph.addNode(new Node("B"));
        graph.addNode(new Node("X1"));
        graph.addNode(new Node("X2"));
        graph.addNode(new Node("C"));
        graph.addNode(new Node("D"));
        graph.addEdge(new Edge("e1", "A", "B", TraversalMode.BOTH, 1));
        graph.addEdge(new Edge("e2", "B", "X1", TraversalMode.BOTH, 3));
        graph.addEdge(new Edge("e3", "X1", "X2", TraversalMode.BOTH, 3));
        graph.addEdge(new Edge("e4", "X2", "C", TraversalMode.BOTH, 3));
        graph.addEdge(new Edge("e5", "C", "D", TraversalMode.BOTH, 1));

        RepairConfig config = RepairConfig.builder()
                .rcLevelsToProcess(1)
                .maxBridgeHops(2)
                .maxRcJump(3)
                .build();
        EnforcementReport report = repair.enforce(graph, noExceptions, config);

        // Bridge rejected (3 hops > max 2), island downgraded
        assertEquals(0, report.totalUpgrades());
        assertEquals(1, report.totalDowngrades());
    }

    @Test
    public void bridgeSearchRespectsMaxPromotions() {
        // A --RC1-- B --RC3-- X1 --RC3-- X2 --RC3-- C --RC1-- D
        // Bridge needs 3 promotions. maxPromotions=2 should reject it.
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("A"));
        graph.addNode(new Node("B"));
        graph.addNode(new Node("X1"));
        graph.addNode(new Node("X2"));
        graph.addNode(new Node("C"));
        graph.addNode(new Node("D"));
        graph.addEdge(new Edge("e1", "A", "B", TraversalMode.BOTH, 1));
        graph.addEdge(new Edge("e2", "B", "X1", TraversalMode.BOTH, 3));
        graph.addEdge(new Edge("e3", "X1", "X2", TraversalMode.BOTH, 3));
        graph.addEdge(new Edge("e4", "X2", "C", TraversalMode.BOTH, 3));
        graph.addEdge(new Edge("e5", "C", "D", TraversalMode.BOTH, 1));

        RepairConfig config = RepairConfig.builder()
                .rcLevelsToProcess(1)
                .maxBridgeHops(10)
                .maxPromotions(2)
                .maxRcJump(3)
                .build();
        EnforcementReport report = repair.enforce(graph, noExceptions, config);

        // Bridge rejected (3 promotions > max 2), island downgraded
        assertEquals(0, report.totalUpgrades());
        assertEquals(1, report.totalDowngrades());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.repair.BridgeFirstCascadeRepairTest -q 2>&1 | tail -5`
Expected: Compilation failure

- [ ] **Step 3: Implement BridgeFirstCascadeRepair**

```java
package com.tomtom.routing.repair;

import com.tomtom.routing.analysis.ConnectivityResult;
import com.tomtom.routing.analysis.UndirectedAnalyzer;
import com.tomtom.routing.exception.ExceptionRegistry;
import com.tomtom.routing.model.*;

import java.util.*;
import java.util.stream.Collectors;

public class BridgeFirstCascadeRepair implements RepairStrategy {

    @Override
    public EnforcementReport enforce(RcGraph graph, ExceptionRegistry exceptions, RepairConfig config) {
        EnforcementReport report = new EnforcementReport();

        for (int level : config.rcLevelsToProcess()) {
            enforceLevel(graph, level, exceptions, config, report);
        }

        return report;
    }

    private void enforceLevel(RcGraph graph, int level, ExceptionRegistry exceptions,
                              RepairConfig config, EnforcementReport report) {
        ConnectivityResult result = UndirectedAnalyzer.analyze(graph, level);

        int componentsBefore = result.totalComponents();

        if (result.isConnected()) {
            report.recordComponentCount(level, componentsBefore, 1);
            return;
        }

        Set<String> mainComponentEdgeIds = result.mainComponent();

        for (Set<String> island : result.islands()) {
            // Check if all island edges are in exception registry
            boolean allExcepted = island.stream().allMatch(exceptions::isException);
            if (allExcepted) {
                for (String edgeId : island) {
                    report.addExceptionHit(edgeId, exceptions.justification(edgeId).orElse(""));
                }
                continue;
            }

            // Try bridge search
            List<Edge> bridge = findBridge(graph, island, mainComponentEdgeIds, level, config);

            if (bridge != null) {
                // Upgrade bridge edges
                for (Edge bridgeEdge : bridge) {
                    int oldRc = bridgeEdge.routingClass().orElse(level + 1);
                    bridgeEdge.setRoutingClass(level);
                    report.addChange(new RcChange(
                            bridgeEdge.id(), oldRc, level,
                            RcChange.Reason.UPGRADE,
                            "bridge to reconnect island at RC" + level
                    ));
                }
                // Add island edges to main component for subsequent islands
                mainComponentEdgeIds = new LinkedHashSet<>(mainComponentEdgeIds);
                mainComponentEdgeIds.addAll(island);
                for (Edge e : bridge) {
                    mainComponentEdgeIds.add(e.id());
                }
            } else {
                // Downgrade island
                if (level >= 5) {
                    report.addUnresolvableIsland(level, List.copyOf(island));
                } else {
                    for (String edgeId : island) {
                        Edge edge = graph.edge(edgeId);
                        if (edge != null) {
                            int oldRc = edge.routingClass().orElse(level);
                            edge.setRoutingClass(level + 1);
                            report.addChange(new RcChange(
                                    edgeId, oldRc, level + 1,
                                    RcChange.Reason.DOWNGRADE,
                                    "island at RC" + level + ", no viable bridge found"
                            ));
                        }
                    }
                }
            }
        }

        // Recount after repairs
        ConnectivityResult afterResult = UndirectedAnalyzer.analyze(graph, level);
        report.recordComponentCount(level, componentsBefore, afterResult.totalComponents());
    }

    private List<Edge> findBridge(RcGraph graph, Set<String> islandEdgeIds,
                                  Set<String> mainEdgeIds, int targetLevel,
                                  RepairConfig config) {
        // Collect boundary nodes of the island
        Set<String> islandNodes = new HashSet<>();
        for (String edgeId : islandEdgeIds) {
            Edge edge = graph.edge(edgeId);
            if (edge != null) {
                islandNodes.add(edge.sourceNodeId());
                islandNodes.add(edge.targetNodeId());
            }
        }

        // Collect nodes of the main component
        Set<String> mainNodes = new HashSet<>();
        for (String edgeId : mainEdgeIds) {
            Edge edge = graph.edge(edgeId);
            if (edge != null) {
                mainNodes.add(edge.sourceNodeId());
                mainNodes.add(edge.targetNodeId());
            }
        }

        // BFS from island boundary nodes on edges with RC > targetLevel
        // looking for a path to a main component node
        for (String startNode : islandNodes) {
            List<Edge> bridgePath = bfsForBridge(graph, startNode, mainNodes,
                    islandNodes, targetLevel, config);
            if (bridgePath != null) {
                return bridgePath;
            }
        }

        return null;
    }

    private List<Edge> bfsForBridge(RcGraph graph, String startNode, Set<String> mainNodes,
                                    Set<String> islandNodes, int targetLevel,
                                    RepairConfig config) {
        // BFS state: node → path of edges taken to reach it
        Queue<String> queue = new ArrayDeque<>();
        Map<String, List<Edge>> pathTo = new HashMap<>();

        queue.add(startNode);
        pathTo.put(startNode, List.of());

        while (!queue.isEmpty()) {
            String current = queue.poll();
            List<Edge> currentPath = pathTo.get(current);

            if (currentPath.size() >= config.maxBridgeHops()) {
                continue;
            }

            for (Edge edge : graph.edgesFrom(current)) {
                // Only traverse edges with RC > targetLevel (lower importance, candidates for promotion)
                if (edge.routingClass().isEmpty() || edge.routingClass().getAsInt() <= targetLevel) {
                    continue;
                }

                // Check maxRcJump
                int rcJump = edge.routingClass().getAsInt() - targetLevel;
                if (rcJump > config.maxRcJump()) {
                    continue;
                }

                String neighbor = edge.sourceNodeId().equals(current) ? edge.targetNodeId() : edge.sourceNodeId();

                if (pathTo.containsKey(neighbor)) {
                    continue;
                }

                List<Edge> newPath = new ArrayList<>(currentPath);
                newPath.add(edge);

                // Check if we reached the main component
                if (mainNodes.contains(neighbor) && !islandNodes.contains(neighbor)) {
                    if (newPath.size() <= config.maxPromotions()) {
                        return newPath;
                    }
                    continue; // path too long (too many promotions)
                }

                pathTo.put(neighbor, newPath);
                queue.add(neighbor);
            }
        }

        return null;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.repair.BridgeFirstCascadeRepairTest -q`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tomtom/routing/repair/BridgeFirstCascadeRepair.java \
        src/test/java/com/tomtom/routing/repair/BridgeFirstCascadeRepairTest.java
git commit -m "feat: add bridge-first cascade repair engine"
```

---

## Task 10: FRC CSV Adapter

**Files:**
- Create: `src/main/java/com/tomtom/routing/adapter/GraphAdapter.java`
- Create: `src/main/java/com/tomtom/routing/adapter/IdMapping.java`
- Create: `src/test/resources/frc-sample.csv`
- Create: `src/test/resources/frc-malformed.csv`
- Create: `src/test/java/com/tomtom/routing/adapter/FrcCsvAdapterTest.java`
- Create: `src/main/java/com/tomtom/routing/adapter/FrcCsvAdapter.java`

- [ ] **Step 1: Create GraphAdapter interface**

```java
package com.tomtom.routing.adapter;

import com.tomtom.routing.model.RcGraph;

public interface GraphAdapter {

    void populate(RcGraph graph);
}
```

- [ ] **Step 2: Create IdMapping interface**

```java
package com.tomtom.routing.adapter;

import java.util.Optional;

public interface IdMapping {

    Optional<String> toEdgeId(String productId);
}
```

- [ ] **Step 3: Create test fixtures**

`src/test/resources/frc-sample.csv`:
```
ProductId,Net2Class,CountryCode
P001,1,NLD
P002,2,NLD
P003,3,BEL
P004,5,DEU
```

`src/test/resources/frc-malformed.csv`:
```
ProductId,Net2Class,CountryCode
P001,1,NLD
P002,INVALID,NLD
P003,,BEL
,3,DEU
```

- [ ] **Step 4: Write failing tests**

```java
package com.tomtom.routing.adapter;

import com.tomtom.routing.model.*;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.*;

public class FrcCsvAdapterTest {

    private final IdMapping directMapping = productId ->
            Optional.of("e" + productId.substring(1)); // P001 → e001

    @Test
    public void parsesValidCsv() throws IOException {
        RcGraph graph = buildGraphWithEdges("e001", "e002", "e003", "e004");

        FrcCsvAdapter adapter = new FrcCsvAdapter(
                Path.of("src/test/resources/frc-sample.csv"), directMapping);
        adapter.populate(graph);

        assertEquals(1, graph.edge("e001").routingClass().getAsInt());
        assertEquals(2, graph.edge("e002").routingClass().getAsInt());
        assertEquals(3, graph.edge("e003").routingClass().getAsInt());
        assertEquals(5, graph.edge("e004").routingClass().getAsInt());
    }

    @Test
    public void skipsUnmappableProductIds() throws IOException {
        IdMapping partialMapping = productId -> {
            if (productId.equals("P001")) return Optional.of("e001");
            return Optional.empty();
        };

        RcGraph graph = buildGraphWithEdges("e001");
        FrcCsvAdapter adapter = new FrcCsvAdapter(
                Path.of("src/test/resources/frc-sample.csv"), partialMapping);
        adapter.populate(graph);

        assertEquals(1, graph.edge("e001").routingClass().getAsInt());
    }

    @Test
    public void skipsMalformedLines() throws IOException {
        RcGraph graph = buildGraphWithEdges("e001", "e002", "e003");
        FrcCsvAdapter adapter = new FrcCsvAdapter(
                Path.of("src/test/resources/frc-malformed.csv"), directMapping);
        adapter.populate(graph);

        // Only P001 is valid
        assertEquals(1, graph.edge("e001").routingClass().getAsInt());
        assertTrue(graph.edge("e002").routingClass().isEmpty());
        assertTrue(graph.edge("e003").routingClass().isEmpty());
    }

    @Test
    public void reportsParseStatistics() throws IOException {
        RcGraph graph = buildGraphWithEdges("e001", "e002", "e003");
        FrcCsvAdapter adapter = new FrcCsvAdapter(
                Path.of("src/test/resources/frc-sample.csv"), directMapping);
        adapter.populate(graph);

        assertEquals(4, adapter.totalLines());
        assertEquals(3, adapter.appliedCount()); // e001, e002, e003 (e004 not in graph)
        assertEquals(1, adapter.skippedCount()); // e004 not in graph
    }

    private RcGraph buildGraphWithEdges(String... edgeIds) {
        RcGraph graph = new RcGraph();
        int nodeCounter = 0;
        for (String edgeId : edgeIds) {
            String n1 = "n" + (nodeCounter++);
            String n2 = "n" + (nodeCounter++);
            graph.addNode(new Node(n1));
            graph.addNode(new Node(n2));
            graph.addEdge(new Edge(edgeId, n1, n2, TraversalMode.BOTH));
        }
        return graph;
    }
}
```

- [ ] **Step 5: Run tests to verify they fail**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.adapter.FrcCsvAdapterTest -q 2>&1 | tail -5`
Expected: Compilation failure

- [ ] **Step 6: Implement FrcCsvAdapter**

```java
package com.tomtom.routing.adapter;

import com.tomtom.routing.model.Edge;
import com.tomtom.routing.model.RcGraph;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public class FrcCsvAdapter implements GraphAdapter {

    private final Path csvPath;
    private final IdMapping idMapping;
    private int totalLines;
    private int appliedCount;
    private int skippedCount;

    public FrcCsvAdapter(Path csvPath, IdMapping idMapping) {
        this.csvPath = csvPath;
        this.idMapping = idMapping;
    }

    @Override
    public void populate(RcGraph graph) {
        try {
            List<String> lines = Files.readAllLines(csvPath);
            totalLines = 0;
            appliedCount = 0;
            skippedCount = 0;

            for (int i = 1; i < lines.size(); i++) { // skip header
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;

                totalLines++;
                String[] parts = line.split(",", -1);
                if (parts.length < 3) {
                    skippedCount++;
                    continue;
                }

                String productId = parts[0].trim();
                String rcStr = parts[1].trim();

                if (productId.isEmpty() || rcStr.isEmpty()) {
                    skippedCount++;
                    continue;
                }

                int rc;
                try {
                    rc = Integer.parseInt(rcStr);
                } catch (NumberFormatException e) {
                    skippedCount++;
                    continue;
                }

                if (rc < 1 || rc > 5) {
                    skippedCount++;
                    continue;
                }

                Optional<String> edgeId = idMapping.toEdgeId(productId);
                if (edgeId.isEmpty()) {
                    skippedCount++;
                    continue;
                }

                Edge edge = graph.edge(edgeId.get());
                if (edge == null) {
                    skippedCount++;
                    continue;
                }

                edge.setRoutingClass(rc);
                appliedCount++;
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read FRC CSV: " + csvPath, e);
        }
    }

    public int totalLines() { return totalLines; }
    public int appliedCount() { return appliedCount; }
    public int skippedCount() { return skippedCount; }
}
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.adapter.FrcCsvAdapterTest -q`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/tomtom/routing/adapter/GraphAdapter.java \
        src/main/java/com/tomtom/routing/adapter/IdMapping.java \
        src/main/java/com/tomtom/routing/adapter/FrcCsvAdapter.java \
        src/test/java/com/tomtom/routing/adapter/FrcCsvAdapterTest.java \
        src/test/resources/frc-sample.csv \
        src/test/resources/frc-malformed.csv
git commit -m "feat: add FRC CSV adapter with ID mapping"
```

---

## Task 11: JSON Report Writer

**Files:**
- Create: `src/main/java/com/tomtom/routing/writer/ResultWriter.java`
- Create: `src/test/java/com/tomtom/routing/writer/JsonReportWriterTest.java`
- Create: `src/main/java/com/tomtom/routing/writer/JsonReportWriter.java`

- [ ] **Step 1: Create ResultWriter interface**

```java
package com.tomtom.routing.writer;

import com.tomtom.routing.model.EnforcementReport;
import com.tomtom.routing.model.RcGraph;

import java.io.IOException;
import java.nio.file.Path;

public interface ResultWriter {

    void write(RcGraph graph, EnforcementReport report, Path outputPath) throws IOException;
}
```

- [ ] **Step 2: Write failing tests**

```java
package com.tomtom.routing.writer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tomtom.routing.model.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

public class JsonReportWriterTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void writesValidJson() throws IOException {
        EnforcementReport report = new EnforcementReport();
        report.addChange(new RcChange("e1", 3, 1, RcChange.Reason.UPGRADE, "bridge repair"));
        report.addChange(new RcChange("e2", 1, 2, RcChange.Reason.DOWNGRADE, "island at RC1"));
        report.recordComponentCount(1, 5, 1);
        report.addExceptionHit("e5", "Peninsula");
        report.addUnresolvableIsland(5, List.of("e10"));

        Path output = tempFolder.getRoot().toPath().resolve("report.json");
        new JsonReportWriter().write(new RcGraph(), report, output);

        JsonNode root = mapper.readTree(output.toFile());
        assertTrue(root.has("changes"));
        assertTrue(root.has("componentCounts"));
        assertTrue(root.has("exceptionHits"));
        assertTrue(root.has("unresolvableIslands"));
        assertTrue(root.has("summary"));

        assertEquals(2, root.get("changes").size());
        assertEquals(1, root.get("summary").get("totalUpgrades").asInt());
        assertEquals(1, root.get("summary").get("totalDowngrades").asInt());
    }

    @Test
    public void emptyReportWritesValidJson() throws IOException {
        EnforcementReport report = new EnforcementReport();

        Path output = tempFolder.getRoot().toPath().resolve("report.json");
        new JsonReportWriter().write(new RcGraph(), report, output);

        JsonNode root = mapper.readTree(output.toFile());
        assertEquals(0, root.get("changes").size());
        assertEquals(0, root.get("summary").get("totalUpgrades").asInt());
    }

    @Test
    public void changeContainsAllFields() throws IOException {
        EnforcementReport report = new EnforcementReport();
        report.addChange(new RcChange("e1", 3, 1, RcChange.Reason.UPGRADE, "bridge repair"));

        Path output = tempFolder.getRoot().toPath().resolve("report.json");
        new JsonReportWriter().write(new RcGraph(), report, output);

        JsonNode change = mapper.readTree(output.toFile()).get("changes").get(0);
        assertEquals("e1", change.get("edgeId").asText());
        assertEquals(3, change.get("oldRc").asInt());
        assertEquals(1, change.get("newRc").asInt());
        assertEquals("UPGRADE", change.get("reason").asText());
        assertEquals("bridge repair", change.get("context").asText());
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.writer.JsonReportWriterTest -q 2>&1 | tail -5`
Expected: Compilation failure

- [ ] **Step 4: Implement JsonReportWriter**

```java
package com.tomtom.routing.writer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tomtom.routing.model.EnforcementReport;
import com.tomtom.routing.model.RcChange;
import com.tomtom.routing.model.RcGraph;

import java.io.IOException;
import java.nio.file.Path;

public class JsonReportWriter implements ResultWriter {

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    public void write(RcGraph graph, EnforcementReport report, Path outputPath) throws IOException {
        ObjectNode root = mapper.createObjectNode();

        // Changes
        ArrayNode changesNode = root.putArray("changes");
        for (RcChange change : report.changes()) {
            ObjectNode changeNode = changesNode.addObject();
            changeNode.put("edgeId", change.edgeId());
            changeNode.put("oldRc", change.oldRc());
            changeNode.put("newRc", change.newRc());
            changeNode.put("reason", change.reason().name());
            changeNode.put("context", change.context());
        }

        // Component counts
        ObjectNode countsNode = root.putObject("componentCounts");
        for (var entry : report.componentCounts().entrySet()) {
            ObjectNode levelNode = countsNode.putObject("RC" + entry.getKey());
            levelNode.put("before", entry.getValue()[0]);
            levelNode.put("after", entry.getValue()[1]);
        }

        // Exception hits
        ArrayNode exceptionsNode = root.putArray("exceptionHits");
        for (var hit : report.exceptionHits()) {
            ObjectNode hitNode = exceptionsNode.addObject();
            hitNode.put("edgeId", hit.edgeId());
            hitNode.put("justification", hit.justification());
        }

        // Unresolvable islands
        ArrayNode unresolvableNode = root.putArray("unresolvableIslands");
        for (var island : report.unresolvableIslands()) {
            ObjectNode islandNode = unresolvableNode.addObject();
            islandNode.put("rcLevel", island.rcLevel());
            ArrayNode edgeIds = islandNode.putArray("edgeIds");
            for (String edgeId : island.edgeIds()) {
                edgeIds.add(edgeId);
            }
        }

        // Summary
        ObjectNode summary = root.putObject("summary");
        summary.put("totalUpgrades", report.totalUpgrades());
        summary.put("totalDowngrades", report.totalDowngrades());
        summary.put("totalChanges", report.changes().size());
        summary.put("exceptionHits", report.exceptionHits().size());
        summary.put("unresolvableIslands", report.unresolvableIslands().size());

        mapper.writeValue(outputPath.toFile(), root);
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.writer.JsonReportWriterTest -q`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/tomtom/routing/writer/ResultWriter.java \
        src/main/java/com/tomtom/routing/writer/JsonReportWriter.java \
        src/test/java/com/tomtom/routing/writer/JsonReportWriterTest.java
git commit -m "feat: add JSON report writer for enforcement diagnostics"
```

---

## Task 12: Parquet Result Writer

**Files:**
- Create: `src/test/java/com/tomtom/routing/writer/ParquetResultWriterTest.java`
- Create: `src/main/java/com/tomtom/routing/writer/ParquetResultWriter.java`

- [ ] **Step 1: Write failing tests**

```java
package com.tomtom.routing.writer;

import com.tomtom.routing.model.*;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path as HadoopPath;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.hadoop.ParquetReader;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class ParquetResultWriterTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void writesAllEdgesWithCorrectSchema() throws IOException {
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("n1"));
        graph.addNode(new Node("n2"));
        graph.addNode(new Node("n3"));
        graph.addEdge(new Edge("e1", "n1", "n2", TraversalMode.BOTH, 1));
        graph.addEdge(new Edge("e2", "n2", "n3", TraversalMode.BOTH, 2));

        EnforcementReport report = new EnforcementReport();
        report.addChange(new RcChange("e2", 3, 2, RcChange.Reason.DOWNGRADE, "island at RC1"));

        Path output = tempFolder.getRoot().toPath().resolve("result.parquet");
        new ParquetResultWriter().write(graph, report, output);

        List<GenericRecord> records = readParquet(output);
        assertEquals(2, records.size());

        GenericRecord r1 = records.stream()
                .filter(r -> r.get("edgeId").toString().equals("e1"))
                .findFirst().orElseThrow();
        assertEquals(1, r1.get("routingClass"));
        assertEquals("unchanged", r1.get("changeType").toString());

        GenericRecord r2 = records.stream()
                .filter(r -> r.get("edgeId").toString().equals("e2"))
                .findFirst().orElseThrow();
        assertEquals(2, r2.get("routingClass"));
        assertEquals("downgraded", r2.get("changeType").toString());
    }

    @Test
    public void edgesWithoutRcWrittenAsNull() throws IOException {
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("n1"));
        graph.addNode(new Node("n2"));
        graph.addEdge(new Edge("e1", "n1", "n2", TraversalMode.BOTH));

        Path output = tempFolder.getRoot().toPath().resolve("result.parquet");
        new ParquetResultWriter().write(graph, new EnforcementReport(), output);

        List<GenericRecord> records = readParquet(output);
        assertEquals(1, records.size());
        assertNull(records.get(0).get("routingClass"));
    }

    private List<GenericRecord> readParquet(Path path) throws IOException {
        List<GenericRecord> records = new ArrayList<>();
        Configuration conf = new Configuration();
        try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(
                new HadoopPath(path.toString())).withConf(conf).build()) {
            GenericRecord record;
            while ((record = reader.read()) != null) {
                records.add(record);
            }
        }
        return records;
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.writer.ParquetResultWriterTest -q 2>&1 | tail -5`
Expected: Compilation failure

- [ ] **Step 3: Implement ParquetResultWriter**

```java
package com.tomtom.routing.writer;

import com.tomtom.routing.model.*;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class ParquetResultWriter implements ResultWriter {

    private static final Schema SCHEMA = SchemaBuilder.record("RcAssignment")
            .namespace("com.tomtom.routing")
            .fields()
            .requiredString("edgeId")
            .optionalInt("routingClass")
            .requiredString("changeType")
            .optionalString("reason")
            .endRecord();

    @Override
    public void write(RcGraph graph, EnforcementReport report, Path outputPath) throws IOException {
        // Build change lookup
        Map<String, RcChange> changeMap = new HashMap<>();
        for (RcChange change : report.changes()) {
            changeMap.put(change.edgeId(), change);
        }

        org.apache.hadoop.fs.Path hadoopPath = new org.apache.hadoop.fs.Path(outputPath.toString());
        try (ParquetWriter<GenericRecord> writer = AvroParquetWriter.<GenericRecord>builder(hadoopPath)
                .withSchema(SCHEMA)
                .withConf(new Configuration())
                .withCompressionCodec(CompressionCodecName.SNAPPY)
                .build()) {

            for (Edge edge : graph.edges()) {
                GenericRecord record = new GenericData.Record(SCHEMA);
                record.put("edgeId", edge.id());

                if (edge.routingClass().isPresent()) {
                    record.put("routingClass", edge.routingClass().getAsInt());
                } else {
                    record.put("routingClass", null);
                }

                RcChange change = changeMap.get(edge.id());
                if (change != null) {
                    String changeType = change.reason() == RcChange.Reason.UPGRADE ? "upgraded" : "downgraded";
                    record.put("changeType", changeType);
                    record.put("reason", change.context());
                } else {
                    record.put("changeType", "unchanged");
                    record.put("reason", null);
                }

                writer.write(record);
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.writer.ParquetResultWriterTest -q`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tomtom/routing/writer/ParquetResultWriter.java \
        src/test/java/com/tomtom/routing/writer/ParquetResultWriterTest.java
git commit -m "feat: add Parquet result writer for repaired RC assignments"
```

---

## Task 13: ConnectivityEnforcer Orchestrator

**Files:**
- Create: `src/test/java/com/tomtom/routing/ConnectivityEnforcerTest.java`
- Create: `src/main/java/com/tomtom/routing/ConnectivityEnforcer.java`

- [ ] **Step 1: Write failing integration test**

```java
package com.tomtom.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tomtom.routing.adapter.ExceptionFileAdapter;
import com.tomtom.routing.adapter.FrcCsvAdapter;
import com.tomtom.routing.adapter.IdMapping;
import com.tomtom.routing.exception.ExceptionRegistry;
import com.tomtom.routing.model.*;
import com.tomtom.routing.repair.RepairConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.Assert.*;

public class ConnectivityEnforcerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void endToEndEnforcement() throws IOException {
        // Build a graph with RC1 connectivity issue:
        // A --RC1-- B    C --RC1-- D --RC3-- E --RC1-- F
        // Island {C,D} can be bridged via D-E (RC3→RC1)
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("A"));
        graph.addNode(new Node("B"));
        graph.addNode(new Node("C"));
        graph.addNode(new Node("D"));
        graph.addNode(new Node("E"));
        graph.addNode(new Node("F"));
        graph.addEdge(new Edge("e1", "A", "B", TraversalMode.BOTH, 1));
        graph.addEdge(new Edge("e2", "C", "D", TraversalMode.BOTH, 1));
        graph.addEdge(new Edge("e3", "D", "E", TraversalMode.BOTH, 3));
        graph.addEdge(new Edge("e4", "E", "F", TraversalMode.BOTH, 1));

        RepairConfig config = RepairConfig.builder()
                .rcLevelsToProcess(1)
                .maxRcJump(3)
                .build();

        Path parquetOut = tempFolder.getRoot().toPath().resolve("result.parquet");
        Path jsonOut = tempFolder.getRoot().toPath().resolve("report.json");

        ConnectivityEnforcer enforcer = new ConnectivityEnforcer(config);
        enforcer.enforce(graph, ExceptionFileAdapter.empty(), parquetOut, jsonOut);

        // Verify JSON report
        JsonNode root = new ObjectMapper().readTree(jsonOut.toFile());
        assertEquals(1, root.get("summary").get("totalUpgrades").asInt());
        assertEquals(0, root.get("summary").get("totalDowngrades").asInt());

        // Verify the bridge edge was promoted
        assertEquals(1, graph.edge("e3").routingClass().getAsInt());

        // Verify Parquet was written
        assertTrue(Files.exists(parquetOut));
    }

    @Test
    public void endToEndWithExceptions() throws IOException {
        // A --RC1-- B    C --RC1-- D (island, but excepted)
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("A"));
        graph.addNode(new Node("B"));
        graph.addNode(new Node("C"));
        graph.addNode(new Node("D"));
        graph.addEdge(new Edge("e1", "A", "B", TraversalMode.BOTH, 1));
        graph.addEdge(new Edge("e2", "C", "D", TraversalMode.BOTH, 1));

        // Write exception file
        Path excFile = tempFolder.getRoot().toPath().resolve("exceptions.txt");
        Files.writeString(excFile, "e2 # Peninsula dead end\n");
        ExceptionRegistry exceptions = ExceptionFileAdapter.load(excFile);

        RepairConfig config = RepairConfig.builder().rcLevelsToProcess(1).build();

        Path parquetOut = tempFolder.getRoot().toPath().resolve("result.parquet");
        Path jsonOut = tempFolder.getRoot().toPath().resolve("report.json");

        ConnectivityEnforcer enforcer = new ConnectivityEnforcer(config);
        enforcer.enforce(graph, exceptions, parquetOut, jsonOut);

        JsonNode root = new ObjectMapper().readTree(jsonOut.toFile());
        assertEquals(0, root.get("summary").get("totalChanges").asInt());
        assertEquals(1, root.get("summary").get("exceptionHits").asInt());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.ConnectivityEnforcerTest -q 2>&1 | tail -5`
Expected: Compilation failure

- [ ] **Step 3: Implement ConnectivityEnforcer**

```java
package com.tomtom.routing;

import com.tomtom.routing.exception.ExceptionRegistry;
import com.tomtom.routing.model.EnforcementReport;
import com.tomtom.routing.model.RcGraph;
import com.tomtom.routing.repair.BridgeFirstCascadeRepair;
import com.tomtom.routing.repair.RepairConfig;
import com.tomtom.routing.repair.RepairStrategy;
import com.tomtom.routing.writer.JsonReportWriter;
import com.tomtom.routing.writer.ParquetResultWriter;

import java.io.IOException;
import java.nio.file.Path;

public class ConnectivityEnforcer {

    private final RepairConfig config;
    private final RepairStrategy repairStrategy;

    public ConnectivityEnforcer(RepairConfig config) {
        this(config, new BridgeFirstCascadeRepair());
    }

    public ConnectivityEnforcer(RepairConfig config, RepairStrategy repairStrategy) {
        this.config = config;
        this.repairStrategy = repairStrategy;
    }

    public EnforcementReport enforce(RcGraph graph, ExceptionRegistry exceptions,
                                     Path parquetOutput, Path jsonReportOutput) throws IOException {
        EnforcementReport report = repairStrategy.enforce(graph, exceptions, config);

        new ParquetResultWriter().write(graph, report, parquetOutput);
        new JsonReportWriter().write(graph, report, jsonReportOutput);

        return report;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.ConnectivityEnforcerTest -q`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tomtom/routing/ConnectivityEnforcer.java \
        src/test/java/com/tomtom/routing/ConnectivityEnforcerTest.java
git commit -m "feat: add ConnectivityEnforcer orchestrator"
```

---

## Task 14: Full Test Suite Verification

**Files:** None (verification only)

- [ ] **Step 1: Run all tests**

Run: `mvn test -q`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 2: Verify test count**

Run: `mvn test 2>&1 | grep "Tests run:"`
Expected: All test classes report 0 failures, 0 errors

- [ ] **Step 3: Commit any cleanup if needed**

If all tests pass, no commit needed. If any fix was required, commit with:

```bash
git commit -m "fix: resolve test failures from full suite run"
```

---

## Task 15: Remove Scaffolding Gitkeep Files

**Files:**
- Delete: `src/main/java/com/tomtom/routing/.gitkeep`
- Delete: `src/test/java/com/tomtom/routing/.gitkeep`
- Delete: `src/main/resources/.gitkeep`
- Delete: `src/test/resources/.gitkeep`

- [ ] **Step 1: Remove gitkeep files that are no longer needed**

```bash
git rm src/main/java/com/tomtom/routing/.gitkeep \
       src/test/java/com/tomtom/routing/.gitkeep \
       src/main/resources/.gitkeep \
       src/test/resources/.gitkeep
```

- [ ] **Step 2: Commit**

```bash
git commit -m "chore: remove scaffolding .gitkeep files"
```
