package com.tomtom.routing.algorithm;

import com.tomtom.routing.model.RoadEdge;
import com.tomtom.routing.model.RoadGraph;
import com.tomtom.routing.model.RoadNode;
import com.tomtom.routing.model.RoutingClass;

import java.util.*;
import java.util.stream.Collectors;

public class ConnectivityEnforcer {

    public record Result(int promotions, int demotions) {}

    public Result enforce(RoadGraph graph) {
        int totalPromotions = 0;
        int totalDemotions = 0;
        // Track edges that were demoted so they are not cascaded down further levels
        Set<String> demotedEdgeIds = new HashSet<>();

        for (int level = 1; level <= 4; level++) {
            int rcLevel = level;

            Set<String> subgraphEdgeIds = graph.getEdges().stream()
                .filter(e -> e.getComputedRc() != null
                    && e.getComputedRc().value() <= rcLevel
                    && !demotedEdgeIds.contains(e.getId()))
                .map(RoadEdge::getId)
                .collect(Collectors.toSet());

            if (subgraphEdgeIds.isEmpty()) continue;

            List<Set<Long>> sccs = TarjanScc.compute(graph, subgraphEdgeIds);
            if (sccs.size() <= 1) continue;

            Set<Long> largestScc = sccs.get(0);

            for (int i = 1; i < sccs.size(); i++) {
                Set<Long> smallScc = sccs.get(i);
                List<RoadEdge> bridgePath = findBridgePath(graph, smallScc, largestScc);

                if (bridgePath != null) {
                    for (RoadEdge pathEdge : bridgePath) {
                        if (pathEdge.getComputedRc().value() > rcLevel) {
                            pathEdge.setComputedRc(RoutingClass.fromValue(rcLevel));
                            totalPromotions++;
                        }
                    }
                    // Also promote sibling edges (reverse direction of the same way segment)
                    promoteSiblings(graph, bridgePath, rcLevel);
                    largestScc.addAll(smallScc);
                } else {
                    for (RoadEdge edge : graph.getEdges()) {
                        if (smallScc.contains(edge.getFromNodeId())
                            && edge.getComputedRc() != null
                            && edge.getComputedRc().value() == rcLevel
                            && !demotedEdgeIds.contains(edge.getId())) {
                            edge.setComputedRc(edge.getComputedRc().demote());
                            demotedEdgeIds.add(edge.getId());
                            totalDemotions++;
                        }
                    }
                }
            }
        }

        return new Result(totalPromotions, totalDemotions);
    }

    /**
     * For each edge in the bridge path, find the reverse-direction edge of the same way
     * segment (same parentWayId, same endpoint nodes) and promote it too.
     */
    private void promoteSiblings(RoadGraph graph, List<RoadEdge> bridgePath, int rcLevel) {
        for (RoadEdge pathEdge : bridgePath) {
            long siblingFrom = pathEdge.getToNodeId();
            long siblingTo = pathEdge.getFromNodeId();
            long wayId = pathEdge.getParentWayId();
            RoadNode fromNode = graph.getNode(siblingFrom);
            if (fromNode == null) continue;
            for (RoadEdge candidate : fromNode.getOutgoingEdges()) {
                if (candidate.getParentWayId() == wayId
                    && candidate.getToNodeId() == siblingTo
                    && candidate.getComputedRc() != null
                    && candidate.getComputedRc().value() > rcLevel) {
                    candidate.setComputedRc(RoutingClass.fromValue(rcLevel));
                }
            }
        }
    }

    private List<RoadEdge> findBridgePath(RoadGraph graph, Set<Long> sourceScc, Set<Long> targetScc) {
        Map<Long, Double> dist = new HashMap<>();
        Map<Long, RoadEdge> predEdge = new HashMap<>();
        PriorityQueue<long[]> pq = new PriorityQueue<>(
            Comparator.comparingDouble(a -> Double.longBitsToDouble(a[1])));

        for (long sourceId : sourceScc) {
            dist.put(sourceId, 0.0);
            pq.add(new long[]{sourceId, Double.doubleToLongBits(0.0)});
        }

        while (!pq.isEmpty()) {
            long[] current = pq.poll();
            long nodeId = current[0];
            double d = Double.longBitsToDouble(current[1]);

            if (d > dist.getOrDefault(nodeId, Double.MAX_VALUE)) continue;

            if (targetScc.contains(nodeId) && !sourceScc.contains(nodeId)) {
                return reconstructPath(predEdge, nodeId, sourceScc);
            }

            RoadNode node = graph.getNode(nodeId);
            if (node == null) continue;

            for (RoadEdge edge : node.getOutgoingEdges()) {
                double weight = Dijkstra.edgeWeight(edge);
                double newDist = d + weight;
                if (newDist < dist.getOrDefault(edge.getToNodeId(), Double.MAX_VALUE)) {
                    dist.put(edge.getToNodeId(), newDist);
                    predEdge.put(edge.getToNodeId(), edge);
                    pq.add(new long[]{edge.getToNodeId(), Double.doubleToLongBits(newDist)});
                }
            }
        }

        return null;
    }

    private List<RoadEdge> reconstructPath(Map<Long, RoadEdge> predEdge, long target, Set<Long> sourceNodes) {
        List<RoadEdge> path = new ArrayList<>();
        long current = target;
        while (!sourceNodes.contains(current)) {
            RoadEdge edge = predEdge.get(current);
            if (edge == null) break;
            path.add(edge);
            current = edge.getFromNodeId();
        }
        Collections.reverse(path);
        return path;
    }
}
