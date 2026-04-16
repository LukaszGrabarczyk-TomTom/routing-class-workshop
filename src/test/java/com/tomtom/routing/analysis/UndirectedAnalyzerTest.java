package com.tomtom.routing.analysis;

import com.tomtom.routing.model.*;
import org.junit.Test;

import static org.junit.Assert.*;

public class UndirectedAnalyzerTest {

    @Test
    public void singleConnectedComponent() {
        // A --RC1-- B --RC1-- C (all connected)
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("A"));
        graph.addNode(new Node("B"));
        graph.addNode(new Node("C"));
        graph.addEdge(new Edge("e1", "A", "B", TraversalMode.BOTH, 1));
        graph.addEdge(new Edge("e2", "B", "C", TraversalMode.BOTH, 1));

        ConnectivityResult result = UndirectedAnalyzer.analyze(graph, 1);

        assertTrue(result.isConnected());
        assertEquals(1, result.totalComponents());
        assertEquals(2, result.mainComponent().size());
    }

    @Test
    public void twoDisconnectedIslands() {
        // A --RC1-- B    C --RC1-- D (two separate components)
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("A"));
        graph.addNode(new Node("B"));
        graph.addNode(new Node("C"));
        graph.addNode(new Node("D"));
        graph.addEdge(new Edge("e1", "A", "B", TraversalMode.BOTH, 1));
        graph.addEdge(new Edge("e2", "C", "D", TraversalMode.BOTH, 1));

        ConnectivityResult result = UndirectedAnalyzer.analyze(graph, 1);

        assertFalse(result.isConnected());
        assertEquals(2, result.totalComponents());
        assertEquals(1, result.mainComponent().size());
        assertEquals(1, result.islands().size());
        assertEquals(1, result.islands().get(0).size());
    }

    @Test
    public void largestComponentBecomesMain() {
        // A --RC1-- B --RC1-- C    D --RC1-- E (2-edge vs 1-edge)
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("A"));
        graph.addNode(new Node("B"));
        graph.addNode(new Node("C"));
        graph.addNode(new Node("D"));
        graph.addNode(new Node("E"));
        graph.addEdge(new Edge("e1", "A", "B", TraversalMode.BOTH, 1));
        graph.addEdge(new Edge("e2", "B", "C", TraversalMode.BOTH, 1));
        graph.addEdge(new Edge("e3", "D", "E", TraversalMode.BOTH, 1));

        ConnectivityResult result = UndirectedAnalyzer.analyze(graph, 1);

        assertEquals(2, result.mainComponent().size()); // e1, e2
        assertEquals(1, result.islands().size());
        assertEquals(1, result.islands().get(0).size()); // e3
    }

    @Test
    public void subgraphFilteringRespectsRcLevel() {
        // A --RC1-- B --RC3-- C (at level 1, only e1 exists)
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("A"));
        graph.addNode(new Node("B"));
        graph.addNode(new Node("C"));
        graph.addEdge(new Edge("e1", "A", "B", TraversalMode.BOTH, 1));
        graph.addEdge(new Edge("e2", "B", "C", TraversalMode.BOTH, 3));

        ConnectivityResult rc1Result = UndirectedAnalyzer.analyze(graph, 1);
        assertEquals(1, rc1Result.totalComponents());

        ConnectivityResult rc3Result = UndirectedAnalyzer.analyze(graph, 3);
        assertEquals(1, rc3Result.totalComponents());
        assertEquals(2, rc3Result.mainComponent().size());
    }

    @Test
    public void emptyGraphProducesNoComponents() {
        RcGraph graph = new RcGraph();
        ConnectivityResult result = UndirectedAnalyzer.analyze(graph, 1);

        assertTrue(result.isConnected());
        assertTrue(result.mainComponent().isEmpty());
        assertTrue(result.islands().isEmpty());
    }

    @Test
    public void onewayEdgesTreatedAsBidirectional() {
        // A --RC1(forward)--> B --RC1(forward)--> C
        // In undirected analysis, still one component
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("A"));
        graph.addNode(new Node("B"));
        graph.addNode(new Node("C"));
        graph.addEdge(new Edge("e1", "A", "B", TraversalMode.FORWARD, 1));
        graph.addEdge(new Edge("e2", "B", "C", TraversalMode.FORWARD, 1));

        ConnectivityResult result = UndirectedAnalyzer.analyze(graph, 1);
        assertTrue(result.isConnected());
    }
}
