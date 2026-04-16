package com.tomtom.routing.adapter;

import com.tomtom.routing.model.Edge;
import com.tomtom.routing.model.RcGraph;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public class FrcCsvAdapter implements GraphAdapter {

    private final Path csvPath;
    private final IdMapping idMapping;
    private int totalLines;
    private int appliedCount;
    private int skippedCount;

    public FrcCsvAdapter(Path csvPath, IdMapping idMapping) {
        this.csvPath = csvPath;
        this.idMapping = idMapping;
    }

    @Override
    public void populate(RcGraph graph) {
        try {
            List<String> lines = Files.readAllLines(csvPath);
            totalLines = 0;
            appliedCount = 0;
            skippedCount = 0;

            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;

                totalLines++;
                String[] parts = line.split(",", -1);
                if (parts.length < 3) { skippedCount++; continue; }

                String productId = parts[0].trim();
                String rcStr = parts[1].trim();

                if (productId.isEmpty() || rcStr.isEmpty()) { skippedCount++; continue; }

                int rc;
                try { rc = Integer.parseInt(rcStr); }
                catch (NumberFormatException e) { skippedCount++; continue; }

                if (rc < 1 || rc > 5) { skippedCount++; continue; }

                Optional<String> edgeId = idMapping.toEdgeId(productId);
                if (edgeId.isEmpty()) { skippedCount++; continue; }

                Edge edge = graph.edge(edgeId.get());
                if (edge == null) { skippedCount++; continue; }

                edge.setRoutingClass(rc);
                appliedCount++;
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read FRC CSV: " + csvPath, e);
        }
    }

    public int totalLines() { return totalLines; }
    public int appliedCount() { return appliedCount; }
    public int skippedCount() { return skippedCount; }
}
