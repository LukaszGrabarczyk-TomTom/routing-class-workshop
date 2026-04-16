package com.tomtom.routing;

import com.tomtom.routing.exception.ExceptionRegistry;
import com.tomtom.routing.model.EnforcementReport;
import com.tomtom.routing.model.RcGraph;
import com.tomtom.routing.repair.BridgeFirstCascadeRepair;
import com.tomtom.routing.repair.RepairConfig;
import com.tomtom.routing.repair.RepairStrategy;
import com.tomtom.routing.writer.JsonReportWriter;
import com.tomtom.routing.writer.ParquetResultWriter;

import java.io.IOException;
import java.nio.file.Path;

public class ConnectivityEnforcer {

    private final RepairConfig config;
    private final RepairStrategy repairStrategy;

    public ConnectivityEnforcer(RepairConfig config) {
        this(config, new BridgeFirstCascadeRepair());
    }

    public ConnectivityEnforcer(RepairConfig config, RepairStrategy repairStrategy) {
        this.config = config;
        this.repairStrategy = repairStrategy;
    }

    public EnforcementReport enforce(RcGraph graph, ExceptionRegistry exceptions,
                                     Path parquetOutput, Path jsonReportOutput) throws IOException {
        EnforcementReport report = repairStrategy.enforce(graph, exceptions, config);
        new ParquetResultWriter().write(graph, report, parquetOutput);
        new JsonReportWriter().write(graph, report, jsonReportOutput);
        return report;
    }
}
