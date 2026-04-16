package com.tomtom.routing.comparison;

import com.tomtom.routing.model.RoadEdge;
import com.tomtom.routing.model.RoadGraph;
import com.tomtom.routing.model.RoutingClass;

import java.util.*;

public class RcComparator {

    public record Report(
        double overallMatchPercent,
        int totalCompared,
        int totalMatched,
        int[][] confusionMatrix,
        Map<Integer, int[]> perLevelStats
    ) {}

    public static Report compare(RoadGraph graph) {
        Map<Long, RoutingClass> computedByWay = new HashMap<>();
        Map<Long, RoutingClass> existingByWay = new HashMap<>();

        for (RoadEdge edge : graph.getEdges()) {
            if (edge.getComputedRc() != null) {
                computedByWay.merge(edge.getParentWayId(), edge.getComputedRc(),
                    (a, b) -> a.value() < b.value() ? a : b);
            }
            if (edge.getExistingRc() != null) {
                existingByWay.merge(edge.getParentWayId(), edge.getExistingRc(),
                    (a, b) -> a.value() < b.value() ? a : b);
            }
        }

        int[][] confusion = new int[5][5];
        Map<Integer, int[]> perLevel = new HashMap<>();
        for (int i = 1; i <= 5; i++) perLevel.put(i, new int[]{0, 0});

        int totalCompared = 0;
        int totalMatched = 0;

        for (long wayId : existingByWay.keySet()) {
            RoutingClass computed = computedByWay.get(wayId);
            RoutingClass existing = existingByWay.get(wayId);
            if (computed == null || existing == null) continue;

            totalCompared++;
            confusion[computed.value() - 1][existing.value() - 1]++;

            boolean match = computed == existing;
            if (match) totalMatched++;

            int[] stats = perLevel.get(existing.value());
            stats[1]++;
            if (match) stats[0]++;
        }

        double matchPercent = totalCompared > 0 ? (100.0 * totalMatched / totalCompared) : 0;
        return new Report(matchPercent, totalCompared, totalMatched, confusion, perLevel);
    }
}
