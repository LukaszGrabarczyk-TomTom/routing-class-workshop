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
        builder.addWay(10, new long[]{1, 2}, Map.of("highway", "primary"));
        builder.addWay(11, new long[]{2, 3}, Map.of("highway", "primary"));
        builder.addWay(12, new long[]{1, 3}, Map.of("highway", "primary"));
        builder.addWay(20, new long[]{4, 5}, Map.of("highway", "primary"));

        RoadGraph graph = builder.build();
        Set<String> allEdgeIds = Set.copyOf(graph.getEdges().stream().map(RoadEdge::getId).toList());

        List<Set<Long>> sccs = TarjanScc.compute(graph, allEdgeIds);

        assertTrue(sccs.size() >= 2);
        assertTrue(sccs.get(0).size() >= sccs.get(1).size());
    }
}
