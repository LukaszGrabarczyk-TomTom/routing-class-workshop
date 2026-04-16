package com.tomtom.routing.adapter;

import com.tomtom.routing.model.*;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.Assert.*;

public class FrcCsvAdapterTest {

    private final IdMapping directMapping = productId ->
            Optional.of("e" + productId.substring(1)); // P001 → e001

    @Test
    public void parsesValidCsv() throws IOException {
        RcGraph graph = buildGraphWithEdges("e001", "e002", "e003", "e004");

        FrcCsvAdapter adapter = new FrcCsvAdapter(
                Path.of("src/test/resources/frc-sample.csv"), directMapping);
        adapter.populate(graph);

        assertEquals(1, graph.edge("e001").routingClass().getAsInt());
        assertEquals(2, graph.edge("e002").routingClass().getAsInt());
        assertEquals(3, graph.edge("e003").routingClass().getAsInt());
        assertEquals(5, graph.edge("e004").routingClass().getAsInt());
    }

    @Test
    public void skipsUnmappableProductIds() throws IOException {
        IdMapping partialMapping = productId -> {
            if (productId.equals("P001")) return Optional.of("e001");
            return Optional.empty();
        };

        RcGraph graph = buildGraphWithEdges("e001");
        FrcCsvAdapter adapter = new FrcCsvAdapter(
                Path.of("src/test/resources/frc-sample.csv"), partialMapping);
        adapter.populate(graph);

        assertEquals(1, graph.edge("e001").routingClass().getAsInt());
    }

    @Test
    public void skipsMalformedLines() throws IOException {
        RcGraph graph = buildGraphWithEdges("e001", "e002", "e003");
        FrcCsvAdapter adapter = new FrcCsvAdapter(
                Path.of("src/test/resources/frc-malformed.csv"), directMapping);
        adapter.populate(graph);

        assertEquals(1, graph.edge("e001").routingClass().getAsInt());
        assertTrue(graph.edge("e002").routingClass().isEmpty());
        assertTrue(graph.edge("e003").routingClass().isEmpty());
    }

    @Test
    public void reportsParseStatistics() throws IOException {
        RcGraph graph = buildGraphWithEdges("e001", "e002", "e003", "e004");
        FrcCsvAdapter adapter = new FrcCsvAdapter(
                Path.of("src/test/resources/frc-sample.csv"), directMapping);
        adapter.populate(graph);

        assertEquals(4, adapter.totalLines());
        assertEquals(4, adapter.appliedCount());
        assertEquals(0, adapter.skippedCount());
    }

    private RcGraph buildGraphWithEdges(String... edgeIds) {
        RcGraph graph = new RcGraph();
        int nodeCounter = 0;
        for (String edgeId : edgeIds) {
            String n1 = "n" + (nodeCounter++);
            String n2 = "n" + (nodeCounter++);
            graph.addNode(new Node(n1));
            graph.addNode(new Node(n2));
            graph.addEdge(new Edge(edgeId, n1, n2, TraversalMode.BOTH));
        }
        return graph;
    }
}
