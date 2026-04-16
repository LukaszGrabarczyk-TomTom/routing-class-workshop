package com.tomtom.routing.algorithm;

import com.tomtom.routing.model.RoadEdge;
import com.tomtom.routing.model.RoadGraph;
import com.tomtom.routing.model.RoadNode;

import java.util.*;

public class Dijkstra {

    // Luxembourg speed defaults (km/h). In production, load per-country from config.
    static final Map<String, Double> DEFAULT_SPEEDS = Map.ofEntries(
        Map.entry("motorway", 130.0),
        Map.entry("trunk", 90.0),
        Map.entry("primary", 70.0),
        Map.entry("secondary", 50.0),
        Map.entry("tertiary", 50.0),
        Map.entry("motorway_link", 60.0),
        Map.entry("trunk_link", 60.0),
        Map.entry("primary_link", 60.0),
        Map.entry("secondary_link", 40.0),
        Map.entry("residential", 30.0),
        Map.entry("living_street", 20.0),
        Map.entry("service", 20.0),
        Map.entry("track", 20.0),
        Map.entry("unclassified", 40.0)
    );

    public static Result run(RoadGraph graph, long sourceNodeId) {
        return run(graph, sourceNodeId, Integer.MAX_VALUE);
    }

    public static Result run(RoadGraph graph, long sourceNodeId, int maxVisitedNodes) {
        Map<Long, Double> dist = new HashMap<>();
        Map<Long, RoadEdge> predEdge = new HashMap<>();
        PriorityQueue<long[]> pq = new PriorityQueue<>(Comparator.comparingDouble(a -> Double.longBitsToDouble(a[1])));
        int visited = 0;

        dist.put(sourceNodeId, 0.0);
        pq.add(new long[]{sourceNodeId, Double.doubleToLongBits(0.0)});

        while (!pq.isEmpty() && visited < maxVisitedNodes) {
            long[] current = pq.poll();
            long nodeId = current[0];
            double d = Double.longBitsToDouble(current[1]);

            if (d > dist.getOrDefault(nodeId, Double.MAX_VALUE)) continue;
            visited++;

            RoadNode node = graph.getNode(nodeId);
            if (node == null) continue;

            for (RoadEdge edge : node.getOutgoingEdges()) {
                double weight = edgeWeight(edge);
                double newDist = d + weight;
                if (newDist < dist.getOrDefault(edge.getToNodeId(), Double.MAX_VALUE)) {
                    dist.put(edge.getToNodeId(), newDist);
                    predEdge.put(edge.getToNodeId(), edge);
                    pq.add(new long[]{edge.getToNodeId(), Double.doubleToLongBits(newDist)});
                }
            }
        }

        return new Result(dist, predEdge);
    }

    static double edgeWeight(RoadEdge edge) {
        double speedKmh = estimateSpeed(edge);
        double speedMs = speedKmh / 3.6;
        if (speedMs <= 0) speedMs = 1.0;
        return edge.getLengthMeters() / speedMs;
    }

    private static double estimateSpeed(RoadEdge edge) {
        String freeFlow = edge.getAttribute("speed:free_flow:forward");
        if (freeFlow != null) {
            try { return Double.parseDouble(freeFlow); } catch (NumberFormatException e) { /* fall through */ }
        }

        String maxspeed = edge.getAttribute("maxspeed");
        if (maxspeed != null) {
            try { return Double.parseDouble(maxspeed); } catch (NumberFormatException e) { /* fall through */ }
        }

        String highway = edge.getAttribute("highway");
        if (highway == null) return 30;
        return DEFAULT_SPEEDS.getOrDefault(highway, 30.0);
    }

    public static class Result {
        private final Map<Long, Double> distances;
        private final Map<Long, RoadEdge> predecessors;

        Result(Map<Long, Double> distances, Map<Long, RoadEdge> predecessors) {
            this.distances = distances;
            this.predecessors = predecessors;
        }

        public double distanceTo(long nodeId) {
            return distances.getOrDefault(nodeId, Double.MAX_VALUE);
        }

        public boolean isReachable(long nodeId) {
            return distances.containsKey(nodeId);
        }

        public RoadEdge predecessorEdge(long nodeId) {
            return predecessors.get(nodeId);
        }

        public Set<Long> reachableNodes() {
            return distances.keySet();
        }
    }
}
