package com.tomtom.routing.comparison;

import com.tomtom.routing.algorithm.ConnectivityEnforcer;
import com.tomtom.routing.model.RoadEdge;
import com.tomtom.routing.model.RoadGraph;
import com.tomtom.routing.model.RoadNode;
import com.tomtom.routing.model.RoutingClass;

import java.io.*;
import java.util.*;

public class ReportWriter {

    public static void writeConsoleReport(RcComparator.Report report,
                                          ConnectivityEnforcer.Result enforcementResult,
                                          RoadGraph graph) {
        System.out.println("\n=== ROUTING CLASS COMPARISON REPORT ===\n");
        System.out.printf("Overall match: %.1f%% (%d / %d ways)%n",
            report.overallMatchPercent(), report.totalMatched(), report.totalCompared());

        System.out.println("\nPer-level match rates:");
        for (int rc = 1; rc <= 5; rc++) {
            int[] stats = report.perLevelStats().get(rc);
            double pct = stats[1] > 0 ? (100.0 * stats[0] / stats[1]) : 0;
            System.out.printf("  RC%d: %.1f%% (%d / %d)%n", rc, pct, stats[0], stats[1]);
        }

        System.out.println("\nConfusion matrix (rows=computed, cols=existing):");
        System.out.print("     ");
        for (int j = 1; j <= 5; j++) System.out.printf("  RC%d", j);
        System.out.println();
        for (int i = 0; i < 5; i++) {
            System.out.printf("RC%d  ", i + 1);
            for (int j = 0; j < 5; j++) {
                System.out.printf("%5d", report.confusionMatrix()[i][j]);
            }
            System.out.println();
        }

        if (enforcementResult != null) {
            System.out.printf("%nEnforcement: %d promotions, %d demotions%n",
                enforcementResult.promotions(), enforcementResult.demotions());
        }

        System.out.println("\nDead ends per RC level:");
        Map<Integer, Integer> deadEnds = countDeadEnds(graph);
        for (int rc = 1; rc <= 5; rc++) {
            System.out.printf("  RC%d: %d dead ends%n", rc, deadEnds.getOrDefault(rc, 0));
        }
    }

    private static Map<Integer, Integer> countDeadEnds(RoadGraph graph) {
        Map<Integer, Integer> result = new HashMap<>();
        for (int level = 1; level <= 5; level++) {
            int rcLevel = level;
            Map<Long, int[]> nodeDegrees = new HashMap<>();
            for (RoadEdge edge : graph.getEdges()) {
                if (edge.getComputedRc() != null && edge.getComputedRc().value() <= rcLevel) {
                    nodeDegrees.computeIfAbsent(edge.getFromNodeId(), k -> new int[2])[1]++;
                    nodeDegrees.computeIfAbsent(edge.getToNodeId(), k -> new int[2])[0]++;
                }
            }
            int deadEndCount = 0;
            for (int[] degrees : nodeDegrees.values()) {
                if ((degrees[0] > 0 && degrees[1] == 0) || (degrees[0] == 0 && degrees[1] > 0)) {
                    deadEndCount++;
                }
            }
            result.put(level, deadEndCount);
        }
        return result;
    }

    public static void writePerRoadDiffCsv(RoadGraph graph, String outputPath) throws IOException {
        Map<Long, RoutingClass> computedByWay = new HashMap<>();
        Map<Long, RoutingClass> existingByWay = new HashMap<>();
        Map<Long, String> highwayByWay = new HashMap<>();
        Map<Long, Double> lengthByWay = new HashMap<>();

        for (RoadEdge edge : graph.getEdges()) {
            long wayId = edge.getParentWayId();
            if (edge.getComputedRc() != null) {
                computedByWay.merge(wayId, edge.getComputedRc(),
                    (a, b) -> a.value() < b.value() ? a : b);
            }
            if (edge.getExistingRc() != null) {
                existingByWay.merge(wayId, edge.getExistingRc(),
                    (a, b) -> a.value() < b.value() ? a : b);
            }
            highwayByWay.putIfAbsent(wayId, edge.getAttribute("highway"));
            lengthByWay.merge(wayId, edge.getLengthMeters(), Double::sum);
        }

        try (PrintWriter pw = new PrintWriter(new FileWriter(outputPath))) {
            pw.println("road_id,highway_type,geometry_length_m,computed_rc,existing_rc,match");
            for (long wayId : existingByWay.keySet()) {
                RoutingClass computed = computedByWay.get(wayId);
                RoutingClass existing = existingByWay.get(wayId);
                if (computed == null) continue;

                pw.printf("%d,%s,%.1f,%d,%d,%s%n",
                    wayId,
                    highwayByWay.getOrDefault(wayId, ""),
                    lengthByWay.getOrDefault(wayId, 0.0),
                    computed.value(),
                    existing.value(),
                    computed == existing ? "yes" : "no");
            }
        }
    }

    public static void writeAggregatedSummaryCsv(RoadGraph graph, String outputPath) throws IOException {
        Map<Long, RoutingClass> computedByWay = new HashMap<>();
        Map<Long, RoutingClass> existingByWay = new HashMap<>();
        Map<Long, Double> lengthByWay = new HashMap<>();

        for (RoadEdge edge : graph.getEdges()) {
            long wayId = edge.getParentWayId();
            if (edge.getComputedRc() != null) {
                computedByWay.merge(wayId, edge.getComputedRc(),
                    (a, b) -> a.value() < b.value() ? a : b);
            }
            if (edge.getExistingRc() != null) {
                existingByWay.merge(wayId, edge.getExistingRc(),
                    (a, b) -> a.value() < b.value() ? a : b);
            }
            lengthByWay.merge(wayId, edge.getLengthMeters(), Double::sum);
        }

        Map<String, int[]> segCounts = new TreeMap<>();
        Map<String, Double> totalLengths = new TreeMap<>();

        for (long wayId : existingByWay.keySet()) {
            RoutingClass computed = computedByWay.get(wayId);
            RoutingClass existing = existingByWay.get(wayId);
            if (computed == null) continue;

            String key = computed.value() + "," + existing.value();
            segCounts.computeIfAbsent(key, k -> new int[]{0})[0]++;
            totalLengths.merge(key, lengthByWay.getOrDefault(wayId, 0.0), Double::sum);
        }

        try (PrintWriter pw = new PrintWriter(new FileWriter(outputPath))) {
            pw.println("computed_rc,existing_rc,segment_count,total_length_km");
            for (String key : segCounts.keySet()) {
                pw.printf("%s,%d,%.2f%n",
                    key, segCounts.get(key)[0], totalLengths.get(key) / 1000.0);
            }
        }
    }
}
