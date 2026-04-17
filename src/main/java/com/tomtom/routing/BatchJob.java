package com.tomtom.routing;

import com.tomtom.routing.algorithm.*;
import com.tomtom.routing.comparison.RcComparator;
import com.tomtom.routing.comparison.ReportWriter;
import com.tomtom.routing.io.PbfReader;
import com.tomtom.routing.io.PbfWriter;
import com.tomtom.routing.model.RoadGraph;

import java.io.IOException;

public class BatchJob {

    private static final int DEFAULT_CENTRALITY_SAMPLE_SIZE = 2000;
    private static final double DEFAULT_PROMOTE_PERCENTILE = 0.85;
    private static final double DEFAULT_DEMOTE_PERCENTILE = 0.15;

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: BatchJob <input.osm.pbf> [output.osm.pbf]");
            System.exit(1);
        }

        String inputPath = args[0];
        String baseName = inputPath.replaceAll("\\.osm\\.pbf$", "");
        String outputPbf = args.length > 1 ? args[1] : baseName + "_computed_rc.osm.pbf";
        String diffCsv = baseName + "_diff.csv";
        String summaryCsv = baseName + "_summary.csv";

        System.out.println("=== Phase 1: Parsing PBF ===");
        PbfReader reader = new PbfReader();
        RoadGraph graph = reader.read(inputPath);
        System.out.printf("Graph built: %d nodes, %d edges%n", graph.nodeCount(), graph.edgeCount());

        System.out.println("\n=== Phase 2: Seed & Refine ===");
        System.out.println("Seeding from attributes...");
        AttributeSeeder seeder = new AttributeSeeder();
        seeder.seed(graph);
        seeder.seedFerries(graph);

        System.out.printf("Computing centrality (sample size: %d)...%n", DEFAULT_CENTRALITY_SAMPLE_SIZE);
        var centrality = CentralityComputer.compute(graph, DEFAULT_CENTRALITY_SAMPLE_SIZE);
        System.out.println("Refining RC with centrality...");
        new RcRefiner(DEFAULT_PROMOTE_PERCENTILE, DEFAULT_DEMOTE_PERCENTILE).refine(graph, centrality);

        System.out.println("\n=== Phase 3: Connectivity Enforcement ===");
        ConnectivityEnforcer enforcer = new ConnectivityEnforcer();
        ConnectivityEnforcer.Result enforcementResult = enforcer.enforce(graph);
        System.out.printf("Enforcement done: %d promotions, %d demotions%n",
            enforcementResult.promotions(), enforcementResult.demotions());

        System.out.println("\n=== Writing Output ===");
        System.out.println("Writing output PBF: " + outputPbf);
        new PbfWriter().write(graph, outputPbf);

        System.out.println("\n=== Comparison ===");
        RcComparator.Report report = RcComparator.compare(graph);
        ReportWriter.writeConsoleReport(report, enforcementResult, graph);

        System.out.println("\nWriting diff CSV: " + diffCsv);
        ReportWriter.writePerRoadDiffCsv(graph, diffCsv);

        System.out.println("Writing summary CSV: " + summaryCsv);
        ReportWriter.writeAggregatedSummaryCsv(graph, summaryCsv);

        System.out.println("\nDone.");
    }
}
