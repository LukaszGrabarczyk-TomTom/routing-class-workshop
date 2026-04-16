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
        // A --RC1-- B --RC5-- C --RC1-- D, maxRcJump=2 rejects jump of 4
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

        assertEquals(0, report.totalUpgrades());
        assertEquals(1, report.totalDowngrades());
    }

    @Test
    public void exceptionSkipsIsland() {
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
        // RC1: C-D island → downgraded to RC2
        // RC2: D-E and C-D(now RC2) should be connected
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

        assertEquals(1, report.totalDowngrades());
        assertEquals(0, report.totalUpgrades());
    }

    @Test
    public void rc5IslandReportedAsUnresolvable() {
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

        assertEquals(0, report.totalUpgrades());
        assertEquals(1, report.totalDowngrades());
    }

    @Test
    public void bridgeSearchRespectsMaxPromotions() {
        // Same graph as maxHops test but maxBridgeHops=10, maxPromotions=2
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

        assertEquals(0, report.totalUpgrades());
        assertEquals(1, report.totalDowngrades());
    }
}
