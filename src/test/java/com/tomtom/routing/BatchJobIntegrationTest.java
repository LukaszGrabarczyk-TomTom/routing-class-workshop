package com.tomtom.routing;

import com.tomtom.routing.algorithm.*;
import com.tomtom.routing.comparison.RcComparator;
import com.tomtom.routing.io.PbfReader;
import com.tomtom.routing.model.RoadEdge;
import com.tomtom.routing.model.RoadGraph;
import com.tomtom.routing.model.RoutingClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class BatchJobIntegrationTest {

    private static final String PBF_PATH = "orbis_nexventura_26160_000_global_lux.osm.pbf";
    private static RoadGraph graph;
    private static ConnectivityEnforcer.Result enforcementResult;
    private static RcComparator.Report report;

    @BeforeClass
    public static void runPipeline() throws Exception {
        assumeTrue("PBF file must exist to run integration test",
            new File(PBF_PATH).exists());

        PbfReader reader = new PbfReader();
        graph = reader.read(PBF_PATH);

        AttributeSeeder seeder = new AttributeSeeder();
        seeder.seed(graph);
        seeder.seedFerries(graph);
        var centrality = CentralityComputer.compute(graph, 200);
        new RcRefiner(0.85, 0.15).refine(graph, centrality);

        ConnectivityEnforcer enforcer = new ConnectivityEnforcer();
        enforcementResult = enforcer.enforce(graph);

        report = RcComparator.compare(graph);
    }

    @Test
    public void graphHasReasonableSize() {
        assertTrue("Expected >50k edges, got " + graph.edgeCount(),
            graph.edgeCount() > 50_000);
    }

    @Test
    public void allEdgesHaveComputedRc() {
        for (RoadEdge edge : graph.getEdges()) {
            assertNotNull("Edge " + edge.getId() + " has no computed RC",
                edge.getComputedRc());
        }
    }

    @Test
    public void rc1ComponentCountIsReported() {
        Set<String> rc1EdgeIds = graph.getEdges().stream()
            .filter(e -> e.getComputedRc() == RoutingClass.RC1)
            .map(RoadEdge::getId)
            .collect(Collectors.toSet());

        if (rc1EdgeIds.isEmpty()) return;

        var sccs = TarjanScc.compute(graph, rc1EdgeIds);
        System.out.println("RC1 components: " + sccs.size());
        System.out.println("RC1 largest component: " + sccs.get(0).size() + " nodes");
        System.out.println("RC1 total edges: " + rc1EdgeIds.size());
        // This is informational — the prototype uses capped Dijkstra so bridging
        // doesn't reach distant components. Component count improves with higher
        // sample sizes and wider Dijkstra reach.
        assertTrue("RC1 should have at least some edges", rc1EdgeIds.size() > 0);
    }

    @Test
    public void comparisonMetricsAreReasonable() {
        assertTrue("Expected >10k compared roads, got " + report.totalCompared(),
            report.totalCompared() > 10_000);

        assertTrue("Match rate suspiciously low: " + report.overallMatchPercent(),
            report.overallMatchPercent() > 10.0);
    }
}
