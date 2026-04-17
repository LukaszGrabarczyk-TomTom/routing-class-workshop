package com.tomtom.routing.algorithm;

import com.tomtom.routing.model.RoadEdge;
import com.tomtom.routing.model.RoadGraph;
import com.tomtom.routing.model.RoutingClass;

import java.util.*;

public class RcRefiner {

    private final double promotePercentile;
    private final double demotePercentile;

    public RcRefiner(double promotePercentile, double demotePercentile) {
        this.promotePercentile = promotePercentile;
        this.demotePercentile = demotePercentile;
    }

    public void refine(RoadGraph graph, Map<String, Double> centrality) {
        Map<RoutingClass, List<RoadEdge>> groups = new EnumMap<>(RoutingClass.class);
        for (RoadEdge edge : graph.getEdges()) {
            groups.computeIfAbsent(edge.getComputedRc(), k -> new ArrayList<>()).add(edge);
        }

        for (Map.Entry<RoutingClass, List<RoadEdge>> entry : groups.entrySet()) {
            List<RoadEdge> edgesInGroup = entry.getValue();
            if (edgesInGroup.size() < 2) continue;

            double[] values = edgesInGroup.stream()
                .mapToDouble(e -> centrality.getOrDefault(e.getId(), 0.0))
                .sorted().toArray();

            double promoteThreshold = percentile(values, promotePercentile);
            double demoteThreshold = percentile(values, demotePercentile);

            for (RoadEdge edge : edgesInGroup) {
                double c = centrality.getOrDefault(edge.getId(), 0.0);
                if (c >= promoteThreshold) {
                    edge.setComputedRc(edge.getComputedRc().promote());
                } else if (c <= demoteThreshold) {
                    edge.setComputedRc(edge.getComputedRc().demote());
                }
            }
        }
    }

    private double percentile(double[] sortedValues, double p) {
        if (sortedValues.length == 0) return 0;
        int index = (int) Math.ceil(p * sortedValues.length) - 1;
        index = Math.max(0, Math.min(index, sortedValues.length - 1));
        return sortedValues[index];
    }
}
