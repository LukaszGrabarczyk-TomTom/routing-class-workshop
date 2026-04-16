package com.tomtom.routing.algorithm;

import com.tomtom.routing.model.RoadEdge;
import com.tomtom.routing.model.RoadGraph;
import com.tomtom.routing.model.RoadNode;

import java.util.*;

public class TarjanScc {

    /**
     * Computes SCCs on the subgraph defined by the given edge IDs.
     * Returns SCCs sorted by size (largest first).
     */
    public static List<Set<Long>> compute(RoadGraph graph, Set<String> activeEdgeIds) {
        Map<Long, Integer> index = new HashMap<>();
        Map<Long, Integer> lowlink = new HashMap<>();
        Map<Long, Boolean> onStack = new HashMap<>();
        Deque<Long> stack = new ArrayDeque<>();
        List<Set<Long>> result = new ArrayList<>();
        int[] counter = {0};

        // Find all nodes that participate in active edges
        Set<Long> activeNodes = new HashSet<>();
        for (RoadEdge edge : graph.getEdges()) {
            if (activeEdgeIds.contains(edge.getId())) {
                activeNodes.add(edge.getFromNodeId());
                activeNodes.add(edge.getToNodeId());
            }
        }

        for (long nodeId : activeNodes) {
            if (!index.containsKey(nodeId)) {
                strongconnect(nodeId, graph, activeEdgeIds, index, lowlink, onStack, stack, result, counter);
            }
        }

        result.sort((a, b) -> Integer.compare(b.size(), a.size()));
        return result;
    }

    private static void strongconnect(long v, RoadGraph graph, Set<String> activeEdgeIds,
                                       Map<Long, Integer> index, Map<Long, Integer> lowlink,
                                       Map<Long, Boolean> onStack, Deque<Long> stack,
                                       List<Set<Long>> result, int[] counter) {
        // Iterative Tarjan using an explicit call stack
        // Each frame: [nodeId, neighborIndex]
        Deque<long[]> callStack = new ArrayDeque<>();
        callStack.push(new long[]{v, 0});
        index.put(v, counter[0]);
        lowlink.put(v, counter[0]);
        counter[0]++;
        onStack.put(v, true);
        stack.push(v);

        while (!callStack.isEmpty()) {
            long[] frame = callStack.peek();
            long nodeId = frame[0];
            RoadNode node = graph.getNode(nodeId);

            List<RoadEdge> activeOut = (node != null) ? node.getOutgoingEdges().stream()
                .filter(e -> activeEdgeIds.contains(e.getId()))
                .toList() : List.of();

            if (frame[1] < activeOut.size()) {
                RoadEdge edge = activeOut.get((int) frame[1]);
                frame[1]++;
                long w = edge.getToNodeId();

                if (!index.containsKey(w)) {
                    index.put(w, counter[0]);
                    lowlink.put(w, counter[0]);
                    counter[0]++;
                    onStack.put(w, true);
                    stack.push(w);
                    callStack.push(new long[]{w, 0});
                } else if (onStack.getOrDefault(w, false)) {
                    lowlink.put(nodeId, Math.min(lowlink.get(nodeId), index.get(w)));
                }
            } else {
                if (lowlink.get(nodeId).equals(index.get(nodeId))) {
                    Set<Long> scc = new HashSet<>();
                    long w;
                    do {
                        w = stack.pop();
                        onStack.put(w, false);
                        scc.add(w);
                    } while (w != nodeId);
                    result.add(scc);
                }

                callStack.pop();
                if (!callStack.isEmpty()) {
                    long parent = callStack.peek()[0];
                    lowlink.put(parent, Math.min(lowlink.get(parent), lowlink.get(nodeId)));
                }
            }
        }
    }
}
