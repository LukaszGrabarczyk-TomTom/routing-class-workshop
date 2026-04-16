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
