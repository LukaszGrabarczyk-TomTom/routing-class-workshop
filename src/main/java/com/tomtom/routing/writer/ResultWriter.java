package com.tomtom.routing.writer;

import com.tomtom.routing.model.EnforcementReport;
import com.tomtom.routing.model.RcGraph;

import java.io.IOException;
import java.nio.file.Path;

public interface ResultWriter {
    void write(RcGraph graph, EnforcementReport report, Path outputPath) throws IOException;
}
