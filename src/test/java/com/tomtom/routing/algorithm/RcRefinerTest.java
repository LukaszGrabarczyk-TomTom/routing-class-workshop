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
        builder.addWay(100, new long[]{1, 2}, Map.of("highway", "secondary", "oneway", "yes"));
        builder.addWay(200, new long[]{2, 3}, Map.of("highway", "secondary", "oneway", "yes"));
        builder.addWay(300, new long[]{1, 3}, Map.of("highway", "secondary", "oneway", "yes"));

        RoadGraph graph = builder.build();
        new AttributeSeeder().seed(graph); // all RC4

        Map<String, Double> centrality = new HashMap<>();
        for (RoadEdge edge : graph.getEdges()) {
            centrality.put(edge.getId(), 1.0);
        }
        for (RoadEdge edge : graph.getEdges()) {
            if (edge.getFromNodeId() == 1 && edge.getToNodeId() == 2) {
                centrality.put(edge.getId(), 1000.0);
            }
        }

        new RcRefiner(0.85, 0.15).refine(graph, centrality);

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
        assertEquals(RoutingClass.RC1, graph.getEdges().get(0).getComputedRc());
    }

    @Test
    public void refinementLimitedToOneLevel() {
        RoadGraphBuilder builder = new RoadGraphBuilder();
        builder.addNode(1, 49.6, 6.1);
        builder.addNode(2, 49.7, 6.2);
        builder.addNode(3, 49.8, 6.3);
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

        for (RoadEdge edge : graph.getEdges()) {
            if (edge.getFromNodeId() == 1) {
                assertEquals(RoutingClass.RC4, edge.getComputedRc());
            }
        }
    }
}
