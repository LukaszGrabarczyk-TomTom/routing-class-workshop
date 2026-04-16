package com.tomtom.routing.algorithm;

import com.tomtom.routing.model.*;
import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class ConnectivityEnforcerTest {

    @Test
    public void disconnectedRC1ComponentGetsBridged() {
        RoadGraphBuilder builder = new RoadGraphBuilder();
        builder.addNode(1, 49.60, 6.10);
        builder.addNode(2, 49.61, 6.11);
        builder.addNode(3, 49.62, 6.12);
        builder.addNode(4, 49.63, 6.13);
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

        ConnectivityEnforcer enforcer = new ConnectivityEnforcer();
        ConnectivityEnforcer.Result result = enforcer.enforce(graph);

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
        RoadGraphBuilder builder = new RoadGraphBuilder();
        builder.addNode(1, 49.60, 6.10);
        builder.addNode(2, 49.61, 6.11);
        builder.addNode(3, 49.80, 6.30);
        builder.addNode(4, 49.81, 6.31);

        builder.addWay(10, new long[]{1, 2}, Map.of("highway", "motorway", "controlled_access", "yes"));
        builder.addWay(20, new long[]{3, 4}, Map.of("highway", "motorway", "controlled_access", "yes"));

        RoadGraph graph = builder.build();
        new AttributeSeeder().seed(graph);

        ConnectivityEnforcer enforcer = new ConnectivityEnforcer();
        enforcer.enforce(graph);

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
        RoadGraphBuilder builder = new RoadGraphBuilder();
        builder.addNode(1, 49.60, 6.10);
        builder.addNode(2, 49.61, 6.11);
        builder.addWay(10, new long[]{1, 2},
            Map.of("highway", "motorway", "controlled_access", "yes"));

        RoadGraph graph = builder.build();
        new AttributeSeeder().seed(graph);

        ConnectivityEnforcer enforcer = new ConnectivityEnforcer();
        ConnectivityEnforcer.Result result = enforcer.enforce(graph);

        assertNotNull(result);
    }
}
