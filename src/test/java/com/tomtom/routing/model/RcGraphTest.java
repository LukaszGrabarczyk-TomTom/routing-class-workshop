package com.tomtom.routing.model;

import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class RcGraphTest {

    @Test
    public void emptyGraphHasNoNodesOrEdges() {
        RcGraph graph = new RcGraph();
        assertTrue(graph.nodes().isEmpty());
        assertTrue(graph.edges().isEmpty());
    }

    @Test
    public void addNodeAndEdge() {
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("n1"));
        graph.addNode(new Node("n2"));
        graph.addEdge(new Edge("e1", "n1", "n2", TraversalMode.BOTH, 1));

        assertEquals(2, graph.nodes().size());
        assertEquals(1, graph.edges().size());
    }

    @Test
    public void subgraphFiltersEdgesByRcLevel() {
        RcGraph graph = buildSampleGraph();

        RcGraph rc1Only = graph.subgraph(1);
        Set<String> rc1Edges = rc1Only.edges().stream().map(Edge::id).collect(Collectors.toSet());
        assertEquals(Set.of("e1"), rc1Edges);

        RcGraph rc1and2 = graph.subgraph(2);
        Set<String> rc12Edges = rc1and2.edges().stream().map(Edge::id).collect(Collectors.toSet());
        assertEquals(Set.of("e1", "e2"), rc12Edges);

        RcGraph rc123 = graph.subgraph(3);
        assertEquals(3, rc123.edges().size());
    }

    @Test
    public void subgraphIncludesOnlyTouchedNodes() {
        RcGraph graph = buildSampleGraph();

        RcGraph rc1Only = graph.subgraph(1);
        Set<String> nodeIds = rc1Only.nodes().stream().map(Node::id).collect(Collectors.toSet());
        assertEquals(Set.of("n1", "n2"), nodeIds);
    }

    @Test
    public void edgesWithoutRcAreExcludedFromAllSubgraphs() {
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("n1"));
        graph.addNode(new Node("n2"));
        graph.addEdge(new Edge("e1", "n1", "n2", TraversalMode.BOTH));

        for (int level = 1; level <= 5; level++) {
            assertTrue(graph.subgraph(level).edges().isEmpty());
        }
    }

    @Test
    public void neighborsReturnsAdjacentEdges() {
        RcGraph graph = buildSampleGraph();
        List<Edge> neighbors = graph.edgesFrom("n2");
        Set<String> edgeIds = neighbors.stream().map(Edge::id).collect(Collectors.toSet());
        // n2 is an endpoint of e1 (n1->n2) and e2 (n2->n3); e3 (n3->n4) does not touch n2
        assertEquals(Set.of("e1", "e2"), edgeIds);
    }

    private RcGraph buildSampleGraph() {
        // n1 --RC1-- n2 --RC2-- n3 --RC3-- n4
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("n1"));
        graph.addNode(new Node("n2"));
        graph.addNode(new Node("n3"));
        graph.addNode(new Node("n4"));
        graph.addEdge(new Edge("e1", "n1", "n2", TraversalMode.BOTH, 1));
        graph.addEdge(new Edge("e2", "n2", "n3", TraversalMode.BOTH, 2));
        graph.addEdge(new Edge("e3", "n3", "n4", TraversalMode.BOTH, 3));
        return graph;
    }
}
