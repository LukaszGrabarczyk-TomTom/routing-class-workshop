package com.tomtom.routing.model;

import java.util.*;
import java.util.stream.Collectors;

public class RcGraph {

    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, Edge> edges = new LinkedHashMap<>();
    private final Map<String, List<Edge>> adjacency = new HashMap<>();

    public void addNode(Node node) {
        nodes.put(node.id(), node);
        adjacency.putIfAbsent(node.id(), new ArrayList<>());
    }

    public void addEdge(Edge edge) {
        edges.put(edge.id(), edge);
        adjacency.computeIfAbsent(edge.sourceNodeId(), k -> new ArrayList<>()).add(edge);
        adjacency.computeIfAbsent(edge.targetNodeId(), k -> new ArrayList<>()).add(edge);
    }

    public Collection<Node> nodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    public Collection<Edge> edges() {
        return Collections.unmodifiableCollection(edges.values());
    }

    public Edge edge(String id) {
        return edges.get(id);
    }

    public List<Edge> edgesFrom(String nodeId) {
        return Collections.unmodifiableList(adjacency.getOrDefault(nodeId, List.of()));
    }

    public RcGraph subgraph(int maxRcLevel) {
        RcGraph sub = new RcGraph();
        for (Edge edge : edges.values()) {
            if (edge.routingClass().isPresent() && edge.routingClass().getAsInt() <= maxRcLevel) {
                sub.addNode(new Node(edge.sourceNodeId()));
                sub.addNode(new Node(edge.targetNodeId()));
                sub.addEdge(edge);
            }
        }
        return sub;
    }

    public RcGraph subgraphExact(int rcLevel) {
        RcGraph sub = new RcGraph();
        for (Edge edge : edges.values()) {
            if (edge.routingClass().isPresent() && edge.routingClass().getAsInt() == rcLevel) {
                sub.addNode(new Node(edge.sourceNodeId()));
                sub.addNode(new Node(edge.targetNodeId()));
                sub.addEdge(edge);
            }
        }
        return sub;
    }
}
