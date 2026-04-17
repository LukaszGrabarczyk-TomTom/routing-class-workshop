package com.tomtom.routing;

import com.tomtom.routing.algorithm.*;
import com.tomtom.routing.io.PbfReader;
import com.tomtom.routing.model.*;

import java.io.IOException;
import java.util.*;

/**
 * Counts connected components (undirected) per RC level for both original and computed RCs.
 */
public class ComponentStats {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: ComponentStats <input.osm.pbf>");
            System.exit(1);
        }

        System.out.println("=== Parsing PBF ===");
        PbfReader reader = new PbfReader();
        RoadGraph graph = reader.read(args[0]);
        System.out.printf("Graph: %d nodes, %d edges%n%n", graph.nodeCount(), graph.edgeCount());

        // Run the algorithm to populate computedRc
        System.out.println("=== Running algorithm ===");
        AttributeSeeder seeder = new AttributeSeeder();
        seeder.seed(graph);
        seeder.seedFerries(graph);
        var centrality = CentralityComputer.compute(graph, 2000);
        new RcRefiner(0.85, 0.15).refine(graph, centrality);
        ConnectivityEnforcer enforcer = new ConnectivityEnforcer();
        enforcer.enforce(graph);
        System.out.println("Algorithm complete.\n");

        // Component analysis
        System.out.println("=== CONNECTED COMPONENTS (undirected) ===");
        System.out.printf("%-6s | %-12s | %-10s %-10s %-10s | %-10s %-10s %-10s%n",
            "Level", "Description", "Orig#Comp", "Orig#Edges", "OrigLargest",
            "Comp#Comp", "Comp#Edges", "CompLargest");
        System.out.println("-".repeat(100));

        int totalOrigComponents = 0;
        int totalCompComponents = 0;

        for (int rc = 1; rc <= 5; rc++) {
            // Exact level: edges with RC == rc
            int[] origExact = countComponents(graph, rc, true, true);
            int[] compExact = countComponents(graph, rc, false, true);

            System.out.printf("RC%d    | exact (==%d)  | %-10d %-10d %-10d | %-10d %-10d %-10d%n",
                rc, rc, origExact[0], origExact[1], origExact[2], compExact[0], compExact[1], compExact[2]);

            totalOrigComponents += origExact[0];
            totalCompComponents += compExact[0];
        }

        System.out.println("-".repeat(100));

        for (int rc = 1; rc <= 5; rc++) {
            // Cumulative: edges with RC <= rc
            int[] origCum = countComponents(graph, rc, true, false);
            int[] compCum = countComponents(graph, rc, false, false);

            System.out.printf("RC1-%d  | cumul (<=%d)  | %-10d %-10d %-10d | %-10d %-10d %-10d%n",
                rc, rc, origCum[0], origCum[1], origCum[2], compCum[0], compCum[1], compCum[2]);
        }

        System.out.println("-".repeat(100));
        System.out.printf("%nTotal exact-level components:  Original=%d  Computed=%d%n",
            totalOrigComponents, totalCompComponents);
    }

    /**
     * Returns [numComponents, numEdges, largestComponentSize] for the subgraph
     * filtered by RC level using union-find (undirected connectivity).
     *
     * @param exactLevel if true, filter edges where RC == level; otherwise RC <= level
     * @param useExisting if true, use existingRc; otherwise use computedRc
     */
    private static int[] countComponents(RoadGraph graph, int level, boolean useExisting, boolean exactLevel) {
        Map<Long, Long> parent = new HashMap<>();
        Map<Long, Integer> rank = new HashMap<>();
        int edgeCount = 0;

        for (RoadEdge edge : graph.getEdges()) {
            RoutingClass rc = useExisting ? edge.getExistingRc() : edge.getComputedRc();
            if (rc == null) continue;

            boolean match = exactLevel ? (rc.value() == level) : (rc.value() <= level);
            if (!match) continue;

            edgeCount++;
            long a = edge.getFromNodeId();
            long b = edge.getToNodeId();
            parent.putIfAbsent(a, a);
            parent.putIfAbsent(b, b);
            rank.putIfAbsent(a, 0);
            rank.putIfAbsent(b, 0);
            union(parent, rank, a, b);
        }

        // Count distinct roots
        Set<Long> roots = new HashSet<>();
        Map<Long, Integer> compSizes = new HashMap<>();
        for (long node : parent.keySet()) {
            long root = find(parent, node);
            roots.add(root);
            compSizes.merge(root, 1, Integer::sum);
        }

        int largest = compSizes.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        return new int[]{roots.size(), edgeCount, largest};
    }

    private static long find(Map<Long, Long> parent, long x) {
        while (parent.get(x) != x) {
            long p = parent.get(parent.get(x));
            parent.put(x, p);
            x = p;
        }
        return x;
    }

    private static void union(Map<Long, Long> parent, Map<Long, Integer> rank, long a, long b) {
        long ra = find(parent, a);
        long rb = find(parent, b);
        if (ra == rb) return;
        int rankA = rank.get(ra);
        int rankB = rank.get(rb);
        if (rankA < rankB) { parent.put(ra, rb); }
        else if (rankA > rankB) { parent.put(rb, ra); }
        else { parent.put(rb, ra); rank.put(ra, rankA + 1); }
    }
}
