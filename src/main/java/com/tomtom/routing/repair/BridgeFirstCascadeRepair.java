package com.tomtom.routing.repair;

import com.tomtom.routing.analysis.ConnectivityResult;
import com.tomtom.routing.analysis.UndirectedAnalyzer;
import com.tomtom.routing.exception.ExceptionRegistry;
import com.tomtom.routing.model.*;

import java.util.*;

public class BridgeFirstCascadeRepair implements RepairStrategy {

    @Override
    public EnforcementReport enforce(RcGraph graph, ExceptionRegistry exceptions, RepairConfig config) {
        EnforcementReport report = new EnforcementReport();
        for (int level : config.rcLevelsToProcess()) {
            enforceLevel(graph, level, exceptions, config, report);
        }
        return report;
    }

    private void enforceLevel(RcGraph graph, int level, ExceptionRegistry exceptions,
                              RepairConfig config, EnforcementReport report) {
        ConnectivityResult result = UndirectedAnalyzer.analyze(graph, level);
        int componentsBefore = result.totalComponents();

        if (result.isConnected()) {
            report.recordComponentCount(level, componentsBefore, 1);
            return;
        }

        Set<String> mainComponentEdgeIds = new LinkedHashSet<>(result.mainComponent());

        for (Set<String> island : result.islands()) {
            boolean allExcepted = island.stream().allMatch(exceptions::isException);
            if (allExcepted) {
                for (String edgeId : island) {
                    report.addExceptionHit(edgeId, exceptions.justification(edgeId).orElse(""));
                }
                continue;
            }

            List<Edge> bridge = findBridge(graph, island, mainComponentEdgeIds, level, config);

            if (bridge != null) {
                for (Edge bridgeEdge : bridge) {
                    int oldRc = bridgeEdge.routingClass().orElse(level + 1);
                    bridgeEdge.setRoutingClass(level);
                    report.addChange(new RcChange(bridgeEdge.id(), oldRc, level,
                            RcChange.Reason.UPGRADE, "bridge to reconnect island at RC" + level));
                }
                mainComponentEdgeIds.addAll(island);
                for (Edge e : bridge) {
                    mainComponentEdgeIds.add(e.id());
                }
            } else {
                if (level >= 5) {
                    report.addUnresolvableIsland(level, List.copyOf(island));
                } else {
                    for (String edgeId : island) {
                        Edge edge = graph.edge(edgeId);
                        if (edge != null) {
                            int oldRc = edge.routingClass().orElse(level);
                            edge.setRoutingClass(level + 1);
                            report.addChange(new RcChange(edgeId, oldRc, level + 1,
                                    RcChange.Reason.DOWNGRADE, "island at RC" + level + ", no viable bridge found"));
                        }
                    }
                }
            }
        }

        ConnectivityResult afterResult = UndirectedAnalyzer.analyze(graph, level);
        report.recordComponentCount(level, componentsBefore, afterResult.totalComponents());
    }

    private List<Edge> findBridge(RcGraph graph, Set<String> islandEdgeIds,
                                  Set<String> mainEdgeIds, int targetLevel, RepairConfig config) {
        Set<String> islandNodes = new HashSet<>();
        for (String edgeId : islandEdgeIds) {
            Edge edge = graph.edge(edgeId);
            if (edge != null) {
                islandNodes.add(edge.sourceNodeId());
                islandNodes.add(edge.targetNodeId());
            }
        }

        Set<String> mainNodes = new HashSet<>();
        for (String edgeId : mainEdgeIds) {
            Edge edge = graph.edge(edgeId);
            if (edge != null) {
                mainNodes.add(edge.sourceNodeId());
                mainNodes.add(edge.targetNodeId());
            }
        }

        for (String startNode : islandNodes) {
            List<Edge> bridgePath = bfsForBridge(graph, startNode, mainNodes, islandNodes, targetLevel, config);
            if (bridgePath != null) {
                return bridgePath;
            }
        }
        return null;
    }

    private List<Edge> bfsForBridge(RcGraph graph, String startNode, Set<String> mainNodes,
                                    Set<String> islandNodes, int targetLevel, RepairConfig config) {
        Queue<String> queue = new ArrayDeque<>();
        Map<String, List<Edge>> pathTo = new HashMap<>();

        queue.add(startNode);
        pathTo.put(startNode, List.of());

        while (!queue.isEmpty()) {
            String current = queue.poll();
            List<Edge> currentPath = pathTo.get(current);

            if (currentPath.size() >= config.maxBridgeHops()) continue;

            for (Edge edge : graph.edgesFrom(current)) {
                if (edge.routingClass().isEmpty() || edge.routingClass().getAsInt() <= targetLevel) continue;

                int rcJump = edge.routingClass().getAsInt() - targetLevel;
                if (rcJump > config.maxRcJump()) continue;

                String neighbor = edge.sourceNodeId().equals(current) ? edge.targetNodeId() : edge.sourceNodeId();
                if (pathTo.containsKey(neighbor)) continue;

                List<Edge> newPath = new ArrayList<>(currentPath);
                newPath.add(edge);

                if (mainNodes.contains(neighbor) && !islandNodes.contains(neighbor)) {
                    if (newPath.size() <= config.maxPromotions()) {
                        return newPath;
                    }
                    continue;
                }

                pathTo.put(neighbor, newPath);
                queue.add(neighbor);
            }
        }
        return null;
    }
}
