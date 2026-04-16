package com.tomtom.routing.model;

import java.util.Objects;
import java.util.OptionalInt;

public final class Edge {

    private final String id;
    private final String sourceNodeId;
    private final String targetNodeId;
    private final TraversalMode traversalMode;
    private int routingClass;
    private boolean hasRoutingClass;

    public Edge(String id, String sourceNodeId, String targetNodeId, TraversalMode traversalMode) {
        this.id = Objects.requireNonNull(id);
        this.sourceNodeId = Objects.requireNonNull(sourceNodeId);
        this.targetNodeId = Objects.requireNonNull(targetNodeId);
        this.traversalMode = Objects.requireNonNull(traversalMode);
        this.hasRoutingClass = false;
    }

    public Edge(String id, String sourceNodeId, String targetNodeId, TraversalMode traversalMode, int routingClass) {
        this(id, sourceNodeId, targetNodeId, traversalMode);
        setRoutingClass(routingClass);
    }

    public String id() { return id; }
    public String sourceNodeId() { return sourceNodeId; }
    public String targetNodeId() { return targetNodeId; }
    public TraversalMode traversalMode() { return traversalMode; }

    public OptionalInt routingClass() {
        return hasRoutingClass ? OptionalInt.of(routingClass) : OptionalInt.empty();
    }

    public void setRoutingClass(int rc) {
        if (rc < 1 || rc > 5) {
            throw new IllegalArgumentException("Routing class must be 1-5, got: " + rc);
        }
        this.routingClass = rc;
        this.hasRoutingClass = true;
    }

    public void clearRoutingClass() {
        this.hasRoutingClass = false;
    }
}
