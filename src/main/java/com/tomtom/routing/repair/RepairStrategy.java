package com.tomtom.routing.repair;

import com.tomtom.routing.exception.ExceptionRegistry;
import com.tomtom.routing.model.EnforcementReport;
import com.tomtom.routing.model.RcGraph;

public interface RepairStrategy {
    EnforcementReport enforce(RcGraph graph, ExceptionRegistry exceptions, RepairConfig config);
}
