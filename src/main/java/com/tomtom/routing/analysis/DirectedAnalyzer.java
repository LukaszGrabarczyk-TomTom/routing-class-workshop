package com.tomtom.routing.analysis;

import com.tomtom.routing.model.Edge;
import com.tomtom.routing.model.RcGraph;
import com.tomtom.routing.model.TraversalMode;

import java.util.*;

public class DirectedAnalyzer {

    public static ConnectivityResult analyze(RcGraph fullGraph, int rcLevel) {
        RcGraph sub = fullGraph.subgraph(rcLevel);
        Collection<Edge> edges = sub.edges();

        if (edges.isEmpty()) {
            return new ConnectivityResult(rcLevel, Set.of(), List.of());
        }

        // Build directed adjacency lists
        Map<String, List<String>> forward = new HashMap<>();
        Map<String, List<String>> reverse = new HashMap<>();
        Set<String> allNodes = new LinkedHashSet<>();

        for (Edge edge : edges) {
            allNodes.add(edge.sourceNodeId());
            allNodes.add(edge.targetNodeId());

            if (edge.traversalMode() == TraversalMode.FORWARD || edge.traversalMode() == TraversalMode.BOTH) {
                forward.computeIfAbsent(edge.sourceNodeId(), k -> new ArrayList<>()).add(edge.targetNodeId());
                reverse.computeIfAbsent(edge.targetNodeId(), k -> new ArrayList<>()).add(edge.sourceNodeId());
            }
            if (edge.traversalMode() == TraversalMode.REVERSE || edge.traversalMode() == TraversalMode.BOTH) {
                forward.computeIfAbsent(edge.targetNodeId(), k -> new ArrayList<>()).add(edge.sourceNodeId());
                reverse.computeIfAbsent(edge.sourceNodeId(), k -> new ArrayList<>()).add(edge.targetNodeId());
            }
        }

        // Kosaraju's step 1: compute finish order via DFS on forward graph
        Deque<String> finishOrder = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        for (String node : allNodes) {
            if (!visited.contains(node)) {
                dfsIterative(node, forward, visited, finishOrder);
            }
        }

        // Kosaraju's step 2: DFS on reverse graph in reverse finish order to find SCCs
        visited.clear();
        Map<String, Integer> nodeToScc = new HashMap<>();
        int sccIndex = 0;
        while (!finishOrder.isEmpty()) {
            String node = finishOrder.pop();
            if (!visited.contains(node)) {
                Set<String> component = new LinkedHashSet<>();
                dfsCollect(node, reverse, visited, component);
                for (String n : component) {
                    nodeToScc.put(n, sccIndex);
                }
                sccIndex++;
            }
        }

        // Map edges to their SCC (assign to source's SCC; cross-SCC edges also appear in target's SCC)
        Map<Integer, Set<String>> edgeComponents = new LinkedHashMap<>();
        for (Edge edge : edges) {
            int srcScc = nodeToScc.get(edge.sourceNodeId());
            int tgtScc = nodeToScc.get(edge.targetNodeId());
            edgeComponents.computeIfAbsent(srcScc, k -> new LinkedHashSet<>()).add(edge.id());
            if (srcScc != tgtScc) {
                edgeComponents.computeIfAbsent(tgtScc, k -> new LinkedHashSet<>()).add(edge.id());
            }
        }

        // Find largest component as main
        Set<String> mainComponent = null;
        int maxSize = -1;
        for (Set<String> comp : edgeComponents.values()) {
            if (comp.size() > maxSize) {
                maxSize = comp.size();
                mainComponent = comp;
            }
        }

        List<Set<String>> islands = new ArrayList<>();
        for (Set<String> comp : edgeComponents.values()) {
            if (comp != mainComponent) {
                islands.add(Collections.unmodifiableSet(comp));
            }
        }

        return new ConnectivityResult(rcLevel, Collections.unmodifiableSet(mainComponent), islands);
    }

    private static void dfsIterative(String start, Map<String, List<String>> adj,
                                     Set<String> visited, Deque<String> finishOrder) {
        Deque<String[]> stack = new ArrayDeque<>();
        stack.push(new String[]{start, "enter"});

        while (!stack.isEmpty()) {
            String[] frame = stack.pop();
            String node = frame[0];
            String phase = frame[1];

            if (phase.equals("exit")) {
                finishOrder.push(node);
                continue;
            }

            if (visited.contains(node)) continue;
            visited.add(node);

            stack.push(new String[]{node, "exit"});
            for (String neighbor : adj.getOrDefault(node, List.of())) {
                if (!visited.contains(neighbor)) {
                    stack.push(new String[]{neighbor, "enter"});
                }
            }
        }
    }

    private static void dfsCollect(String start, Map<String, List<String>> adj,
                                   Set<String> visited, Set<String> component) {
        Deque<String> stack = new ArrayDeque<>();
        stack.push(start);

        while (!stack.isEmpty()) {
            String node = stack.pop();
            if (visited.contains(node)) continue;
            visited.add(node);
            component.add(node);

            for (String neighbor : adj.getOrDefault(node, List.of())) {
                if (!visited.contains(neighbor)) {
                    stack.push(neighbor);
                }
            }
        }
    }
}
