package com.tomtom.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tomtom.routing.adapter.ExceptionFileAdapter;
import com.tomtom.routing.exception.ExceptionRegistry;
import com.tomtom.routing.model.*;
import com.tomtom.routing.repair.RepairConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class ConnectivityEnforcerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void endToEndEnforcement() throws IOException {
        // A --RC1-- B --RC1-- F --RC1-- E    C --RC1-- D --RC3-- E
        // Main component: {A,B,E,F}; Island {C,D} can be bridged via D-E (RC3→RC1)
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("A"));
        graph.addNode(new Node("B"));
        graph.addNode(new Node("C"));
        graph.addNode(new Node("D"));
        graph.addNode(new Node("E"));
        graph.addNode(new Node("F"));
        graph.addEdge(new Edge("e1", "A", "B", TraversalMode.BOTH, 1));
        graph.addEdge(new Edge("e5", "B", "F", TraversalMode.BOTH, 1));
        graph.addEdge(new Edge("e2", "C", "D", TraversalMode.BOTH, 1));
        graph.addEdge(new Edge("e3", "D", "E", TraversalMode.BOTH, 3));
        graph.addEdge(new Edge("e4", "E", "F", TraversalMode.BOTH, 1));

        RepairConfig config = RepairConfig.builder()
                .rcLevelsToProcess(1)
                .maxRcJump(3)
                .build();

        Path parquetOut = tempFolder.getRoot().toPath().resolve("result.parquet");
        Path jsonOut = tempFolder.getRoot().toPath().resolve("report.json");

        ConnectivityEnforcer enforcer = new ConnectivityEnforcer(config);
        enforcer.enforce(graph, ExceptionFileAdapter.empty(), parquetOut, jsonOut);

        // Verify JSON report
        JsonNode root = new ObjectMapper().readTree(jsonOut.toFile());
        assertEquals(1, root.get("summary").get("totalUpgrades").asInt());
        assertEquals(0, root.get("summary").get("totalDowngrades").asInt());

        // Verify the bridge edge was promoted
        assertEquals(1, graph.edge("e3").routingClass().getAsInt());

        // Verify Parquet was written
        assertTrue(Files.exists(parquetOut));
    }

    @Test
    public void endToEndWithExceptions() throws IOException {
        // A --RC1-- B    C --RC1-- D (island, but excepted)
        RcGraph graph = new RcGraph();
        graph.addNode(new Node("A"));
        graph.addNode(new Node("B"));
        graph.addNode(new Node("C"));
        graph.addNode(new Node("D"));
        graph.addEdge(new Edge("e1", "A", "B", TraversalMode.BOTH, 1));
        graph.addEdge(new Edge("e2", "C", "D", TraversalMode.BOTH, 1));

        Path excFile = tempFolder.getRoot().toPath().resolve("exceptions.txt");
        Files.writeString(excFile, "e2 # Peninsula dead end\n");
        ExceptionRegistry exceptions = ExceptionFileAdapter.load(excFile);

        RepairConfig config = RepairConfig.builder().rcLevelsToProcess(1).build();

        Path parquetOut = tempFolder.getRoot().toPath().resolve("result.parquet");
        Path jsonOut = tempFolder.getRoot().toPath().resolve("report.json");

        ConnectivityEnforcer enforcer = new ConnectivityEnforcer(config);
        enforcer.enforce(graph, exceptions, parquetOut, jsonOut);

        JsonNode root = new ObjectMapper().readTree(jsonOut.toFile());
        assertEquals(0, root.get("summary").get("totalChanges").asInt());
        assertEquals(1, root.get("summary").get("exceptionHits").asInt());
    }
}
