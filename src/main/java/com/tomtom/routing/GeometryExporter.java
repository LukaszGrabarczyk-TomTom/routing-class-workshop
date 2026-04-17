package com.tomtom.routing;

import com.tomtom.routing.algorithm.*;
import com.tomtom.routing.io.PbfReader;
import com.tomtom.routing.model.*;

import java.io.*;
import java.util.List;

/**
 * Exports edge geometries with original and computed RC to CSV for visualization.
 * Format: edge_id,existing_rc,computed_rc,highway,point_index,lon,lat
 */
public class GeometryExporter {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: GeometryExporter <input.osm.pbf> [output.csv]");
            System.exit(1);
        }

        String outputCsv = args.length > 1 ? args[1] : "edges_geometry.csv";

        System.err.println("Parsing PBF...");
        RoadGraph graph = new PbfReader().read(args[0]);
        System.err.printf("Graph: %d nodes, %d edges%n", graph.nodeCount(), graph.edgeCount());

        System.err.println("Running algorithm...");
        AttributeSeeder seeder = new AttributeSeeder();
        seeder.seed(graph);
        seeder.seedFerries(graph);
        var centrality = CentralityComputer.compute(graph, 2000);
        new RcRefiner(0.85, 0.15).refine(graph, centrality);
        new ConnectivityEnforcer().enforce(graph);
        System.err.println("Algorithm complete.");

        System.err.println("Exporting to " + outputCsv + "...");
        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(outputCsv), 1 << 20))) {
            pw.println("edge_id,from_node,to_node,existing_rc,computed_rc,highway,point_idx,lon,lat");
            for (RoadEdge edge : graph.getEdges()) {
                int existingRc = edge.getExistingRc() != null ? edge.getExistingRc().value() : 0;
                int computedRc = edge.getComputedRc() != null ? edge.getComputedRc().value() : 0;
                String highway = edge.getAttribute("highway");
                if (highway == null) highway = "";

                List<double[]> geom = edge.getGeometry();
                if (geom == null || geom.isEmpty()) {
                    // Fall back to node positions
                    continue;
                }

                for (int i = 0; i < geom.size(); i++) {
                    pw.printf("%s,%d,%d,%d,%d,%s,%d,%.7f,%.7f%n",
                        edge.getId(), edge.getFromNodeId(), edge.getToNodeId(),
                        existingRc, computedRc, highway,
                        i, geom.get(i)[0], geom.get(i)[1]);
                }
            }
        }
        System.err.println("Done. Exported geometry for all edges.");
    }
}
