package com.tomtom.routing.analysis;

import com.tomtom.routing.model.Edge;
import com.tomtom.routing.model.RcGraph;

import java.util.*;

public class UndirectedAnalyzer {

    public static ConnectivityResult analyze(RcGraph fullGraph, int rcLevel) {
        RcGraph sub = fullGraph.subgraph(rcLevel);
        Collection<Edge> edges = sub.edges();

        if (edges.isEmpty()) {
            return new ConnectivityResult(rcLevel, Set.of(), List.of());
        }

        // Union-Find on node IDs
        Map<String, String> parent = new HashMap<>();
        Map<String, Integer> rank = new HashMap<>();

        for (Edge edge : edges) {
            parent.putIfAbsent(edge.sourceNodeId(), edge.sourceNodeId());
            parent.putIfAbsent(edge.targetNodeId(), edge.targetNodeId());
            rank.putIfAbsent(edge.sourceNodeId(), 0);
            rank.putIfAbsent(edge.targetNodeId(), 0);
            union(parent, rank, edge.sourceNodeId(), edge.targetNodeId());
        }

        // Group edges by their component root
        Map<String, Set<String>> componentEdges = new LinkedHashMap<>();
        for (Edge edge : edges) {
            String root = find(parent, edge.sourceNodeId());
            componentEdges.computeIfAbsent(root, k -> new LinkedHashSet<>()).add(edge.id());
        }

        // Find the largest component
        Set<String> mainComponent = null;
        int maxSize = -1;
        for (Set<String> component : componentEdges.values()) {
            if (component.size() > maxSize) {
                maxSize = component.size();
                mainComponent = component;
            }
        }

        List<Set<String>> islands = new ArrayList<>();
        for (Set<String> component : componentEdges.values()) {
            if (component != mainComponent) {
                islands.add(Collections.unmodifiableSet(component));
            }
        }

        return new ConnectivityResult(rcLevel, Collections.unmodifiableSet(mainComponent), islands);
    }

    private static String find(Map<String, String> parent, String x) {
        while (!parent.get(x).equals(x)) {
            parent.put(x, parent.get(parent.get(x))); // path compression
            x = parent.get(x);
        }
        return x;
    }

    private static void union(Map<String, String> parent, Map<String, Integer> rank, String a, String b) {
        String rootA = find(parent, a);
        String rootB = find(parent, b);
        if (rootA.equals(rootB)) return;

        int rankA = rank.get(rootA);
        int rankB = rank.get(rootB);
        if (rankA < rankB) {
            parent.put(rootA, rootB);
        } else if (rankA > rankB) {
            parent.put(rootB, rootA);
        } else {
            parent.put(rootB, rootA);
            rank.put(rootA, rankA + 1);
        }
    }
}
