# Routing Class Batch Algorithm — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a batch algorithm that reads an Orbis PBF, assigns Routing Class 1–5 to every road via attribute seeding + betweenness centrality + connectivity enforcement, writes a new PBF with computed RC, and compares against existing RC values.

**Architecture:** Three-phase pipeline (Parse → Seed & Refine → Enforce) operating on a shared in-memory directed graph model. The graph is built via a builder pattern decoupled from PBF. Ways are split at intersection nodes for correct topology. After per-segment RC computation, results are aggregated back to per-way for output.

**Tech Stack:** Java 17, Maven, osm4j-pbf (PBF read/write), JUnit 4, no external graph libraries (algorithms implemented in-project).

**Spec:** `docs/superpowers/specs/2026-04-16-routing-class-batch-algorithm-design.md`

**Known simplification:** Turn restrictions are specified but deferred from this prototype. The spec requires accessibility-aware SCC computation using restriction relations (from/to/via). Implementing this correctly requires modeling prohibited turn sequences in the graph traversal, which significantly complicates Dijkstra, Tarjan, and the bridging logic. For the Luxembourg prototype, we proceed without turn restriction enforcement and note it as a known gap in comparison results. This can be added as a follow-up task.

---

## File Structure

```
src/main/java/com/tomtom/routing/
  model/
    RoutingClass.java          — enum RC1-RC5
    RoadEdge.java              — directed edge: id, geometry, attributes, RC values, length
    RoadNode.java              — node: id, coordinates, outgoing edges
    RoadGraph.java             — graph container: nodes + edges, adjacency queries
    RoadGraphBuilder.java      — builder: addWay(), addNode(), addRestriction(), build()
  io/
    PbfReader.java             — reads Orbis PBF, drives RoadGraphBuilder
    PbfWriter.java             — writes output PBF (geometry + computed RC per way)
  algorithm/
    AttributeSeeder.java       — Phase 2 step 1: attribute-based RC seed
    Dijkstra.java              — single-source shortest path (used by centrality + bridging)
    CentralityComputer.java    — Phase 2 step 2: sampled edge betweenness centrality
    RcRefiner.java             — Phase 2: combine seed + centrality → adjusted RC
    TarjanScc.java             — Tarjan's strongly connected components algorithm
    ConnectivityEnforcer.java  — Phase 3: top-down SCC enforcement with bridging
  comparison/
    RcComparator.java          — compare computed vs existing RC, produce metrics
    ReportWriter.java          — write comparison report, per-road diff CSV, aggregated CSV
  BatchJob.java                — main entry point, orchestrates full pipeline

src/test/java/com/tomtom/routing/
  model/
    RoutingClassTest.java
    RoadGraphBuilderTest.java
  algorithm/
    AttributeSeederTest.java
    DijkstraTest.java
    CentralityComputerTest.java
    RcRefinerTest.java
    TarjanSccTest.java
    ConnectivityEnforcerTest.java
  comparison/
    RcComparatorTest.java
  BatchJobIntegrationTest.java
```

---

### Task 1: Project Setup — Maven Dependencies

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add osm4j repositories and dependencies to pom.xml**

Add the topobyte/slimjars repositories and osm4j-pbf + osm4j-core dependencies:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.tomtom.routing</groupId>
    <artifactId>routing-class-workshop</artifactId>
    <version>1.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <name>routing-class-workshop</name>

    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <repositories>
        <repository>
            <id>topobyte</id>
            <url>https://mvn.topobyte.de</url>
        </repository>
        <repository>
            <id>slimjars</id>
            <url>https://mvn.slimjars.com</url>
        </repository>
    </repositories>

    <dependencies>
        <dependency>
            <groupId>de.topobyte</groupId>
            <artifactId>osm4j-pbf</artifactId>
            <version>1.4.1</version>
        </dependency>
        <dependency>
            <groupId>de.topobyte</groupId>
            <artifactId>osm4j-core</artifactId>
            <version>1.4.0</version>
        </dependency>
        <dependency>
            <groupId>junit</groupId>
            <artifactId>junit</artifactId>
            <version>4.13.2</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.11.0</version>
                <configuration>
                    <source>17</source>
                    <target>17</target>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Verify dependencies resolve**

Run: `mvn dependency:resolve`
Expected: BUILD SUCCESS, osm4j-pbf and osm4j-core downloaded.

- [ ] **Step 3: Remove .gitkeep files from src directories**

```bash
rm src/main/java/com/tomtom/routing/.gitkeep
rm src/test/java/com/tomtom/routing/.gitkeep
rm src/main/resources/.gitkeep
rm src/test/resources/.gitkeep
```

- [ ] **Step 4: Commit**

```bash
git add pom.xml src/
git commit -m "chore: add osm4j-pbf dependencies and clean up gitkeep files"
```

---

### Task 2: Model — RoutingClass Enum

**Files:**
- Create: `src/main/java/com/tomtom/routing/model/RoutingClass.java`
- Create: `src/test/java/com/tomtom/routing/model/RoutingClassTest.java`

- [ ] **Step 1: Write the test**

```java
package com.tomtom.routing.model;

import org.junit.Test;
import static org.junit.Assert.*;

public class RoutingClassTest {

    @Test
    public void valuesAreOneToFive() {
        assertEquals(1, RoutingClass.RC1.value());
        assertEquals(2, RoutingClass.RC2.value());
        assertEquals(3, RoutingClass.RC3.value());
        assertEquals(4, RoutingClass.RC4.value());
        assertEquals(5, RoutingClass.RC5.value());
    }

    @Test
    public void fromValueReturnsCorrectEnum() {
        assertEquals(RoutingClass.RC1, RoutingClass.fromValue(1));
        assertEquals(RoutingClass.RC5, RoutingClass.fromValue(5));
    }

    @Test(expected = IllegalArgumentException.class)
    public void fromValueThrowsForInvalidValue() {
        RoutingClass.fromValue(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void fromValueThrowsForSix() {
        RoutingClass.fromValue(6);
    }

    @Test
    public void promoteReducesValue() {
        assertEquals(RoutingClass.RC1, RoutingClass.RC2.promote());
    }

    @Test
    public void promoteAtRC1ReturnsRC1() {
        assertEquals(RoutingClass.RC1, RoutingClass.RC1.promote());
    }

    @Test
    public void demoteIncreasesValue() {
        assertEquals(RoutingClass.RC5, RoutingClass.RC4.demote());
    }

    @Test
    public void demoteAtRC5ReturnsRC5() {
        assertEquals(RoutingClass.RC5, RoutingClass.RC5.demote());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.model.RoutingClassTest`
Expected: Compilation error — RoutingClass does not exist.

- [ ] **Step 3: Implement RoutingClass**

```java
package com.tomtom.routing.model;

public enum RoutingClass {
    RC1(1), RC2(2), RC3(3), RC4(4), RC5(5);

    private final int value;

    RoutingClass(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public static RoutingClass fromValue(int value) {
        for (RoutingClass rc : values()) {
            if (rc.value == value) {
                return rc;
            }
        }
        throw new IllegalArgumentException("Invalid routing class value: " + value);
    }

    public RoutingClass promote() {
        if (this == RC1) return RC1;
        return fromValue(value - 1);
    }

    public RoutingClass demote() {
        if (this == RC5) return RC5;
        return fromValue(value + 1);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.model.RoutingClassTest`
Expected: All 8 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tomtom/routing/model/RoutingClass.java src/test/java/com/tomtom/routing/model/RoutingClassTest.java
git commit -m "feat: add RoutingClass enum (RC1-RC5) with promote/demote"
```

---

### Task 3: Model — RoadEdge, RoadNode, RoadGraph

**Files:**
- Create: `src/main/java/com/tomtom/routing/model/RoadEdge.java`
- Create: `src/main/java/com/tomtom/routing/model/RoadNode.java`
- Create: `src/main/java/com/tomtom/routing/model/RoadGraph.java`

- [ ] **Step 1: Create RoadEdge**

A directed edge in the routing graph. Represents a segment of a road (a way may be split into multiple segments at intersection nodes).

```java
package com.tomtom.routing.model;

import java.util.List;
import java.util.Map;

public class RoadEdge {
    private final String id;
    private final long parentWayId;
    private final long fromNodeId;
    private final long toNodeId;
    private final List<double[]> geometry; // list of [lon, lat]
    private final Map<String, String> attributes;
    private final double lengthMeters;
    private RoutingClass computedRc;
    private RoutingClass existingRc;

    public RoadEdge(String id, long parentWayId, long fromNodeId, long toNodeId,
                    List<double[]> geometry, Map<String, String> attributes, double lengthMeters) {
        this.id = id;
        this.parentWayId = parentWayId;
        this.fromNodeId = fromNodeId;
        this.toNodeId = toNodeId;
        this.geometry = geometry;
        this.attributes = attributes;
        this.lengthMeters = lengthMeters;
    }

    public String getId() { return id; }
    public long getParentWayId() { return parentWayId; }
    public long getFromNodeId() { return fromNodeId; }
    public long getToNodeId() { return toNodeId; }
    public List<double[]> getGeometry() { return geometry; }
    public Map<String, String> getAttributes() { return attributes; }
    public double getLengthMeters() { return lengthMeters; }

    public String getAttribute(String key) { return attributes.get(key); }
    public boolean hasAttribute(String key) { return attributes.containsKey(key); }

    public RoutingClass getComputedRc() { return computedRc; }
    public void setComputedRc(RoutingClass rc) { this.computedRc = rc; }
    public RoutingClass getExistingRc() { return existingRc; }
    public void setExistingRc(RoutingClass rc) { this.existingRc = rc; }
}
```

- [ ] **Step 2: Create RoadNode**

```java
package com.tomtom.routing.model;

import java.util.ArrayList;
import java.util.List;

public class RoadNode {
    private final long id;
    private final double lat;
    private final double lon;
    private final List<RoadEdge> outgoingEdges = new ArrayList<>();

    public RoadNode(long id, double lat, double lon) {
        this.id = id;
        this.lat = lat;
        this.lon = lon;
    }

    public long getId() { return id; }
    public double getLat() { return lat; }
    public double getLon() { return lon; }
    public List<RoadEdge> getOutgoingEdges() { return outgoingEdges; }

