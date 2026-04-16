package com.tomtom.routing.model;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class RoadGraphBuilderTest {

    @Test
    public void buildSimpleTwoWayRoad() {
        RoadGraphBuilder builder = new RoadGraphBuilder();
        builder.addNode(1, 49.6, 6.1);
        builder.addNode(2, 49.7, 6.2);
        builder.addWay(100, new long[]{1, 2}, Map.of("highway", "primary"));

        RoadGraph graph = builder.build();

        assertEquals(2, graph.edgeCount());
        assertEquals(2, graph.nodeCount());

        RoadNode node1 = graph.getNode(1);
        RoadNode node2 = graph.getNode(2);
        assertEquals(1, node1.getOutgoingEdges().size());
        assertEquals(1, node2.getOutgoingEdges().size());

        RoadEdge forward = node1.getOutgoingEdges().get(0);
        assertEquals(1, forward.getFromNodeId());
        assertEquals(2, forward.getToNodeId());
        assertEquals("primary", forward.getAttribute("highway"));

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
        RoadGraphBuilder builder = new RoadGraphBuilder();
        builder.addNode(1, 49.60, 6.10);
        builder.addNode(2, 49.65, 6.15);
        builder.addNode(3, 49.70, 6.20);
        builder.addNode(4, 49.60, 6.20);
        builder.addNode(5, 49.70, 6.10);
        builder.addWay(100, new long[]{1, 2, 3}, Map.of("highway", "primary", "oneway", "yes"));
        builder.addWay(200, new long[]{4, 2, 5}, Map.of("highway", "secondary", "oneway", "yes"));

        RoadGraph graph = builder.build();

        assertEquals(4, graph.edgeCount());

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
