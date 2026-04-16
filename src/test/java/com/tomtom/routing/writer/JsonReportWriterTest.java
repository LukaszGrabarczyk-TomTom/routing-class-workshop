package com.tomtom.routing.writer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tomtom.routing.model.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

public class JsonReportWriterTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void writesValidJson() throws IOException {
        EnforcementReport report = new EnforcementReport();
        report.addChange(new RcChange("e1", 3, 1, RcChange.Reason.UPGRADE, "bridge repair"));
        report.addChange(new RcChange("e2", 1, 2, RcChange.Reason.DOWNGRADE, "island at RC1"));
        report.recordComponentCount(1, 5, 1);
        report.addExceptionHit("e5", "Peninsula");
        report.addUnresolvableIsland(5, List.of("e10"));

        Path output = tempFolder.getRoot().toPath().resolve("report.json");
        new JsonReportWriter().write(new RcGraph(), report, output);

        JsonNode root = mapper.readTree(output.toFile());
        assertTrue(root.has("changes"));
        assertTrue(root.has("componentCounts"));
        assertTrue(root.has("exceptionHits"));
        assertTrue(root.has("unresolvableIslands"));
        assertTrue(root.has("summary"));

        assertEquals(2, root.get("changes").size());
        assertEquals(1, root.get("summary").get("totalUpgrades").asInt());
        assertEquals(1, root.get("summary").get("totalDowngrades").asInt());
    }

    @Test
    public void emptyReportWritesValidJson() throws IOException {
        EnforcementReport report = new EnforcementReport();

        Path output = tempFolder.getRoot().toPath().resolve("report.json");
        new JsonReportWriter().write(new RcGraph(), report, output);

        JsonNode root = mapper.readTree(output.toFile());
        assertEquals(0, root.get("changes").size());
        assertEquals(0, root.get("summary").get("totalUpgrades").asInt());
    }

    @Test
    public void changeContainsAllFields() throws IOException {
        EnforcementReport report = new EnforcementReport();
        report.addChange(new RcChange("e1", 3, 1, RcChange.Reason.UPGRADE, "bridge repair"));

        Path output = tempFolder.getRoot().toPath().resolve("report.json");
        new JsonReportWriter().write(new RcGraph(), report, output);

        JsonNode change = mapper.readTree(output.toFile()).get("changes").get(0);
        assertEquals("e1", change.get("edgeId").asText());
        assertEquals(3, change.get("oldRc").asInt());
        assertEquals(1, change.get("newRc").asInt());
        assertEquals("UPGRADE", change.get("reason").asText());
        assertEquals("bridge repair", change.get("context").asText());
    }
}
