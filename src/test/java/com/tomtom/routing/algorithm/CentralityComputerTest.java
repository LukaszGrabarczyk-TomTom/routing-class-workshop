package com.tomtom.routing.algorithm;

import com.tomtom.routing.model.*;
import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class CentralityComputerTest {

    @Test
    public void bridgeEdgeHasHighestCentrality() {
        // Two clusters connected by a single bridge edge
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

        // Find the bridge edge (from node 3 to 4)
        RoadEdge bridgeEdge = null;
        for (RoadEdge edge : graph.getEdges()) {
            if (edge.getFromNodeId() == 3 && edge.getToNodeId() == 4) {
                bridgeEdge = edge;
                break;
            }
        }
        assertNotNull(bridgeEdge);

        double bridgeCentrality = centrality.getOrDefault(bridgeEdge.getId(), 0.0);

        // Bridge should have the highest centrality
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