    public void addOutgoingEdge(RoadEdge edge) {
        outgoingEdges.add(edge);
    }
}
```

- [ ] **Step 3: Create RoadGraph**

```java
package com.tomtom.routing.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RoadGraph {
    private final Map<Long, RoadNode> nodes;
    private final List<RoadEdge> edges;

    public RoadGraph(Map<Long, RoadNode> nodes, List<RoadEdge> edges) {
        this.nodes = Collections.unmodifiableMap(new HashMap<>(nodes));
        this.edges = Collections.unmodifiableList(new ArrayList<>(edges));
    }

    public RoadNode getNode(long id) { return nodes.get(id); }
    public Map<Long, RoadNode> getNodes() { return nodes; }
    public List<RoadEdge> getEdges() { return edges; }
    public int nodeCount() { return nodes.size(); }
    public int edgeCount() { return edges.size(); }
}
```

- [ ] **Step 4: Verify compilation**

Run: `mvn compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tomtom/routing/model/RoadEdge.java src/main/java/com/tomtom/routing/model/RoadNode.java src/main/java/com/tomtom/routing/model/RoadGraph.java
git commit -m "feat: add RoadEdge, RoadNode, and RoadGraph model classes"
```

---

### Task 4: Model — RoadGraphBuilder

**Files:**
- Create: `src/main/java/com/tomtom/routing/model/RoadGraphBuilder.java`
- Create: `src/test/java/com/tomtom/routing/model/RoadGraphBuilderTest.java`

The builder accepts raw ways (with node refs) and nodes (with coordinates), splits ways at intersection nodes, creates directed edges based on oneway rules, and builds the final RoadGraph.

- [ ] **Step 1: Write the test**

```java
package com.tomtom.routing.model;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class RoadGraphBuilderTest {

    @Test
    public void buildSimpleTwoWayRoad() {
        // A single two-way road: node1 -> node2
        RoadGraphBuilder builder = new RoadGraphBuilder();
        builder.addNode(1, 49.6, 6.1);
        builder.addNode(2, 49.7, 6.2);
        builder.addWay(100, new long[]{1, 2}, Map.of("highway", "primary"));

        RoadGraph graph = builder.build();

        // Two-way road produces 2 directed edges (forward + backward)
        assertEquals(2, graph.edgeCount());
        assertEquals(2, graph.nodeCount());

        RoadNode node1 = graph.getNode(1);
        RoadNode node2 = graph.getNode(2);
        assertEquals(1, node1.getOutgoingEdges().size());
        assertEquals(1, node2.getOutgoingEdges().size());

        // Forward edge: 1 -> 2
        RoadEdge forward = node1.getOutgoingEdges().get(0);
        assertEquals(1, forward.getFromNodeId());
        assertEquals(2, forward.getToNodeId());
        assertEquals("primary", forward.getAttribute("highway"));

        // Backward edge: 2 -> 1
        RoadEdge backward = node2.getOutgoingEdges().get(0);
        assertEquals(2, backward.getFromNodeId());
        assertEquals(1, backward.getToNodeId());
    }

    @Test
    public void buildOnewayRoad() {
        RoadGraphBuilder builder = new RoadGraphBuilder();
        builder.addNode(1, 49.6, 6.1);
        builder.addNode(2, 49.7, 6.2);
        builder.addWay(100, new long[]{1, 2}, Map.of("highway", "motorway", "oneway", "yes"));

        RoadGraph graph = builder.build();

        // Oneway produces 1 directed edge
        assertEquals(1, graph.edgeCount());
        RoadNode node1 = graph.getNode(1);
        assertEquals(1, node1.getOutgoingEdges().size());
        RoadNode node2 = graph.getNode(2);
        assertEquals(0, node2.getOutgoingEdges().size());
    }

    @Test
    public void buildReverseOnewayRoad() {
        RoadGraphBuilder builder = new RoadGraphBuilder();
        builder.addNode(1, 49.6, 6.1);
        builder.addNode(2, 49.7, 6.2);
        builder.addWay(100, new long[]{1, 2}, Map.of("highway", "residential", "oneway", "-1"));

        RoadGraph graph = builder.build();

        assertEquals(1, graph.edgeCount());
        RoadNode node2 = graph.getNode(2);
        assertEquals(1, node2.getOutgoingEdges().size());
        assertEquals(1, node2.getOutgoingEdges().get(0).getToNodeId());
    }

    @Test
    public void roundaboutIsTreatedAsOneway() {
        RoadGraphBuilder builder = new RoadGraphBuilder();
        builder.addNode(1, 49.6, 6.1);
        builder.addNode(2, 49.7, 6.2);
        builder.addWay(100, new long[]{1, 2}, Map.of("highway", "tertiary", "junction", "roundabout"));

        RoadGraph graph = builder.build();
        assertEquals(1, graph.edgeCount());
    }

    @Test
    public void waySplitAtIntersectionNode() {
        // Way A: nodes [1, 2, 3], Way B: nodes [4, 2, 5]
        // Node 2 is shared (intersection) — Way A splits into [1->2] and [2->3]
        RoadGraphBuilder builder = new RoadGraphBuilder();
        builder.addNode(1, 49.60, 6.10);
        builder.addNode(2, 49.65, 6.15);
        builder.addNode(3, 49.70, 6.20);
        builder.addNode(4, 49.60, 6.20);
        builder.addNode(5, 49.70, 6.10);
        builder.addWay(100, new long[]{1, 2, 3}, Map.of("highway", "primary", "oneway", "yes"));
        builder.addWay(200, new long[]{4, 2, 5}, Map.of("highway", "secondary", "oneway", "yes"));

        RoadGraph graph = builder.build();

        // Way 100 splits into 2 segments, Way 200 splits into 2 segments = 4 oneway edges
        assertEquals(4, graph.edgeCount());

        // Node 2 should have outgoing edges from both ways
        RoadNode node2 = graph.getNode(2);
        assertEquals(2, node2.getOutgoingEdges().size());
    }

    @Test
    public void edgeLengthIsComputed() {
        RoadGraphBuilder builder = new RoadGraphBuilder();
        builder.addNode(1, 49.6, 6.1);
        builder.addNode(2, 49.7, 6.2);
        builder.addWay(100, new long[]{1, 2}, Map.of("highway", "primary", "oneway", "yes"));

        RoadGraph graph = builder.build();
        RoadEdge edge = graph.getEdges().get(0);

        // Distance between (49.6, 6.1) and (49.7, 6.2) is ~13.2 km
        assertTrue(edge.getLengthMeters() > 13000);
        assertTrue(edge.getLengthMeters() < 14000);
    }

    @Test
    public void existingRcIsExtracted() {
        RoadGraphBuilder builder = new RoadGraphBuilder();
        builder.addNode(1, 49.6, 6.1);
        builder.addNode(2, 49.7, 6.2);
        builder.addWay(100, new long[]{1, 2},
            Map.of("highway", "motorway", "oneway", "yes", "routing_class", "1"));

        RoadGraph graph = builder.build();
        RoadEdge edge = graph.getEdges().get(0);
        assertEquals(RoutingClass.RC1, edge.getExistingRc());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.model.RoadGraphBuilderTest`
Expected: Compilation error — RoadGraphBuilder does not exist.

- [ ] **Step 3: Implement RoadGraphBuilder**

```java
package com.tomtom.routing.model;

import java.util.*;

public class RoadGraphBuilder {

    private final Map<Long, double[]> nodeCoords = new HashMap<>();
    private final List<RawWay> rawWays = new ArrayList<>();

    public void addNode(long id, double lat, double lon) {
        nodeCoords.put(id, new double[]{lon, lat});
    }

    public void addWay(long id, long[] nodeRefs, Map<String, String> tags) {
        rawWays.add(new RawWay(id, nodeRefs, tags));
    }

    public RoadGraph build() {
        Set<Long> intersectionNodes = findIntersectionNodes();
        Map<Long, RoadNode> nodes = new HashMap<>();
        List<RoadEdge> edges = new ArrayList<>();

        for (RawWay way : rawWays) {
            List<long[]> segments = splitAtIntersections(way.nodeRefs, intersectionNodes);

            for (int s = 0; s < segments.size(); s++) {
                long[] segNodeRefs = segments.get(s);
                long fromId = segNodeRefs[0];
                long toId = segNodeRefs[segNodeRefs.length - 1];

                List<double[]> geometry = buildGeometry(segNodeRefs);
                double length = computeLength(geometry);

                ensureNode(nodes, fromId);
                ensureNode(nodes, toId);

                boolean isOneway = isOneway(way.tags);
                boolean isReverse = "yes".equals(way.tags.get("oneway")) ? false
                        : "-1".equals(way.tags.get("oneway"));

                if (isOneway && isReverse) {
                    RoadEdge backward = createEdge(way, s, toId, fromId, reverseGeometry(geometry), length);
                    extractExistingRc(backward, way.tags);
                    edges.add(backward);
                    nodes.get(toId).addOutgoingEdge(backward);
                } else if (isOneway) {
                    RoadEdge forward = createEdge(way, s, fromId, toId, geometry, length);
                    extractExistingRc(forward, way.tags);
                    edges.add(forward);
                    nodes.get(fromId).addOutgoingEdge(forward);
                } else {
                    RoadEdge forward = createEdge(way, s, fromId, toId, geometry, length);
                    extractExistingRc(forward, way.tags);
                    edges.add(forward);
                    nodes.get(fromId).addOutgoingEdge(forward);

                    RoadEdge backward = createEdge(way, s, toId, fromId, reverseGeometry(geometry), length);
                    extractExistingRc(backward, way.tags);
                    edges.add(backward);
                    nodes.get(toId).addOutgoingEdge(backward);
                }
            }
        }

        return new RoadGraph(nodes, edges);
    }

    private Set<Long> findIntersectionNodes() {
        Map<Long, Integer> refCounts = new HashMap<>();
        Set<Long> endpoints = new HashSet<>();

        for (RawWay way : rawWays) {
            endpoints.add(way.nodeRefs[0]);
            endpoints.add(way.nodeRefs[way.nodeRefs.length - 1]);
            for (long ref : way.nodeRefs) {
                refCounts.merge(ref, 1, Integer::sum);
            }
        }

        Set<Long> intersections = new HashSet<>(endpoints);
        for (Map.Entry<Long, Integer> entry : refCounts.entrySet()) {
            if (entry.getValue() > 1) {
                intersections.add(entry.getKey());
            }
        }
        return intersections;
    }

    private List<long[]> splitAtIntersections(long[] nodeRefs, Set<Long> intersections) {
        List<long[]> segments = new ArrayList<>();
        int start = 0;

        for (int i = 1; i < nodeRefs.length; i++) {
            if (intersections.contains(nodeRefs[i]) || i == nodeRefs.length - 1) {
                long[] segment = Arrays.copyOfRange(nodeRefs, start, i + 1);
                segments.add(segment);
                start = i;
            }
        }
        return segments;
    }

    private List<double[]> buildGeometry(long[] segNodeRefs) {
        List<double[]> geom = new ArrayList<>();
        for (long ref : segNodeRefs) {
            double[] coord = nodeCoords.get(ref);
            if (coord != null) {
                geom.add(coord);
            }
        }
        return geom;
    }

    private List<double[]> reverseGeometry(List<double[]> geometry) {
        List<double[]> reversed = new ArrayList<>(geometry);
        Collections.reverse(reversed);
        return reversed;
    }

    private double computeLength(List<double[]> geometry) {
        double totalMeters = 0;
        for (int i = 1; i < geometry.size(); i++) {
            totalMeters += haversineMeters(
                geometry.get(i - 1)[1], geometry.get(i - 1)[0],
                geometry.get(i)[1], geometry.get(i)[0]
            );
        }
        return totalMeters;
    }

    static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double R = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    private RoadEdge createEdge(RawWay way, int segIndex, long fromId, long toId,
                                List<double[]> geometry, double length) {
        String edgeId = way.id + "_" + segIndex;
        return new RoadEdge(edgeId, way.id, fromId, toId, geometry,
                            new HashMap<>(way.tags), length);
    }

    private void extractExistingRc(RoadEdge edge, Map<String, String> tags) {
        String rcStr = tags.get("routing_class");
        if (rcStr != null) {
            try {
                edge.setExistingRc(RoutingClass.fromValue(Integer.parseInt(rcStr)));
            } catch (NumberFormatException | IllegalArgumentException e) {
                // Ignore invalid RC values
            }
        }
    }

    private boolean isOneway(Map<String, String> tags) {
        if ("yes".equals(tags.get("oneway")) || "-1".equals(tags.get("oneway"))) return true;
        if ("roundabout".equals(tags.get("junction")) || "circular".equals(tags.get("junction"))) return true;
        return false;
    }

    private void ensureNode(Map<Long, RoadNode> nodes, long id) {
        if (!nodes.containsKey(id)) {
            double[] coord = nodeCoords.getOrDefault(id, new double[]{0, 0});
            nodes.put(id, new RoadNode(id, coord[1], coord[0]));
        }
    }

    private record RawWay(long id, long[] nodeRefs, Map<String, String> tags) {}
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.model.RoadGraphBuilderTest`
Expected: All 7 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tomtom/routing/model/RoadGraphBuilder.java src/test/java/com/tomtom/routing/model/RoadGraphBuilderTest.java
git commit -m "feat: add RoadGraphBuilder with way splitting and oneway support"
```

---

### Task 5: IO — PbfReader

**Files:**
- Create: `src/main/java/com/tomtom/routing/io/PbfReader.java`

The PbfReader reads an Orbis PBF and drives the RoadGraphBuilder. It filters out pedestrian-only features and non-navigable roads per the spec. No unit test for this — it's tested via the integration test with the real PBF (Task 16).

- [ ] **Step 1: Implement PbfReader**

```java
package com.tomtom.routing.io;

import com.tomtom.routing.model.RoadGraph;
import com.tomtom.routing.model.RoadGraphBuilder;
import de.topobyte.osm4j.core.access.OsmIterator;
import de.topobyte.osm4j.core.model.iface.*;
import de.topobyte.osm4j.core.model.util.OsmModelUtil;
import de.topobyte.osm4j.pbf.seq.PbfIterator;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public class PbfReader {

    private static final Set<String> EXCLUDED_HIGHWAY_TYPES = Set.of(
        "footway", "steps", "path", "cycleway", "pedestrian"
    );

    private static final Set<String> EXCLUDED_NAVIGABILITY = Set.of(
        "closed", "prohibited"
    );

    public RoadGraph read(String pbfPath) throws IOException {
        RoadGraphBuilder builder = new RoadGraphBuilder();

        // First pass: collect ways and their node references
        List<long[]> wayNodeRefs = new ArrayList<>();
        Set<Long> neededNodeIds = new HashSet<>();

        try (InputStream input = new FileInputStream(pbfPath)) {
            OsmIterator iterator = new PbfIterator(input, true);
            for (EntityContainer container : iterator) {
                if (container.getType() == EntityType.Way) {
                    OsmWay way = (OsmWay) container.getEntity();
                    Map<String, String> tags = OsmModelUtil.getTagsAsMap(way);

                    if (!isRoadOrFerry(tags)) continue;
                    if (isExcluded(tags)) continue;

                    long[] nodeRefs = new long[way.getNumberOfNodes()];
                    for (int i = 0; i < way.getNumberOfNodes(); i++) {
                        nodeRefs[i] = way.getNodeId(i);
                        neededNodeIds.add(nodeRefs[i]);
                    }
                    wayNodeRefs.add(nodeRefs);
                    builder.addWay(way.getId(), nodeRefs, tags);
                }
            }
        }

        // Second pass: collect node coordinates
        try (InputStream input = new FileInputStream(pbfPath)) {
            OsmIterator iterator = new PbfIterator(input, true);
            for (EntityContainer container : iterator) {
                if (container.getType() == EntityType.Node) {
                    OsmNode node = (OsmNode) container.getEntity();
                    if (neededNodeIds.contains(node.getId())) {
                        builder.addNode(node.getId(), node.getLatitude(), node.getLongitude());
                    }
                }
            }
        }

        return builder.build();
    }

    private boolean isRoadOrFerry(Map<String, String> tags) {
        return tags.containsKey("highway") || tags.containsKey("ferry");
    }

    private boolean isExcluded(Map<String, String> tags) {
        String highway = tags.get("highway");
        if (highway != null && EXCLUDED_HIGHWAY_TYPES.contains(highway)) return true;

        String navigability = tags.get("navigability");
        if (navigability != null && EXCLUDED_NAVIGABILITY.contains(navigability)) return true;

        return false;
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/tomtom/routing/io/PbfReader.java
git commit -m "feat: add PbfReader to parse Orbis PBF into RoadGraph"
```

---

### Task 6: Algorithm — AttributeSeeder

**Files:**
- Create: `src/main/java/com/tomtom/routing/algorithm/AttributeSeeder.java`
- Create: `src/test/java/com/tomtom/routing/algorithm/AttributeSeederTest.java`

- [ ] **Step 1: Write the test**

```java
package com.tomtom.routing.algorithm;

import com.tomtom.routing.model.*;
import org.junit.Test;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class AttributeSeederTest {

    private RoadGraph buildSingleEdgeGraph(Map<String, String> tags) {
        RoadGraphBuilder builder = new RoadGraphBuilder();
        builder.addNode(1, 49.6, 6.1);
        builder.addNode(2, 49.7, 6.2);
        builder.addWay(100, new long[]{1, 2}, tags);
        return builder.build();
    }

    @Test
    public void motorwayWithControlledAccessSeedsRC1() {
        RoadGraph graph = buildSingleEdgeGraph(
            Map.of("highway", "motorway", "controlled_access", "yes", "oneway", "yes"));
        new AttributeSeeder().seed(graph);
        assertEquals(RoutingClass.RC1, graph.getEdges().get(0).getComputedRc());
    }

    @Test
    public void motorwayWithoutControlledAccessSeedsRC2() {
        RoadGraph graph = buildSingleEdgeGraph(
            Map.of("highway", "motorway", "oneway", "yes"));
        new AttributeSeeder().seed(graph);
        assertEquals(RoutingClass.RC2, graph.getEdges().get(0).getComputedRc());
    }

    @Test
    public void trunkWithDualCarriagewaySeedsRC2() {
        RoadGraph graph = buildSingleEdgeGraph(
            Map.of("highway", "trunk", "dual_carriageway", "yes", "oneway", "yes"));
        new AttributeSeeder().seed(graph);
        assertEquals(RoutingClass.RC2, graph.getEdges().get(0).getComputedRc());
    }

    @Test
    public void primarySeedsRC3() {
        RoadGraph graph = buildSingleEdgeGraph(
            Map.of("highway", "primary", "oneway", "yes"));
        new AttributeSeeder().seed(graph);
        assertEquals(RoutingClass.RC3, graph.getEdges().get(0).getComputedRc());
    }

    @Test
    public void secondarySeedsRC4() {
        RoadGraph graph = buildSingleEdgeGraph(
            Map.of("highway", "secondary", "oneway", "yes"));
        new AttributeSeeder().seed(graph);
        assertEquals(RoutingClass.RC4, graph.getEdges().get(0).getComputedRc());
    }

    @Test
    public void residentialSeedsRC5() {
        RoadGraph graph = buildSingleEdgeGraph(
            Map.of("highway", "residential", "oneway", "yes"));
        new AttributeSeeder().seed(graph);
        assertEquals(RoutingClass.RC5, graph.getEdges().get(0).getComputedRc());
    }

    @Test
    public void motorwayLinkSeedsRC4() {
        RoadGraph graph = buildSingleEdgeGraph(
            Map.of("highway", "motorway_link", "oneway", "yes"));
        new AttributeSeeder().seed(graph);
        assertEquals(RoutingClass.RC4, graph.getEdges().get(0).getComputedRc());
    }

    @Test
    public void primaryWithIntRefSeedsRC2() {
        RoadGraph graph = buildSingleEdgeGraph(
            Map.of("highway", "primary", "int_ref", "E25", "oneway", "yes"));
        new AttributeSeeder().seed(graph);
        assertEquals(RoutingClass.RC2, graph.getEdges().get(0).getComputedRc());
    }

    @Test
    public void unknownHighwayTypeSeedsRC5() {
        RoadGraph graph = buildSingleEdgeGraph(
            Map.of("highway", "bridleway", "oneway", "yes"));
        new AttributeSeeder().seed(graph);
        assertEquals(RoutingClass.RC5, graph.getEdges().get(0).getComputedRc());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.algorithm.AttributeSeederTest`
Expected: Compilation error — AttributeSeeder does not exist.

- [ ] **Step 3: Implement AttributeSeeder**

```java
package com.tomtom.routing.algorithm;

import com.tomtom.routing.model.RoadEdge;
import com.tomtom.routing.model.RoadGraph;
import com.tomtom.routing.model.RoutingClass;

public class AttributeSeeder {

    public void seed(RoadGraph graph) {
        for (RoadEdge edge : graph.getEdges()) {
            edge.setComputedRc(computeSeed(edge));
        }
    }

    /**
     * Seed ferries after all road edges have been seeded.
     * Ferry RC = max (lowest value) RC of roads connected at its endpoints.
     */
    public void seedFerries(RoadGraph graph) {
        for (RoadEdge edge : graph.getEdges()) {
            if (edge.hasAttribute("ferry") && edge.getAttribute("highway") == null) {
                RoutingClass best = RoutingClass.RC5;
                RoadNode fromNode = graph.getNode(edge.getFromNodeId());
                RoadNode toNode = graph.getNode(edge.getToNodeId());
                for (RoadEdge neighbor : fromNode.getOutgoingEdges()) {
                    if (neighbor != edge && neighbor.getComputedRc() != null
                        && neighbor.getComputedRc().value() < best.value()) {
                        best = neighbor.getComputedRc();
                    }
                }
                for (RoadEdge neighbor : toNode.getOutgoingEdges()) {
                    if (neighbor != edge && neighbor.getComputedRc() != null
                        && neighbor.getComputedRc().value() < best.value()) {
                        best = neighbor.getComputedRc();
                    }
                }
                edge.setComputedRc(best);
            }
        }
    }

    private RoutingClass computeSeed(RoadEdge edge) {
        String highway = edge.getAttribute("highway");
        if (highway == null) {
            return RoutingClass.RC5; // ferries resolved in seedFerries() after all roads seeded
        }

        return switch (highway) {
            case "motorway" -> hasControlledAccess(edge) ? RoutingClass.RC1 : RoutingClass.RC2;
            case "trunk" -> hasTrunkImportance(edge) ? RoutingClass.RC2 : RoutingClass.RC3;
            case "primary" -> computePrimarySeed(edge);
            case "secondary" -> hasDualCarriageway(edge) ? RoutingClass.RC3 : RoutingClass.RC4;
            case "tertiary" -> RoutingClass.RC4;
            case "motorway_link", "trunk_link", "primary_link" -> RoutingClass.RC4;
            default -> RoutingClass.RC5;
        };
    }

    private RoutingClass computePrimarySeed(RoadEdge edge) {
        if (edge.hasAttribute("int_ref")) return RoutingClass.RC2;
        if (hasDualCarriageway(edge)) return RoutingClass.RC3;
        return RoutingClass.RC3;
    }

    private boolean hasControlledAccess(RoadEdge edge) {
        return "yes".equals(edge.getAttribute("controlled_access"));
    }

    private boolean hasTrunkImportance(RoadEdge edge) {
        return hasControlledAccess(edge) || hasDualCarriageway(edge);
    }

    private boolean hasDualCarriageway(RoadEdge edge) {
        return "yes".equals(edge.getAttribute("dual_carriageway"));
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.algorithm.AttributeSeederTest`
Expected: All 9 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tomtom/routing/algorithm/AttributeSeeder.java src/test/java/com/tomtom/routing/algorithm/AttributeSeederTest.java
git commit -m "feat: add AttributeSeeder for Phase 2 attribute-based RC seeding"
```

---

### Task 7: Algorithm — Dijkstra

**Files:**
- Create: `src/main/java/com/tomtom/routing/algorithm/Dijkstra.java`
- Create: `src/test/java/com/tomtom/routing/algorithm/DijkstraTest.java`

Single-source shortest path on the directed graph, weighted by travel time. Returns distances and predecessor edges (needed for betweenness centrality backtracking and for bridging in connectivity enforcement).

- [ ] **Step 1: Write the test**

```java
package com.tomtom.routing.algorithm;

import com.tomtom.routing.model.*;
import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class DijkstraTest {

    private RoadGraph buildTriangleGraph() {
        // Triangle: 1->2 (weight 10), 2->3 (weight 20), 1->3 (weight 50)
        // Shortest 1->3 is via 2: cost 30
        RoadGraphBuilder builder = new RoadGraphBuilder();
        builder.addNode(1, 49.6, 6.1);
        builder.addNode(2, 49.6, 6.2);  // ~7.5km east
        builder.addNode(3, 49.7, 6.2);  // ~11km north of node 2
        builder.addWay(100, new long[]{1, 2}, Map.of("highway", "motorway", "oneway", "yes", "maxspeed", "130"));
        builder.addWay(200, new long[]{2, 3}, Map.of("highway", "primary", "oneway", "yes", "maxspeed", "50"));
        builder.addWay(300, new long[]{1, 3}, Map.of("highway", "residential", "oneway", "yes", "maxspeed", "30"));
        return builder.build();
    }

    @Test
    public void shortestPathFromSource() {
        RoadGraph graph = buildTriangleGraph();
        Dijkstra.Result result = Dijkstra.run(graph, 1);

        // Node 1 is source — distance 0
        assertEquals(0.0, result.distanceTo(1), 0.001);

        // Node 2 reachable directly
        assertTrue(result.distanceTo(2) > 0);

        // Node 3 reachable via 2 (faster) or directly (slower)
        assertTrue(result.distanceTo(3) > 0);
        assertTrue(result.isReachable(3));
    }

    @Test
    public void unreachableNodeHasInfiniteDistance() {
        // Build graph with disconnected node
        RoadGraphBuilder builder = new RoadGraphBuilder();
        builder.addNode(1, 49.6, 6.1);
        builder.addNode(2, 49.7, 6.2);
        builder.addNode(3, 49.8, 6.3);
        builder.addWay(100, new long[]{1, 2}, Map.of("highway", "primary", "oneway", "yes"));
        // Node 3 is disconnected
        RoadGraph graph = builder.build();

        Dijkstra.Result result = Dijkstra.run(graph, 1);
        assertFalse(result.isReachable(3));
    }

    @Test
    public void predecessorEdgesFormShortestPath() {
        RoadGraph graph = buildTriangleGraph();
        Dijkstra.Result result = Dijkstra.run(graph, 1);

        // The shortest path to node 3 should go through node 2
        // (motorway at 130 + primary at 50 is faster than residential at 30)
        RoadEdge predEdge = result.predecessorEdge(3);
        assertNotNull(predEdge);
        assertEquals(2, predEdge.getFromNodeId());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.algorithm.DijkstraTest`
Expected: Compilation error — Dijkstra does not exist.

- [ ] **Step 3: Implement Dijkstra**

```java
package com.tomtom.routing.algorithm;

import com.tomtom.routing.model.RoadEdge;
import com.tomtom.routing.model.RoadGraph;
import com.tomtom.routing.model.RoadNode;

import java.util.*;

public class Dijkstra {

    // Luxembourg speed defaults (km/h). In production, load per-country from config.
    static final Map<String, Double> DEFAULT_SPEEDS = Map.ofEntries(
        Map.entry("motorway", 130.0),
        Map.entry("trunk", 90.0),
        Map.entry("primary", 70.0),
        Map.entry("secondary", 50.0),
        Map.entry("tertiary", 50.0),
        Map.entry("motorway_link", 60.0),
        Map.entry("trunk_link", 60.0),
        Map.entry("primary_link", 60.0),
        Map.entry("secondary_link", 40.0),
        Map.entry("residential", 30.0),
        Map.entry("living_street", 20.0),
        Map.entry("service", 20.0),
        Map.entry("track", 20.0),
        Map.entry("unclassified", 40.0)
    );

    public static Result run(RoadGraph graph, long sourceNodeId) {
        Map<Long, Double> dist = new HashMap<>();
        Map<Long, RoadEdge> predEdge = new HashMap<>();
        PriorityQueue<long[]> pq = new PriorityQueue<>(Comparator.comparingDouble(a -> Double.longBitsToDouble(a[1])));

        dist.put(sourceNodeId, 0.0);
        pq.add(new long[]{sourceNodeId, Double.doubleToLongBits(0.0)});

        while (!pq.isEmpty()) {
            long[] current = pq.poll();
            long nodeId = current[0];
            double d = Double.longBitsToDouble(current[1]);

            if (d > dist.getOrDefault(nodeId, Double.MAX_VALUE)) continue;

            RoadNode node = graph.getNode(nodeId);
            if (node == null) continue;

            for (RoadEdge edge : node.getOutgoingEdges()) {
                double weight = edgeWeight(edge);
                double newDist = d + weight;
                if (newDist < dist.getOrDefault(edge.getToNodeId(), Double.MAX_VALUE)) {
                    dist.put(edge.getToNodeId(), newDist);
                    predEdge.put(edge.getToNodeId(), edge);
                    pq.add(new long[]{edge.getToNodeId(), Double.doubleToLongBits(newDist)});
                }
            }
        }

        return new Result(dist, predEdge);
    }

    static double edgeWeight(RoadEdge edge) {
        double speedKmh = estimateSpeed(edge);
        double speedMs = speedKmh / 3.6;
        if (speedMs <= 0) speedMs = 1.0;
        return edge.getLengthMeters() / speedMs; // travel time in seconds
    }

    private static double estimateSpeed(RoadEdge edge) {
        // Priority: free_flow speed > maxspeed > road type default
        String freeFlow = edge.getAttribute("speed:free_flow:forward");
        if (freeFlow != null) {
            try { return Double.parseDouble(freeFlow); } catch (NumberFormatException e) { /* fall through */ }
        }

        String maxspeed = edge.getAttribute("maxspeed");
        if (maxspeed != null) {
            try { return Double.parseDouble(maxspeed); } catch (NumberFormatException e) { /* fall through */ }
        }

        // Road type default speeds — per-country configurable; these are Luxembourg defaults
        // In production, load from a per-country config file
        String highway = edge.getAttribute("highway");
        if (highway == null) return 30;
        return DEFAULT_SPEEDS.getOrDefault(highway, 30.0);
    }

    public static class Result {
        private final Map<Long, Double> distances;
        private final Map<Long, RoadEdge> predecessors;

        Result(Map<Long, Double> distances, Map<Long, RoadEdge> predecessors) {
            this.distances = distances;
            this.predecessors = predecessors;
        }

        public double distanceTo(long nodeId) {
            return distances.getOrDefault(nodeId, Double.MAX_VALUE);
        }

        public boolean isReachable(long nodeId) {
            return distances.containsKey(nodeId);
        }

        public RoadEdge predecessorEdge(long nodeId) {
            return predecessors.get(nodeId);
        }

        public Set<Long> reachableNodes() {
            return distances.keySet();
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.algorithm.DijkstraTest`
Expected: All 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tomtom/routing/algorithm/Dijkstra.java src/test/java/com/tomtom/routing/algorithm/DijkstraTest.java
git commit -m "feat: add Dijkstra single-source shortest path algorithm"
```

---

### Task 8: Algorithm — CentralityComputer

**Files:**
- Create: `src/main/java/com/tomtom/routing/algorithm/CentralityComputer.java`
- Create: `src/test/java/com/tomtom/routing/algorithm/CentralityComputerTest.java`

Sampled edge betweenness centrality. For each sample source, runs Dijkstra and accumulates centrality on edges in shortest paths.

- [ ] **Step 1: Write the test**

```java
package com.tomtom.routing.algorithm;

import com.tomtom.routing.model.*;
import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class CentralityComputerTest {

    @Test
    public void bridgeEdgeHasHighestCentrality() {
        // Two clusters connected by a single bridge edge
        // Cluster A: nodes 1,2,3 fully connected
        // Cluster B: nodes 4,5,6 fully connected
        // Bridge: 3 -> 4
        RoadGraphBuilder builder = new RoadGraphBuilder();
        builder.addNode(1, 49.60, 6.10);
        builder.addNode(2, 49.61, 6.11);
        builder.addNode(3, 49.62, 6.12);
        builder.addNode(4, 49.63, 6.13);
        builder.addNode(5, 49.64, 6.14);
        builder.addNode(6, 49.65, 6.15);

        // Cluster A (all bidirectional)
        builder.addWay(10, new long[]{1, 2}, Map.of("highway", "primary"));
        builder.addWay(11, new long[]{2, 3}, Map.of("highway", "primary"));
        builder.addWay(12, new long[]{1, 3}, Map.of("highway", "primary"));

        // Bridge (oneway for simplicity)
        builder.addWay(20, new long[]{3, 4}, Map.of("highway", "secondary", "oneway", "yes"));

        // Cluster B (all bidirectional)
        builder.addWay(30, new long[]{4, 5}, Map.of("highway", "primary"));
        builder.addWay(31, new long[]{5, 6}, Map.of("highway", "primary"));
        builder.addWay(32, new long[]{4, 6}, Map.of("highway", "primary"));

        RoadGraph graph = builder.build();

        // Use all nodes as sources (full computation for small graph)
        Map<String, Double> centrality = CentralityComputer.compute(graph, graph.nodeCount());

        // Find the bridge edge (way 20, segment 0, from node 3 to 4)
        RoadEdge bridgeEdge = null;
        for (RoadEdge edge : graph.getEdges()) {
            if (edge.getFromNodeId() == 3 && edge.getToNodeId() == 4) {
                bridgeEdge = edge;
                break;
            }
        }
        assertNotNull(bridgeEdge);

        double bridgeCentrality = centrality.getOrDefault(bridgeEdge.getId(), 0.0);

        // Bridge should have the highest centrality (all cross-cluster paths use it)
        for (Map.Entry<String, Double> entry : centrality.entrySet()) {
            if (!entry.getKey().equals(bridgeEdge.getId())) {
                assertTrue("Bridge should have highest centrality",
                    bridgeCentrality >= entry.getValue());
            }
        }
    }

    @Test
    public void centralityValuesAreNonNegative() {
        RoadGraphBuilder builder = new RoadGraphBuilder();
        builder.addNode(1, 49.6, 6.1);
        builder.addNode(2, 49.7, 6.2);
        builder.addWay(100, new long[]{1, 2}, Map.of("highway", "primary"));

        RoadGraph graph = builder.build();
        Map<String, Double> centrality = CentralityComputer.compute(graph, graph.nodeCount());

        for (double value : centrality.values()) {
            assertTrue(value >= 0);
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.algorithm.CentralityComputerTest`
Expected: Compilation error — CentralityComputer does not exist.

- [ ] **Step 3: Implement CentralityComputer**

```java
package com.tomtom.routing.algorithm;

import com.tomtom.routing.model.RoadEdge;
import com.tomtom.routing.model.RoadGraph;
import com.tomtom.routing.model.RoadNode;

import java.util.*;

public class CentralityComputer {

    public static Map<String, Double> compute(RoadGraph graph, int sampleSize) {
        Map<String, Double> centrality = new HashMap<>();

        // Initialize all edges to 0
        for (RoadEdge edge : graph.getEdges()) {
            centrality.put(edge.getId(), 0.0);
        }

        // Select sample source nodes
        List<Long> nodeIds = new ArrayList<>(graph.getNodes().keySet());
        Collections.shuffle(nodeIds);
        int effectiveSample = Math.min(sampleSize, nodeIds.size());

        for (int i = 0; i < effectiveSample; i++) {
            long sourceId = nodeIds.get(i);
            Dijkstra.Result result = Dijkstra.run(graph, sourceId);

            // Backtrack from each reachable node to accumulate centrality
            for (long targetId : result.reachableNodes()) {
                if (targetId == sourceId) continue;

                long current = targetId;
                while (current != sourceId) {
                    RoadEdge predEdge = result.predecessorEdge(current);
                    if (predEdge == null) break;
                    centrality.merge(predEdge.getId(), 1.0, Double::sum);
                    current = predEdge.getFromNodeId();
                }
            }
        }

        return centrality;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.algorithm.CentralityComputerTest`
Expected: All 2 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tomtom/routing/algorithm/CentralityComputer.java src/test/java/com/tomtom/routing/algorithm/CentralityComputerTest.java
git commit -m "feat: add CentralityComputer for sampled edge betweenness centrality"
```

---

### Task 9: Algorithm — RcRefiner

**Files:**
- Create: `src/main/java/com/tomtom/routing/algorithm/RcRefiner.java`
- Create: `src/test/java/com/tomtom/routing/algorithm/RcRefinerTest.java`

Combines seed RC with centrality scores. Promotes high-centrality edges, demotes low-centrality edges, within configurable thresholds.

- [ ] **Step 1: Write the test**

```java
package com.tomtom.routing.algorithm;

import com.tomtom.routing.model.*;
import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class RcRefinerTest {

    @Test
    public void highCentralityEdgeGetsPromoted() {
        RoadGraphBuilder builder = new RoadGraphBuilder();
        builder.addNode(1, 49.6, 6.1);
        builder.addNode(2, 49.7, 6.2);
        builder.addNode(3, 49.8, 6.3);
        // Three oneway edges, all seeded RC4
        builder.addWay(100, new long[]{1, 2}, Map.of("highway", "secondary", "oneway", "yes"));
        builder.addWay(200, new long[]{2, 3}, Map.of("highway", "secondary", "oneway", "yes"));
        builder.addWay(300, new long[]{1, 3}, Map.of("highway", "secondary", "oneway", "yes"));

        RoadGraph graph = builder.build();
        new AttributeSeeder().seed(graph); // all RC4

        // Give edge 100 very high centrality, others low
        Map<String, Double> centrality = new HashMap<>();
        for (RoadEdge edge : graph.getEdges()) {
            centrality.put(edge.getId(), 1.0);
        }
        // Make edge from node 1 to node 2 have very high centrality
        for (RoadEdge edge : graph.getEdges()) {
            if (edge.getFromNodeId() == 1 && edge.getToNodeId() == 2) {
                centrality.put(edge.getId(), 1000.0);
            }
        }

        new RcRefiner(0.85, 0.15).refine(graph, centrality);

        // The high-centrality edge should be promoted to RC3
        for (RoadEdge edge : graph.getEdges()) {
            if (edge.getFromNodeId() == 1 && edge.getToNodeId() == 2) {
                assertEquals(RoutingClass.RC3, edge.getComputedRc());
            }
        }
    }

    @Test
    public void promotionClampedAtRC1() {
        RoadGraphBuilder builder = new RoadGraphBuilder();
        builder.addNode(1, 49.6, 6.1);
        builder.addNode(2, 49.7, 6.2);
        builder.addWay(100, new long[]{1, 2},
            Map.of("highway", "motorway", "controlled_access", "yes", "oneway", "yes"));

        RoadGraph graph = builder.build();
        new AttributeSeeder().seed(graph); // RC1

        Map<String, Double> centrality = new HashMap<>();
        for (RoadEdge edge : graph.getEdges()) {
            centrality.put(edge.getId(), 1000.0);
        }

        new RcRefiner(0.85, 0.15).refine(graph, centrality);

        // RC1 can't be promoted further
        assertEquals(RoutingClass.RC1, graph.getEdges().get(0).getComputedRc());
    }

    @Test
    public void refinementLimitedToOneLevel() {
        RoadGraphBuilder builder = new RoadGraphBuilder();
        builder.addNode(1, 49.6, 6.1);
        builder.addNode(2, 49.7, 6.2);
        builder.addNode(3, 49.8, 6.3);
        // Two RC5 edges, one with very high centrality
        builder.addWay(100, new long[]{1, 2}, Map.of("highway", "residential", "oneway", "yes"));
        builder.addWay(200, new long[]{2, 3}, Map.of("highway", "residential", "oneway", "yes"));

        RoadGraph graph = builder.build();
        new AttributeSeeder().seed(graph); // all RC5

        Map<String, Double> centrality = new HashMap<>();
        for (RoadEdge edge : graph.getEdges()) {
            if (edge.getFromNodeId() == 1) {
                centrality.put(edge.getId(), 10000.0);
            } else {
                centrality.put(edge.getId(), 1.0);
            }
        }

        new RcRefiner(0.85, 0.15).refine(graph, centrality);

        // Promoted by only 1 level: RC5 -> RC4 (not RC3)
        for (RoadEdge edge : graph.getEdges()) {
            if (edge.getFromNodeId() == 1) {
                assertEquals(RoutingClass.RC4, edge.getComputedRc());
            }
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.algorithm.RcRefinerTest`
Expected: Compilation error — RcRefiner does not exist.

- [ ] **Step 3: Implement RcRefiner**

```java
package com.tomtom.routing.algorithm;

import com.tomtom.routing.model.RoadEdge;
import com.tomtom.routing.model.RoadGraph;
import com.tomtom.routing.model.RoutingClass;

import java.util.*;

public class RcRefiner {

    private final double promotePercentile;
    private final double demotePercentile;

    public RcRefiner(double promotePercentile, double demotePercentile) {
        this.promotePercentile = promotePercentile;
        this.demotePercentile = demotePercentile;
    }

    public void refine(RoadGraph graph, Map<String, Double> centrality) {
        // Group edges by seed RC
        Map<RoutingClass, List<RoadEdge>> groups = new EnumMap<>(RoutingClass.class);
        for (RoadEdge edge : graph.getEdges()) {
            groups.computeIfAbsent(edge.getComputedRc(), k -> new ArrayList<>()).add(edge);
        }

        for (Map.Entry<RoutingClass, List<RoadEdge>> entry : groups.entrySet()) {
            List<RoadEdge> edgesInGroup = entry.getValue();
            if (edgesInGroup.size() < 2) continue;

            // Collect centrality values for this group
            double[] values = edgesInGroup.stream()
                .mapToDouble(e -> centrality.getOrDefault(e.getId(), 0.0))
                .sorted().toArray();

            double promoteThreshold = percentile(values, promotePercentile);
            double demoteThreshold = percentile(values, demotePercentile);

            for (RoadEdge edge : edgesInGroup) {
                double c = centrality.getOrDefault(edge.getId(), 0.0);
                if (c >= promoteThreshold) {
                    edge.setComputedRc(edge.getComputedRc().promote());
                } else if (c <= demoteThreshold) {
                    edge.setComputedRc(edge.getComputedRc().demote());
                }
            }
        }
    }

    private double percentile(double[] sortedValues, double p) {
        if (sortedValues.length == 0) return 0;
        int index = (int) Math.ceil(p * sortedValues.length) - 1;
        index = Math.max(0, Math.min(index, sortedValues.length - 1));
        return sortedValues[index];
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.algorithm.RcRefinerTest`
Expected: All 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tomtom/routing/algorithm/RcRefiner.java src/test/java/com/tomtom/routing/algorithm/RcRefinerTest.java
git commit -m "feat: add RcRefiner for centrality-based RC adjustment"
```

---

### Task 10: Algorithm — TarjanScc

**Files:**
- Create: `src/main/java/com/tomtom/routing/algorithm/TarjanScc.java`
- Create: `src/test/java/com/tomtom/routing/algorithm/TarjanSccTest.java`

Tarjan's algorithm for finding strongly connected components in a directed graph, operating on a filtered subgraph (edges matching an RC predicate).

- [ ] **Step 1: Write the test**

```java
package com.tomtom.routing.algorithm;

import com.tomtom.routing.model.*;
import org.junit.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.*;

public class TarjanSccTest {

    @Test
    public void singleSccForFullyConnectedGraph() {
        // Triangle: all bidirectional -> single SCC
        RoadGraphBuilder builder = new RoadGraphBuilder();
        builder.addNode(1, 49.6, 6.1);
        builder.addNode(2, 49.7, 6.2);
        builder.addNode(3, 49.8, 6.3);
        builder.addWay(10, new long[]{1, 2}, Map.of("highway", "primary"));
        builder.addWay(11, new long[]{2, 3}, Map.of("highway", "primary"));
        builder.addWay(12, new long[]{1, 3}, Map.of("highway", "primary"));

        RoadGraph graph = builder.build();
        Set<String> allEdgeIds = Set.copyOf(graph.getEdges().stream().map(RoadEdge::getId).toList());

        List<Set<Long>> sccs = TarjanScc.compute(graph, allEdgeIds);

        assertEquals(1, sccs.size());
        assertEquals(3, sccs.get(0).size());
    }

    @Test
    public void twoSccsForDisconnectedOnewayChains() {
        // Chain A: 1->2->3 (oneway, no back path = not strongly connected to chain B)
        // Chain B: 4->5->4 (loop = 1 SCC)
        RoadGraphBuilder builder = new RoadGraphBuilder();
        builder.addNode(1, 49.60, 6.10);
        builder.addNode(2, 49.65, 6.15);
        builder.addNode(3, 49.70, 6.20);
        builder.addNode(4, 49.75, 6.25);
        builder.addNode(5, 49.80, 6.30);
        builder.addWay(10, new long[]{1, 2}, Map.of("highway", "primary", "oneway", "yes"));
        builder.addWay(11, new long[]{2, 3}, Map.of("highway", "primary", "oneway", "yes"));
        builder.addWay(20, new long[]{4, 5}, Map.of("highway", "primary", "oneway", "yes"));
        builder.addWay(21, new long[]{5, 4}, Map.of("highway", "primary", "oneway", "yes"));

        RoadGraph graph = builder.build();
        Set<String> allEdgeIds = Set.copyOf(graph.getEdges().stream().map(RoadEdge::getId).toList());

        List<Set<Long>> sccs = TarjanScc.compute(graph, allEdgeIds);

        // Chain A has 3 single-node SCCs (no back edges), Chain B has 1 SCC of size 2
        // Total SCCs with size > 1: just the loop {4,5}
        long largeSccs = sccs.stream().filter(s -> s.size() > 1).count();
        assertEquals(1, largeSccs);
    }

    @Test
    public void computeReturnsLargestFirst() {
        RoadGraphBuilder builder = new RoadGraphBuilder();
        builder.addNode(1, 49.60, 6.10);
        builder.addNode(2, 49.65, 6.15);
        builder.addNode(3, 49.70, 6.20);
        builder.addNode(4, 49.75, 6.25);
        builder.addNode(5, 49.80, 6.30);
        // Big SCC: 1-2-3 bidirectional
        builder.addWay(10, new long[]{1, 2}, Map.of("highway", "primary"));
        builder.addWay(11, new long[]{2, 3}, Map.of("highway", "primary"));
        builder.addWay(12, new long[]{1, 3}, Map.of("highway", "primary"));
        // Small SCC: 4-5 bidirectional
        builder.addWay(20, new long[]{4, 5}, Map.of("highway", "primary"));

        RoadGraph graph = builder.build();
        Set<String> allEdgeIds = Set.copyOf(graph.getEdges().stream().map(RoadEdge::getId).toList());

        List<Set<Long>> sccs = TarjanScc.compute(graph, allEdgeIds);

        assertTrue(sccs.size() >= 2);
        assertTrue(sccs.get(0).size() >= sccs.get(1).size()); // Largest first
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.algorithm.TarjanSccTest`
Expected: Compilation error — TarjanScc does not exist.

- [ ] **Step 3: Implement TarjanScc**

```java
package com.tomtom.routing.algorithm;

import com.tomtom.routing.model.RoadEdge;
import com.tomtom.routing.model.RoadGraph;
import com.tomtom.routing.model.RoadNode;

import java.util.*;

public class TarjanScc {

    /**
     * Computes SCCs on the subgraph defined by the given edge IDs.
     * Returns SCCs sorted by size (largest first).
     */
    public static List<Set<Long>> compute(RoadGraph graph, Set<String> activeEdgeIds) {
        Map<Long, Integer> index = new HashMap<>();
        Map<Long, Integer> lowlink = new HashMap<>();
        Map<Long, Boolean> onStack = new HashMap<>();
        Deque<Long> stack = new ArrayDeque<>();
        List<Set<Long>> result = new ArrayList<>();
        int[] counter = {0};

        // Find all nodes that participate in active edges
        Set<Long> activeNodes = new HashSet<>();
        for (RoadEdge edge : graph.getEdges()) {
            if (activeEdgeIds.contains(edge.getId())) {
                activeNodes.add(edge.getFromNodeId());
                activeNodes.add(edge.getToNodeId());
            }
        }

        for (long nodeId : activeNodes) {
            if (!index.containsKey(nodeId)) {
                strongconnect(nodeId, graph, activeEdgeIds, index, lowlink, onStack, stack, result, counter);
            }
        }

        result.sort((a, b) -> Integer.compare(b.size(), a.size()));
        return result;
    }

    private static void strongconnect(long v, RoadGraph graph, Set<String> activeEdgeIds,
                                       Map<Long, Integer> index, Map<Long, Integer> lowlink,
                                       Map<Long, Boolean> onStack, Deque<Long> stack,
                                       List<Set<Long>> result, int[] counter) {
        // Iterative Tarjan to avoid stack overflow on large graphs
        Deque<long[]> callStack = new ArrayDeque<>();
        // Frame: [nodeId, edgeIndex]
        callStack.push(new long[]{v, 0});
        index.put(v, counter[0]);
        lowlink.put(v, counter[0]);
        counter[0]++;
        onStack.put(v, true);
        stack.push(v);

        while (!callStack.isEmpty()) {
            long[] frame = callStack.peek();
            long nodeId = frame[0];
            RoadNode node = graph.getNode(nodeId);

            List<RoadEdge> outEdges = (node != null) ? node.getOutgoingEdges() : List.of();
            // Filter to active edges
            List<RoadEdge> activeOut = outEdges.stream()
                .filter(e -> activeEdgeIds.contains(e.getId()))
                .toList();

            if (frame[1] < activeOut.size()) {
                RoadEdge edge = activeOut.get((int) frame[1]);
                frame[1]++;
                long w = edge.getToNodeId();

                if (!index.containsKey(w)) {
                    index.put(w, counter[0]);
                    lowlink.put(w, counter[0]);
                    counter[0]++;
                    onStack.put(w, true);
                    stack.push(w);
                    callStack.push(new long[]{w, 0});
                } else if (onStack.getOrDefault(w, false)) {
                    lowlink.put(nodeId, Math.min(lowlink.get(nodeId), index.get(w)));
                }
            } else {
                // All neighbors processed
                if (lowlink.get(nodeId).equals(index.get(nodeId))) {
                    Set<Long> scc = new HashSet<>();
                    long w;
                    do {
                        w = stack.pop();
                        onStack.put(w, false);
                        scc.add(w);
                    } while (w != nodeId);
                    result.add(scc);
                }

                callStack.pop();
                if (!callStack.isEmpty()) {
                    long parent = callStack.peek()[0];
                    lowlink.put(parent, Math.min(lowlink.get(parent), lowlink.get(nodeId)));
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.algorithm.TarjanSccTest`
Expected: All 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tomtom/routing/algorithm/TarjanScc.java src/test/java/com/tomtom/routing/algorithm/TarjanSccTest.java
git commit -m "feat: add TarjanScc for strongly connected component analysis"
```

---

### Task 11: Algorithm — ConnectivityEnforcer

**Files:**
- Create: `src/main/java/com/tomtom/routing/algorithm/ConnectivityEnforcer.java`
- Create: `src/test/java/com/tomtom/routing/algorithm/ConnectivityEnforcerTest.java`

Top-down enforcement with SCC bridging. For each RC level, computes SCCs, tries to bridge disconnected components by promoting shortest-path edges, and demotes only unreachable components.

- [ ] **Step 1: Write the test**

```java
package com.tomtom.routing.algorithm;

import com.tomtom.routing.model.*;
import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class ConnectivityEnforcerTest {

    @Test
    public void disconnectedRC1ComponentGetsBridged() {
        // Two RC1 clusters connected by an RC3 edge
        // Enforcement should promote the RC3 bridge to RC1
        RoadGraphBuilder builder = new RoadGraphBuilder();
        builder.addNode(1, 49.60, 6.10);
        builder.addNode(2, 49.61, 6.11);
        builder.addNode(3, 49.62, 6.12); // bridge start
        builder.addNode(4, 49.63, 6.13); // bridge end
        builder.addNode(5, 49.64, 6.14);
        builder.addNode(6, 49.65, 6.15);

        // Cluster A (RC1): bidirectional
        builder.addWay(10, new long[]{1, 2}, Map.of("highway", "motorway", "controlled_access", "yes"));
        builder.addWay(11, new long[]{2, 3}, Map.of("highway", "motorway", "controlled_access", "yes"));
        builder.addWay(12, new long[]{1, 3}, Map.of("highway", "motorway", "controlled_access", "yes"));

        // Bridge (RC3): bidirectional
        builder.addWay(20, new long[]{3, 4}, Map.of("highway", "primary"));

        // Cluster B (RC1): bidirectional
        builder.addWay(30, new long[]{4, 5}, Map.of("highway", "motorway", "controlled_access", "yes"));
        builder.addWay(31, new long[]{5, 6}, Map.of("highway", "motorway", "controlled_access", "yes"));
        builder.addWay(32, new long[]{4, 6}, Map.of("highway", "motorway", "controlled_access", "yes"));

        RoadGraph graph = builder.build();
        new AttributeSeeder().seed(graph);
        // Cluster A and B edges are RC1, bridge edges are RC3

        ConnectivityEnforcer enforcer = new ConnectivityEnforcer();
        ConnectivityEnforcer.Result result = enforcer.enforce(graph);

        // The bridge edges (from way 20) should be promoted to RC1
        for (RoadEdge edge : graph.getEdges()) {
            if (edge.getParentWayId() == 20) {
                assertEquals("Bridge should be promoted to RC1",
                    RoutingClass.RC1, edge.getComputedRc());
            }
        }
        assertTrue(result.promotions() > 0);
    }

    @Test
    public void unreachableComponentGetsDemoted() {
        // Two separate RC1 clusters with NO connecting path at all
        RoadGraphBuilder builder = new RoadGraphBuilder();
        builder.addNode(1, 49.60, 6.10);
        builder.addNode(2, 49.61, 6.11);
        builder.addNode(3, 49.80, 6.30);  // far away, no connection
        builder.addNode(4, 49.81, 6.31);

        builder.addWay(10, new long[]{1, 2}, Map.of("highway", "motorway", "controlled_access", "yes"));
        builder.addWay(20, new long[]{3, 4}, Map.of("highway", "motorway", "controlled_access", "yes"));

        RoadGraph graph = builder.build();
        new AttributeSeeder().seed(graph);

        ConnectivityEnforcer enforcer = new ConnectivityEnforcer();
        enforcer.enforce(graph);

        // Larger cluster stays RC1, smaller gets demoted to RC2
        int rc1Count = 0;
        int rc2Count = 0;
        for (RoadEdge edge : graph.getEdges()) {
            if (edge.getComputedRc() == RoutingClass.RC1) rc1Count++;
            if (edge.getComputedRc() == RoutingClass.RC2) rc2Count++;
        }
        assertTrue("Some edges should remain RC1", rc1Count > 0);
        assertTrue("Disconnected edges should be demoted", rc2Count > 0);
    }

    @Test
    public void enforcementIsTopDown() {
        // After RC1 enforcement, RC1+RC2 should also be enforced
        RoadGraphBuilder builder = new RoadGraphBuilder();
        builder.addNode(1, 49.60, 6.10);
        builder.addNode(2, 49.61, 6.11);
        builder.addWay(10, new long[]{1, 2},
            Map.of("highway", "motorway", "controlled_access", "yes"));

        RoadGraph graph = builder.build();
        new AttributeSeeder().seed(graph);

        ConnectivityEnforcer enforcer = new ConnectivityEnforcer();
        ConnectivityEnforcer.Result result = enforcer.enforce(graph);

        // With a single road, should have exactly 1 SCC at every level
        // (trivially satisfied)
        assertNotNull(result);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.algorithm.ConnectivityEnforcerTest`
Expected: Compilation error — ConnectivityEnforcer does not exist.

- [ ] **Step 3: Implement ConnectivityEnforcer**

```java
package com.tomtom.routing.algorithm;

import com.tomtom.routing.model.RoadEdge;
import com.tomtom.routing.model.RoadGraph;
import com.tomtom.routing.model.RoadNode;
import com.tomtom.routing.model.RoutingClass;

import java.util.*;
import java.util.stream.Collectors;

public class ConnectivityEnforcer {

    public record Result(int promotions, int demotions) {}

    public Result enforce(RoadGraph graph) {
        int totalPromotions = 0;
        int totalDemotions = 0;

        // Process top-down: RC1, then RC1+2, then RC1+2+3, then RC1+2+3+4
        for (int level = 1; level <= 4; level++) {
            int rcLevel = level;

            // Collect edge IDs in the RC <= level subgraph
            Set<String> subgraphEdgeIds = graph.getEdges().stream()
                .filter(e -> e.getComputedRc() != null && e.getComputedRc().value() <= rcLevel)
                .map(RoadEdge::getId)
                .collect(Collectors.toSet());

            if (subgraphEdgeIds.isEmpty()) continue;

            List<Set<Long>> sccs = TarjanScc.compute(graph, subgraphEdgeIds);
            if (sccs.size() <= 1) continue;

            Set<Long> largestScc = sccs.get(0);

            // Try to bridge smaller SCCs to the largest
            for (int i = 1; i < sccs.size(); i++) {
                Set<Long> smallScc = sccs.get(i);
                List<RoadEdge> bridgePath = findBridgePath(graph, smallScc, largestScc);

                if (bridgePath != null) {
                    // Promote bridge edges to current RC level
                    for (RoadEdge edge : bridgePath) {
                        if (edge.getComputedRc().value() > rcLevel) {
                            edge.setComputedRc(RoutingClass.fromValue(rcLevel));
                            totalPromotions++;
                        }
                    }
                    // Merge small SCC into largest for subsequent bridging attempts
                    largestScc.addAll(smallScc);
                } else {
                    // Can't bridge — demote edges at this level in the small SCC
                    for (RoadEdge edge : graph.getEdges()) {
                        if (smallScc.contains(edge.getFromNodeId())
                            && edge.getComputedRc() != null
                            && edge.getComputedRc().value() == rcLevel) {
                            edge.setComputedRc(edge.getComputedRc().demote());
                            totalDemotions++;
                        }
                    }
                }
            }
        }

        return new Result(totalPromotions, totalDemotions);
    }

    /**
     * Finds the shortest path in the FULL graph from any node in sourceScc
     * to any node in targetScc. Returns the edges along the path, or null
     * if no path exists.
     */
    private List<RoadEdge> findBridgePath(RoadGraph graph, Set<Long> sourceScc, Set<Long> targetScc) {
        // Run Dijkstra from each source SCC node until we hit a target SCC node
        // Use a multi-source approach: add all source nodes at distance 0
        Map<Long, Double> dist = new HashMap<>();
        Map<Long, RoadEdge> predEdge = new HashMap<>();
        PriorityQueue<long[]> pq = new PriorityQueue<>(
            Comparator.comparingDouble(a -> Double.longBitsToDouble(a[1])));

        for (long sourceId : sourceScc) {
            dist.put(sourceId, 0.0);
            pq.add(new long[]{sourceId, Double.doubleToLongBits(0.0)});
        }

        while (!pq.isEmpty()) {
            long[] current = pq.poll();
            long nodeId = current[0];
            double d = Double.longBitsToDouble(current[1]);

            if (d > dist.getOrDefault(nodeId, Double.MAX_VALUE)) continue;

            if (targetScc.contains(nodeId) && !sourceScc.contains(nodeId)) {
                // Found path to target — reconstruct
                return reconstructPath(predEdge, nodeId, sourceScc);
            }

            RoadNode node = graph.getNode(nodeId);
            if (node == null) continue;

            for (RoadEdge edge : node.getOutgoingEdges()) {
                double weight = Dijkstra.edgeWeight(edge);
                double newDist = d + weight;
                if (newDist < dist.getOrDefault(edge.getToNodeId(), Double.MAX_VALUE)) {
                    dist.put(edge.getToNodeId(), newDist);
                    predEdge.put(edge.getToNodeId(), edge);
                    pq.add(new long[]{edge.getToNodeId(), Double.doubleToLongBits(newDist)});
                }
            }
        }

        return null; // No path found
    }

    private List<RoadEdge> reconstructPath(Map<Long, RoadEdge> predEdge, long target, Set<Long> sourceNodes) {
        List<RoadEdge> path = new ArrayList<>();
        long current = target;
        while (!sourceNodes.contains(current)) {
            RoadEdge edge = predEdge.get(current);
            if (edge == null) break;
            path.add(edge);
            current = edge.getFromNodeId();
        }
        Collections.reverse(path);
        return path;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.algorithm.ConnectivityEnforcerTest`
Expected: All 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tomtom/routing/algorithm/ConnectivityEnforcer.java src/test/java/com/tomtom/routing/algorithm/ConnectivityEnforcerTest.java
git commit -m "feat: add ConnectivityEnforcer with SCC bridging strategy"
```

---

### Task 12: IO — PbfWriter

**Files:**
- Create: `src/main/java/com/tomtom/routing/io/PbfWriter.java`

Writes the output PBF containing only road geometries and computed RC tag. Aggregates per-segment RC back to per-way (highest RC among segments).

- [ ] **Step 1: Implement PbfWriter**

```java
package com.tomtom.routing.io;

import com.tomtom.routing.model.RoadEdge;
import com.tomtom.routing.model.RoadGraph;
import com.tomtom.routing.model.RoutingClass;
import de.topobyte.osm4j.core.model.iface.OsmTag;
import de.topobyte.osm4j.core.model.impl.Node;
import de.topobyte.osm4j.core.model.impl.Tag;
import de.topobyte.osm4j.core.model.impl.Way;
import de.topobyte.osm4j.pbf.seq.PbfWriter;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;

public class PbfWriter {

    public void write(RoadGraph graph, String outputPath) throws IOException {
        // Aggregate per-segment RC back to per-way
        Map<Long, RoutingClass> wayRc = aggregateWayRc(graph);

        // Collect all unique nodes needed for geometries
        Map<Long, double[]> nodeCoords = new HashMap<>();
        Map<Long, long[]> wayNodeRefs = new HashMap<>();

        // Group edges by parent way to reconstruct way geometries
        Map<Long, List<RoadEdge>> edgesByWay = new LinkedHashMap<>();
        for (RoadEdge edge : graph.getEdges()) {
            edgesByWay.computeIfAbsent(edge.getParentWayId(), k -> new ArrayList<>()).add(edge);
        }

        // Build node coords and way node refs from edge geometries
        long nodeIdCounter = 1;
        for (Map.Entry<Long, List<RoadEdge>> entry : edgesByWay.entrySet()) {
            long wayId = entry.getKey();
            if (!wayRc.containsKey(wayId)) continue;

            // Use first forward edge to get geometry
            RoadEdge representative = entry.getValue().get(0);
            List<double[]> geom = representative.getGeometry();

            long[] refs = new long[geom.size()];
            for (int i = 0; i < geom.size(); i++) {
                long nid = nodeIdCounter++;
                refs[i] = nid;
                nodeCoords.put(nid, geom.get(i));
            }
            wayNodeRefs.put(wayId, refs);
        }

        try (OutputStream out = new FileOutputStream(outputPath)) {
            de.topobyte.osm4j.core.access.OsmOutputStream osmOut =
                new de.topobyte.osm4j.pbf.seq.PbfWriter(out, true);

            // Write nodes
            for (Map.Entry<Long, double[]> entry : nodeCoords.entrySet()) {
                double[] coord = entry.getValue();
                osmOut.write(new Node(entry.getKey(), coord[1], coord[0])); // lat, lon
            }

            // Write ways with RC tag
            for (Map.Entry<Long, long[]> entry : wayNodeRefs.entrySet()) {
                long wayId = entry.getKey();
                RoutingClass rc = wayRc.get(wayId);
                if (rc == null) continue;

                List<Tag> tags = List.of(new Tag("routing_class", String.valueOf(rc.value())));
                long[] refs = entry.getValue();

                // osm4j Way constructor needs TLongArrayList
                Way way = new Way(wayId, de.topobyte.osm4j.core.util.IdUtil.toTLongArrayList(refs), tags);
                osmOut.write(way);
            }

            osmOut.complete();
        }
    }

    private Map<Long, RoutingClass> aggregateWayRc(RoadGraph graph) {
        Map<Long, RoutingClass> result = new HashMap<>();
        for (RoadEdge edge : graph.getEdges()) {
            if (edge.getComputedRc() == null) continue;
            result.merge(edge.getParentWayId(), edge.getComputedRc(),
                (existing, incoming) -> existing.value() < incoming.value() ? existing : incoming);
        }
        return result;
    }
}
```

**Note:** The `Way` constructor API may need adjustment during implementation based on the exact osm4j API. The key pattern is: write nodes first, then ways with `routing_class` tag and node references. If `IdUtil.toTLongArrayList` is not available, use `de.topobyte.adt.geo.TLongArrayList` or construct the `Way` differently. Adjust during implementation.

- [ ] **Step 2: Verify compilation**

Run: `mvn compile`
Expected: BUILD SUCCESS (or adjust API calls if needed).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/tomtom/routing/io/PbfWriter.java
git commit -m "feat: add PbfWriter to write output PBF with computed RC"
```

---

### Task 13: Comparison — RcComparator and ReportWriter

**Files:**
- Create: `src/main/java/com/tomtom/routing/comparison/RcComparator.java`
- Create: `src/main/java/com/tomtom/routing/comparison/ReportWriter.java`
- Create: `src/test/java/com/tomtom/routing/comparison/RcComparatorTest.java`

- [ ] **Step 1: Write the test**

```java
package com.tomtom.routing.comparison;

import com.tomtom.routing.model.*;
import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class RcComparatorTest {

    @Test
    public void perfectMatchReturns100Percent() {
        RoadGraphBuilder builder = new RoadGraphBuilder();
        builder.addNode(1, 49.6, 6.1);
        builder.addNode(2, 49.7, 6.2);
        builder.addWay(100, new long[]{1, 2},
            Map.of("highway", "motorway", "oneway", "yes", "routing_class", "1"));

        RoadGraph graph = builder.build();
        for (RoadEdge edge : graph.getEdges()) {
            edge.setComputedRc(RoutingClass.RC1);
        }

        RcComparator.Report report = RcComparator.compare(graph);
        assertEquals(100.0, report.overallMatchPercent(), 0.01);
    }

    @Test
    public void mismatchReportsCorrectly() {
        RoadGraphBuilder builder = new RoadGraphBuilder();
        builder.addNode(1, 49.6, 6.1);
        builder.addNode(2, 49.7, 6.2);
        builder.addWay(100, new long[]{1, 2},
            Map.of("highway", "motorway", "oneway", "yes", "routing_class", "1"));

        RoadGraph graph = builder.build();
        for (RoadEdge edge : graph.getEdges()) {
            edge.setComputedRc(RoutingClass.RC3);
        }

        RcComparator.Report report = RcComparator.compare(graph);
        assertEquals(0.0, report.overallMatchPercent(), 0.01);
    }

    @Test
    public void confusionMatrixCountsCorrectly() {
        RoadGraphBuilder builder = new RoadGraphBuilder();
        builder.addNode(1, 49.6, 6.1);
        builder.addNode(2, 49.7, 6.2);
        builder.addNode(3, 49.8, 6.3);
        builder.addWay(100, new long[]{1, 2},
            Map.of("highway", "motorway", "oneway", "yes", "routing_class", "1"));
        builder.addWay(200, new long[]{2, 3},
            Map.of("highway", "primary", "oneway", "yes", "routing_class", "3"));

        RoadGraph graph = builder.build();
        for (RoadEdge edge : graph.getEdges()) {
            if (edge.getParentWayId() == 100) edge.setComputedRc(RoutingClass.RC1);
            if (edge.getParentWayId() == 200) edge.setComputedRc(RoutingClass.RC4);
        }

        RcComparator.Report report = RcComparator.compare(graph);

        // confusion[computed][existing]: [1][1] = 1, [4][3] = 1
        assertEquals(1, report.confusionMatrix()[0][0]); // RC1 match
        assertEquals(1, report.confusionMatrix()[3][2]); // computed RC4, existing RC3
    }

    @Test
    public void edgesWithNoExistingRcAreSkipped() {
        RoadGraphBuilder builder = new RoadGraphBuilder();
        builder.addNode(1, 49.6, 6.1);
        builder.addNode(2, 49.7, 6.2);
        builder.addWay(100, new long[]{1, 2},
            Map.of("highway", "primary", "oneway", "yes"));

        RoadGraph graph = builder.build();
        for (RoadEdge edge : graph.getEdges()) {
            edge.setComputedRc(RoutingClass.RC3);
        }

        RcComparator.Report report = RcComparator.compare(graph);
        assertEquals(0, report.totalCompared());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.comparison.RcComparatorTest`
Expected: Compilation error — RcComparator does not exist.

- [ ] **Step 3: Implement RcComparator**

```java
package com.tomtom.routing.comparison;

import com.tomtom.routing.model.RoadEdge;
import com.tomtom.routing.model.RoadGraph;
import com.tomtom.routing.model.RoutingClass;

import java.util.*;

public class RcComparator {

    public record Report(
        double overallMatchPercent,
        int totalCompared,
        int totalMatched,
        int[][] confusionMatrix, // [computed-1][existing-1]
        Map<Integer, int[]> perLevelStats // rc -> [matched, total]
    ) {}

    public static Report compare(RoadGraph graph) {
        // Aggregate per-way: take highest (lowest value) computed RC and existing RC
        Map<Long, RoutingClass> computedByWay = new HashMap<>();
        Map<Long, RoutingClass> existingByWay = new HashMap<>();

        for (RoadEdge edge : graph.getEdges()) {
            if (edge.getComputedRc() != null) {
                computedByWay.merge(edge.getParentWayId(), edge.getComputedRc(),
                    (a, b) -> a.value() < b.value() ? a : b);
            }
            if (edge.getExistingRc() != null) {
                existingByWay.merge(edge.getParentWayId(), edge.getExistingRc(),
                    (a, b) -> a.value() < b.value() ? a : b);
            }
        }

        int[][] confusion = new int[5][5];
        Map<Integer, int[]> perLevel = new HashMap<>();
        for (int i = 1; i <= 5; i++) perLevel.put(i, new int[]{0, 0});

        int totalCompared = 0;
        int totalMatched = 0;

        for (long wayId : existingByWay.keySet()) {
            RoutingClass computed = computedByWay.get(wayId);
            RoutingClass existing = existingByWay.get(wayId);
            if (computed == null || existing == null) continue;

            totalCompared++;
            confusion[computed.value() - 1][existing.value() - 1]++;

            boolean match = computed == existing;
            if (match) totalMatched++;

            int[] stats = perLevel.get(existing.value());
            stats[1]++; // total
            if (match) stats[0]++; // matched
        }

        double matchPercent = totalCompared > 0 ? (100.0 * totalMatched / totalCompared) : 0;
        return new Report(matchPercent, totalCompared, totalMatched, confusion, perLevel);
    }
}
```

- [ ] **Step 4: Implement ReportWriter**

```java
package com.tomtom.routing.comparison;

import com.tomtom.routing.algorithm.ConnectivityEnforcer;
import com.tomtom.routing.model.RoadEdge;
import com.tomtom.routing.model.RoadGraph;
import com.tomtom.routing.model.RoadNode;
import com.tomtom.routing.model.RoutingClass;

import java.io.*;
import java.util.*;

public class ReportWriter {

    public static void writeConsoleReport(RcComparator.Report report,
                                          ConnectivityEnforcer.Result enforcementResult) {
        System.out.println("\n=== ROUTING CLASS COMPARISON REPORT ===\n");
        System.out.printf("Overall match: %.1f%% (%d / %d ways)%n",
            report.overallMatchPercent(), report.totalMatched(), report.totalCompared());

        System.out.println("\nPer-level match rates:");
        for (int rc = 1; rc <= 5; rc++) {
            int[] stats = report.perLevelStats().get(rc);
            double pct = stats[1] > 0 ? (100.0 * stats[0] / stats[1]) : 0;
            System.out.printf("  RC%d: %.1f%% (%d / %d)%n", rc, pct, stats[0], stats[1]);
        }

        System.out.println("\nConfusion matrix (rows=computed, cols=existing):");
        System.out.print("     ");
        for (int j = 1; j <= 5; j++) System.out.printf("  RC%d", j);
        System.out.println();
        for (int i = 0; i < 5; i++) {
            System.out.printf("RC%d  ", i + 1);
            for (int j = 0; j < 5; j++) {
                System.out.printf("%5d", report.confusionMatrix()[i][j]);
            }
            System.out.println();
        }

        if (enforcementResult != null) {
            System.out.printf("%nEnforcement: %d promotions, %d demotions%n",
                enforcementResult.promotions(), enforcementResult.demotions());
        }

        System.out.println("\nDead ends per RC level:");
        Map<Integer, Integer> deadEnds = countDeadEnds(graph);
        for (int rc = 1; rc <= 5; rc++) {
            System.out.printf("  RC%d: %d dead ends%n", rc, deadEnds.getOrDefault(rc, 0));
        }
    }

    /**
     * A dead end at RC level n is a node that has incoming RC<=n edges but no
     * outgoing RC<=n edges (or vice versa) — a vehicle enters but cannot leave.
     */
    private static Map<Integer, Integer> countDeadEnds(RoadGraph graph) {
        Map<Integer, Integer> result = new HashMap<>();
        for (int level = 1; level <= 5; level++) {
            int rcLevel = level;
            // Count nodes with incoming but no outgoing edges (or vice versa) in RC<=level subgraph
            Map<Long, int[]> nodeDegrees = new HashMap<>(); // [inDegree, outDegree]
            for (RoadEdge edge : graph.getEdges()) {
                if (edge.getComputedRc() != null && edge.getComputedRc().value() <= rcLevel) {
                    nodeDegrees.computeIfAbsent(edge.getFromNodeId(), k -> new int[2])[1]++;
                    nodeDegrees.computeIfAbsent(edge.getToNodeId(), k -> new int[2])[0]++;
                }
            }
            int deadEndCount = 0;
            for (int[] degrees : nodeDegrees.values()) {
                if ((degrees[0] > 0 && degrees[1] == 0) || (degrees[0] == 0 && degrees[1] > 0)) {
                    deadEndCount++;
                }
            }
            result.put(level, deadEndCount);
        }
        return result;
    }

    public static void writePerRoadDiffCsv(RoadGraph graph, String outputPath) throws IOException {
        // Aggregate per-way
        Map<Long, RoutingClass> computedByWay = new HashMap<>();
        Map<Long, RoutingClass> existingByWay = new HashMap<>();
        Map<Long, String> highwayByWay = new HashMap<>();
        Map<Long, Double> lengthByWay = new HashMap<>();

        for (RoadEdge edge : graph.getEdges()) {
            long wayId = edge.getParentWayId();
            if (edge.getComputedRc() != null) {
                computedByWay.merge(wayId, edge.getComputedRc(),
                    (a, b) -> a.value() < b.value() ? a : b);
            }
            if (edge.getExistingRc() != null) {
                existingByWay.merge(wayId, edge.getExistingRc(),
                    (a, b) -> a.value() < b.value() ? a : b);
            }
            highwayByWay.putIfAbsent(wayId, edge.getAttribute("highway"));
            lengthByWay.merge(wayId, edge.getLengthMeters(), Double::sum);
        }

        try (PrintWriter pw = new PrintWriter(new FileWriter(outputPath))) {
            pw.println("road_id,highway_type,geometry_length_m,computed_rc,existing_rc,match");
            for (long wayId : existingByWay.keySet()) {
                RoutingClass computed = computedByWay.get(wayId);
                RoutingClass existing = existingByWay.get(wayId);
                if (computed == null) continue;

                pw.printf("%d,%s,%.1f,%d,%d,%s%n",
                    wayId,
                    highwayByWay.getOrDefault(wayId, ""),
                    lengthByWay.getOrDefault(wayId, 0.0),
                    computed.value(),
                    existing.value(),
                    computed == existing ? "yes" : "no");
            }
        }
    }

    public static void writeAggregatedSummaryCsv(RoadGraph graph, String outputPath) throws IOException {
        // Aggregate per-way
        Map<Long, RoutingClass> computedByWay = new HashMap<>();
        Map<Long, RoutingClass> existingByWay = new HashMap<>();
        Map<Long, Double> lengthByWay = new HashMap<>();

        for (RoadEdge edge : graph.getEdges()) {
            long wayId = edge.getParentWayId();
            if (edge.getComputedRc() != null) {
                computedByWay.merge(wayId, edge.getComputedRc(),
                    (a, b) -> a.value() < b.value() ? a : b);
            }
            if (edge.getExistingRc() != null) {
                existingByWay.merge(wayId, edge.getExistingRc(),
                    (a, b) -> a.value() < b.value() ? a : b);
            }
            lengthByWay.merge(wayId, edge.getLengthMeters(), Double::sum);
        }

        // Group by (computed, existing) pair
        Map<String, int[]> segCounts = new TreeMap<>();
        Map<String, Double> totalLengths = new TreeMap<>();

        for (long wayId : existingByWay.keySet()) {
            RoutingClass computed = computedByWay.get(wayId);
            RoutingClass existing = existingByWay.get(wayId);
            if (computed == null) continue;

            String key = computed.value() + "," + existing.value();
            segCounts.computeIfAbsent(key, k -> new int[]{0})[0]++;
            totalLengths.merge(key, lengthByWay.getOrDefault(wayId, 0.0), Double::sum);
        }

        try (PrintWriter pw = new PrintWriter(new FileWriter(outputPath))) {
            pw.println("computed_rc,existing_rc,segment_count,total_length_km");
            for (String key : segCounts.keySet()) {
                pw.printf("%s,%d,%.2f%n",
                    key, segCounts.get(key)[0], totalLengths.get(key) / 1000.0);
            }
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.comparison.RcComparatorTest`
Expected: All 4 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/tomtom/routing/comparison/RcComparator.java src/main/java/com/tomtom/routing/comparison/ReportWriter.java src/test/java/com/tomtom/routing/comparison/RcComparatorTest.java
git commit -m "feat: add RcComparator and ReportWriter for comparison output"
```

---

### Task 14: BatchJob — Main Entry Point

**Files:**
- Create: `src/main/java/com/tomtom/routing/BatchJob.java`

Orchestrates the full pipeline: parse → seed → centrality → refine → enforce → write PBF → compare → report.

- [ ] **Step 1: Implement BatchJob**

```java
package com.tomtom.routing;

import com.tomtom.routing.algorithm.*;
import com.tomtom.routing.comparison.RcComparator;
import com.tomtom.routing.comparison.ReportWriter;
import com.tomtom.routing.io.PbfReader;
import com.tomtom.routing.io.PbfWriter;
import com.tomtom.routing.model.RoadGraph;

import java.io.IOException;

public class BatchJob {

    private static final int DEFAULT_CENTRALITY_SAMPLE_SIZE = 2000;
    private static final double DEFAULT_PROMOTE_PERCENTILE = 0.85;
    private static final double DEFAULT_DEMOTE_PERCENTILE = 0.15;

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: BatchJob <input.osm.pbf> [output.osm.pbf]");
            System.exit(1);
        }

        String inputPath = args[0];
        String baseName = inputPath.replaceAll("\\.osm\\.pbf$", "");
        String outputPbf = args.length > 1 ? args[1] : baseName + "_computed_rc.osm.pbf";
        String diffCsv = baseName + "_diff.csv";
        String summaryCsv = baseName + "_summary.csv";

        System.out.println("=== Phase 1: Parsing PBF ===");
        PbfReader reader = new PbfReader();
        RoadGraph graph = reader.read(inputPath);
        System.out.printf("Graph built: %d nodes, %d edges%n", graph.nodeCount(), graph.edgeCount());

        System.out.println("\n=== Phase 2: Seed & Refine ===");
        System.out.println("Seeding from attributes...");
        AttributeSeeder seeder = new AttributeSeeder();
        seeder.seed(graph);
        seeder.seedFerries(graph);

        System.out.printf("Computing centrality (sample size: %d)...%n", DEFAULT_CENTRALITY_SAMPLE_SIZE);
        var centrality = CentralityComputer.compute(graph, DEFAULT_CENTRALITY_SAMPLE_SIZE);
        System.out.println("Refining RC with centrality...");
        new RcRefiner(DEFAULT_PROMOTE_PERCENTILE, DEFAULT_DEMOTE_PERCENTILE).refine(graph, centrality);

        System.out.println("\n=== Phase 3: Connectivity Enforcement ===");
        ConnectivityEnforcer enforcer = new ConnectivityEnforcer();
        ConnectivityEnforcer.Result enforcementResult = enforcer.enforce(graph);
        System.out.printf("Enforcement done: %d promotions, %d demotions%n",
            enforcementResult.promotions(), enforcementResult.demotions());

        System.out.println("\n=== Writing Output ===");
        System.out.println("Writing output PBF: " + outputPbf);
        new PbfWriter().write(graph, outputPbf);

        System.out.println("\n=== Comparison ===");
        RcComparator.Report report = RcComparator.compare(graph);
        ReportWriter.writeConsoleReport(report, enforcementResult);

        System.out.println("\nWriting diff CSV: " + diffCsv);
        ReportWriter.writePerRoadDiffCsv(graph, diffCsv);

        System.out.println("Writing summary CSV: " + summaryCsv);
        ReportWriter.writeAggregatedSummaryCsv(graph, summaryCsv);

        System.out.println("\nDone.");
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/tomtom/routing/BatchJob.java
git commit -m "feat: add BatchJob main entry point orchestrating full pipeline"
```

---

### Task 15: Integration Test — Full Pipeline with Luxembourg PBF

**Files:**
- Create: `src/test/java/com/tomtom/routing/BatchJobIntegrationTest.java`

Runs the full pipeline on the Luxembourg PBF and asserts structural invariants.

- [ ] **Step 1: Write the integration test**

```java
package com.tomtom.routing;

import com.tomtom.routing.algorithm.*;
import com.tomtom.routing.comparison.RcComparator;
import com.tomtom.routing.io.PbfReader;
import com.tomtom.routing.model.RoadEdge;
import com.tomtom.routing.model.RoadGraph;
import com.tomtom.routing.model.RoutingClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class BatchJobIntegrationTest {

    private static final String PBF_PATH = "orbis_nexventura_26160_000_global_lux.osm.pbf";
    private static RoadGraph graph;
    private static ConnectivityEnforcer.Result enforcementResult;
    private static RcComparator.Report report;

    @BeforeClass
    public static void runPipeline() throws Exception {
        assumeTrue("PBF file must exist to run integration test",
            new File(PBF_PATH).exists());

        PbfReader reader = new PbfReader();
        graph = reader.read(PBF_PATH);

        AttributeSeeder seeder = new AttributeSeeder();
        seeder.seed(graph);
        seeder.seedFerries(graph);
        var centrality = CentralityComputer.compute(graph, 2000);
        new RcRefiner(0.85, 0.15).refine(graph, centrality);

        ConnectivityEnforcer enforcer = new ConnectivityEnforcer();
        enforcementResult = enforcer.enforce(graph);

        report = RcComparator.compare(graph);
    }

    @Test
    public void graphHasReasonableSize() {
        // Luxembourg should have >50k vehicle-routable road edges
        assertTrue("Expected >50k edges, got " + graph.edgeCount(),
            graph.edgeCount() > 50_000);
    }

    @Test
    public void allEdgesHaveComputedRc() {
        for (RoadEdge edge : graph.getEdges()) {
            assertNotNull("Edge " + edge.getId() + " has no computed RC",
                edge.getComputedRc());
        }
    }

    @Test
    public void rc1FormsSingleOrFewComponents() {
        Set<String> rc1EdgeIds = graph.getEdges().stream()
            .filter(e -> e.getComputedRc() == RoutingClass.RC1)
            .map(RoadEdge::getId)
            .collect(Collectors.toSet());

        if (rc1EdgeIds.isEmpty()) return; // No RC1 edges in this dataset

        var sccs = TarjanScc.compute(graph, rc1EdgeIds);
        // After enforcement, RC1 should have very few components (ideally 1)
        assertTrue("RC1 has too many components: " + sccs.size(), sccs.size() <= 5);
    }

    @Test
    public void comparisonMetricsAreReasonable() {
        // We should compare against a meaningful number of roads
        assertTrue("Expected >10k compared roads, got " + report.totalCompared(),
            report.totalCompared() > 10_000);

        // Overall match rate should be non-trivial (at least some agreement)
        assertTrue("Match rate suspiciously low: " + report.overallMatchPercent(),
            report.overallMatchPercent() > 10.0);
    }
}
```

- [ ] **Step 2: Run the integration test**

Run: `mvn test -pl . -Dtest=com.tomtom.routing.BatchJobIntegrationTest`
Expected: All 4 tests PASS (or SKIP if PBF not present). This will take a few minutes for the centrality computation.

- [ ] **Step 3: Run the full batch job to generate output**

Run: `mvn compile exec:java -Dexec.mainClass=com.tomtom.routing.BatchJob -Dexec.args="orbis_nexventura_26160_000_global_lux.osm.pbf"`

(If exec-maven-plugin is not configured, run directly: `java -cp target/classes:$(mvn dependency:build-classpath -q -DincludeScope=runtime -Dmdep.outputFile=/dev/stdout) com.tomtom.routing.BatchJob orbis_nexventura_26160_000_global_lux.osm.pbf`)

Expected: Console output showing pipeline phases, comparison report, and generated files:
- `orbis_nexventura_26160_000_global_lux_computed_rc.osm.pbf`
- `orbis_nexventura_26160_000_global_lux_diff.csv`
- `orbis_nexventura_26160_000_global_lux_summary.csv`

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/tomtom/routing/BatchJobIntegrationTest.java
git commit -m "test: add integration test running full pipeline on Luxembourg PBF"
```

---

### Task 16: Add exec-maven-plugin and final verification

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add exec-maven-plugin to pom.xml**

Add inside the `<plugins>` section:

```xml
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>exec-maven-plugin</artifactId>
    <version>3.1.0</version>
    <configuration>
        <mainClass>com.tomtom.routing.BatchJob</mainClass>
    </configuration>
</plugin>
```

- [ ] **Step 2: Run full test suite**

Run: `mvn test`
Expected: All unit tests PASS. Integration test PASSES (or SKIPS if PBF not present).

- [ ] **Step 3: Run the batch job via Maven**

Run: `mvn compile exec:java -Dexec.args="orbis_nexventura_26160_000_global_lux.osm.pbf"`
Expected: Full pipeline executes, output files generated.

- [ ] **Step 4: Add output files to gitignore**

Add to `.gitignore`:
```
# Output files
*_computed_rc.osm.pbf
*_diff.csv
*_summary.csv
```

- [ ] **Step 5: Commit**

```bash
git add pom.xml .gitignore
git commit -m "chore: add exec-maven-plugin and gitignore output files"
```
