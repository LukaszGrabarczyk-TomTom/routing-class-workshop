package com.tomtom.routing.algorithm;

import com.tomtom.routing.model.*;
import org.junit.Test;
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
