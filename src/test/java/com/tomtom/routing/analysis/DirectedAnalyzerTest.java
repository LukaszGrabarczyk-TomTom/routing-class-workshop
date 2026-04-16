package com.tomtom.routing.analysis;

import com.tomtom.routing.model.*;
import org.junit.Test;

import static org.junit.Assert.*;

public class DirectedAnalyzerTest {

    @Test
    public void bidirectionalEdgesFormSingleScc() {
        // A <--RC1--> B <--RC1--> C (strongly connected)
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("A"));
        graph.addNode(new Node("B"));
        graph.addNode(new Node("C"));
        graph.addEdge(new Edge("e1", "A", "B", TraversalMode.BOTH, 1));
        graph.addEdge(new Edge("e2", "B", "C", TraversalMode.BOTH, 1));

        ConnectivityResult result = DirectedAnalyzer.analyze(graph, 1);

        assertTrue(result.isConnected());
        assertEquals(2, result.mainComponent().size());
    }

    @Test
    public void onewayChainBreaksStrongConnectivity() {
        // A --RC1--> B --RC1--> C (not strongly connected: can't go C→A)
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("A"));
        graph.addNode(new Node("B"));
        graph.addNode(new Node("C"));
        graph.addEdge(new Edge("e1", "A", "B", TraversalMode.FORWARD, 1));
        graph.addEdge(new Edge("e2", "B", "C", TraversalMode.FORWARD, 1));

        ConnectivityResult result = DirectedAnalyzer.analyze(graph, 1);

        assertFalse(result.isConnected());
    }

    @Test
    public void onewayLoopIsStronglyConnected() {
        // A --RC1--> B --RC1--> C --RC1--> A (cycle = one SCC)
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("A"));
        graph.addNode(new Node("B"));
        graph.addNode(new Node("C"));
        graph.addEdge(new Edge("e1", "A", "B", TraversalMode.FORWARD, 1));
        graph.addEdge(new Edge("e2", "B", "C", TraversalMode.FORWARD, 1));
        graph.addEdge(new Edge("e3", "C", "A", TraversalMode.FORWARD, 1));

        ConnectivityResult result = DirectedAnalyzer.analyze(graph, 1);

        assertTrue(result.isConnected());
        assertEquals(3, result.mainComponent().size());
    }

    @Test
    public void reverseTraversalModeRespected() {
        // Edge source=A, target=B, mode=REVERSE means traversal B→A only
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("A"));
        graph.addNode(new Node("B"));
        graph.addNode(new Node("C"));
        graph.addEdge(new Edge("e1", "A", "B", TraversalMode.REVERSE, 1)); // B→A
        graph.addEdge(new Edge("e2", "B", "C", TraversalMode.FORWARD, 1)); // B→C

        ConnectivityResult result = DirectedAnalyzer.analyze(graph, 1);

        // A←B→C: three separate SCCs (no cycles)
        assertFalse(result.isConnected());
    }

    @Test
    public void emptyGraphIsConnected() {
        RcGraph graph = new RcGraph();
        ConnectivityResult result = DirectedAnalyzer.analyze(graph, 1);

        assertTrue(result.isConnected());
    }
}
