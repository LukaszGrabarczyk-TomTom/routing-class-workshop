package com.tomtom.routing.algorithm;

import com.tomtom.routing.model.RoadEdge;
import com.tomtom.routing.model.RoadGraph;
import com.tomtom.routing.model.RoadNode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.stream.IntStream;

/**
 * Edge betweenness centrality using Brandes' algorithm with parallel source sampling.
 *
 * Instead of tracing individual shortest paths from every reachable node (O(N * pathLen) per source),
 * this uses a single reverse-topological-order pass to accumulate dependency scores in O(N + E) per source.
 * Sources are processed in parallel using a parallel stream.
 */
public class CentralityComputer {

    public static Map<String, Double> compute(RoadGraph graph, int sampleSize) {
        // Thread-safe accumulators for edge centrality
        Map<String, DoubleAdder> adders = new ConcurrentHashMap<>();
        for (RoadEdge edge : graph.getEdges()) {
            adders.put(edge.getId(), new DoubleAdder());
        }

        List<Long> nodeIds = new ArrayList<>(graph.getNodes().keySet());
        Collections.shuffle(nodeIds);
        int effectiveSample = Math.min(sampleSize, nodeIds.size());

        // Process sources in parallel — each Brandes SSSP is independent
        IntStream.range(0, effectiveSample).parallel().forEach(i -> {
            long source = nodeIds.get(i);
            brandesSssp(graph, source, adders);
        });

        // Collect results
        Map<String, Double> centrality = new HashMap<>(adders.size());
        adders.forEach((id, adder) -> centrality.put(id, adder.sum()));
        return centrality;
    }

    /**
     * Single-source Brandes pass: Dijkstra forward, then reverse accumulation.
     * Accumulates edge betweenness into the shared adders map.
     */
    private static void brandesSssp(RoadGraph graph, long source, Map<String, DoubleAdder> adders) {
        // --- Forward Dijkstra ---
        Map<Long, Double> dist = new HashMap<>();
        // For each node: list of predecessor edges on shortest paths
        Map<Long, List<RoadEdge>> predEdges = new HashMap<>();
        // sigma[v] = number of shortest paths from source to v
        Map<Long, Long> sigma = new HashMap<>();
        // Stack of nodes in order of non-decreasing distance (for reverse pass)
        Deque<Long> stack = new ArrayDeque<>();

        PriorityQueue<long[]> pq = new PriorityQueue<>(Comparator.comparingDouble(a -> Double.longBitsToDouble(a[1])));

        dist.put(source, 0.0);
        sigma.put(source, 1L);
        pq.add(new long[]{source, Double.doubleToLongBits(0.0)});

        while (!pq.isEmpty()) {
            long[] current = pq.poll();
            long nodeId = current[0];
            double d = Double.longBitsToDouble(current[1]);

            if (d > dist.getOrDefault(nodeId, Double.MAX_VALUE)) continue;

            stack.push(nodeId);

            RoadNode node = graph.getNode(nodeId);
            if (node == null) continue;

            for (RoadEdge edge : node.getOutgoingEdges()) {
                double weight = Dijkstra.edgeWeight(edge);
                double newDist = d + weight;
                long toNode = edge.getToNodeId();

                Double oldDist = dist.get(toNode);
                if (oldDist == null || newDist < oldDist - 1e-12) {
                    // Found a strictly shorter path
                    dist.put(toNode, newDist);
                    sigma.put(toNode, sigma.getOrDefault(nodeId, 1L));
                    predEdges.put(toNode, new ArrayList<>(List.of(edge)));
                    pq.add(new long[]{toNode, Double.doubleToLongBits(newDist)});
                } else if (Math.abs(newDist - oldDist) < 1e-12) {
                    // Found an equally short path
                    sigma.merge(toNode, sigma.getOrDefault(nodeId, 1L), Long::sum);
                    predEdges.computeIfAbsent(toNode, k -> new ArrayList<>()).add(edge);
                }
            }
        }

        // --- Reverse accumulation (Brandes) ---
        // delta[v] = dependency of source on v
        Map<Long, Double> delta = new HashMap<>();

        while (!stack.isEmpty()) {
            long w = stack.pop();
            List<RoadEdge> preds = predEdges.get(w);
            if (preds == null) continue;

            double sigmaW = sigma.getOrDefault(w, 1L);
            double deltaW = delta.getOrDefault(w, 0.0);
            double coeff = (1.0 + deltaW) / sigmaW;

            for (RoadEdge predEdge : preds) {
                long v = predEdge.getFromNodeId();
                double sigmaV = sigma.getOrDefault(v, 1L);
                double contribution = sigmaV * coeff;

                delta.merge(v, contribution, Double::sum);

                // Accumulate edge betweenness
                DoubleAdder adder = adders.get(predEdge.getId());
                if (adder != null) {
                    adder.add(contribution);
                }
            }
        }
    }
}
