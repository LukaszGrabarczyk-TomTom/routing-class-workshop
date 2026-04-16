package com.tomtom.routing.algorithm;

import com.tomtom.routing.model.RoadEdge;
import com.tomtom.routing.model.RoadGraph;

import java.util.*;

public class CentralityComputer {

    private static final int DEFAULT_MAX_VISITED_NODES = 10_000;

    public static Map<String, Double> compute(RoadGraph graph, int sampleSize) {
        return compute(graph, sampleSize, DEFAULT_MAX_VISITED_NODES);
    }

    public static Map<String, Double> compute(RoadGraph graph, int sampleSize, int maxVisitedNodes) {
        Map<String, Double> centrality = new HashMap<>();

        for (RoadEdge edge : graph.getEdges()) {
            centrality.put(edge.getId(), 0.0);
        }

        List<Long> nodeIds = new ArrayList<>(graph.getNodes().keySet());
        Collections.shuffle(nodeIds);
        int effectiveSample = Math.min(sampleSize, nodeIds.size());

        for (int i = 0; i < effectiveSample; i++) {
            long sourceId = nodeIds.get(i);
            Dijkstra.Result result = Dijkstra.run(graph, sourceId, maxVisitedNodes);

            for (long targetId : result.reachableNodes()) {
                if (targetId == sourceId) continue;

                long current = targetId;
                while (current != sourceId) {
                    RoadEdge predEdge = result.predecessorEdge(current);
                    if (predEdge == null) break;
                    centrality.merge(predEdge.getId(), 1.0, Double::sum);
                    current = predEdge.getFromNodeId();
                }
            }
        }

        return centrality;
    }
}
