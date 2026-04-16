package com.tomtom.routing.writer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tomtom.routing.model.EnforcementReport;
import com.tomtom.routing.model.RcChange;
import com.tomtom.routing.model.RcGraph;

import java.io.IOException;
import java.nio.file.Path;

public class JsonReportWriter implements ResultWriter {

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    public void write(RcGraph graph, EnforcementReport report, Path outputPath) throws IOException {
        ObjectNode root = mapper.createObjectNode();

        ArrayNode changesNode = root.putArray("changes");
        for (RcChange change : report.changes()) {
            ObjectNode changeNode = changesNode.addObject();
            changeNode.put("edgeId", change.edgeId());
            changeNode.put("oldRc", change.oldRc());
            changeNode.put("newRc", change.newRc());
            changeNode.put("reason", change.reason().name());
            changeNode.put("context", change.context());
        }

        ObjectNode countsNode = root.putObject("componentCounts");
        for (var entry : report.componentCounts().entrySet()) {
            ObjectNode levelNode = countsNode.putObject("RC" + entry.getKey());
            levelNode.put("before", entry.getValue()[0]);
            levelNode.put("after", entry.getValue()[1]);
        }

        ArrayNode exceptionsNode = root.putArray("exceptionHits");
        for (var hit : report.exceptionHits()) {
            ObjectNode hitNode = exceptionsNode.addObject();
            hitNode.put("edgeId", hit.edgeId());
            hitNode.put("justification", hit.justification());
        }

        ArrayNode unresolvableNode = root.putArray("unresolvableIslands");
        for (var island : report.unresolvableIslands()) {
            ObjectNode islandNode = unresolvableNode.addObject();
            islandNode.put("rcLevel", island.rcLevel());
            ArrayNode edgeIds = islandNode.putArray("edgeIds");
            for (String edgeId : island.edgeIds()) {
                edgeIds.add(edgeId);
            }
        }

        ObjectNode summary = root.putObject("summary");
        summary.put("totalUpgrades", report.totalUpgrades());
        summary.put("totalDowngrades", report.totalDowngrades());
        summary.put("totalChanges", report.changes().size());
        summary.put("exceptionHits", report.exceptionHits().size());
        summary.put("unresolvableIslands", report.unresolvableIslands().size());

        mapper.writeValue(outputPath.toFile(), root);
    }
}
