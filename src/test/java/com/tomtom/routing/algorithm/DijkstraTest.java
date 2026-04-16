package com.tomtom.routing.algorithm;

import com.tomtom.routing.model.*;
import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class DijkstraTest {

    private RoadGraph buildTriangleGraph() {
        RoadGraphBuilder builder = new RoadGraphBuilder();
        builder.addNode(1, 49.6, 6.1);
        builder.addNode(2, 49.6, 6.2);
        builder.addNode(3, 49.7, 6.2);
        builder.addWay(100, new long[]{1, 2}, Map.of("highway", "motorway", "oneway", "yes", "maxspeed", "130"));
        builder.addWay(200, new long[]{2, 3}, Map.of("highway", "primary", "oneway", "yes", "maxspeed", "50"));
        builder.addWay(300, new long[]{1, 3}, Map.of("highway", "residential", "oneway", "yes", "maxspeed", "30"));
        return builder.build();
    }

    @Test
    public void shortestPathFromSource() {
        RoadGraph graph = buildTriangleGraph();
        Dijkstra.Result result = Dijkstra.run(graph, 1);

        assertEquals(0.0, result.distanceTo(1), 0.001);
        assertTrue(result.distanceTo(2) > 0);
        assertTrue(result.distanceTo(3) > 0);
        assertTrue(result.isReachable(3));
    }

    @Test
    public void unreachableNodeHasInfiniteDistance() {
        RoadGraphBuilder builder = new RoadGraphBuilder();
        builder.addNode(1, 49.6, 6.1);
        builder.addNode(2, 49.7, 6.2);
        builder.addNode(3, 49.8, 6.3);
        builder.addWay(100, new long[]{1, 2}, Map.of("highway", "primary", "oneway", "yes"));
        RoadGraph graph = builder.build();

        Dijkstra.Result result = Dijkstra.run(graph, 1);
        assertFalse(result.isReachable(3));
    }

    @Test
    public void predecessorEdgesFormShortestPath() {
        RoadGraph graph = buildTriangleGraph();
        Dijkstra.Result result = Dijkstra.run(graph, 1);

        RoadEdge predEdge = result.predecessorEdge(3);
        assertNotNull(predEdge);
        assertEquals(2, predEdge.getFromNodeId());
    }
}
