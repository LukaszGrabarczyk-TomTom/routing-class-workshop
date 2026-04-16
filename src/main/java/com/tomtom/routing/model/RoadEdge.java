package com.tomtom.routing.model;

import java.util.List;
import java.util.Map;

public class RoadEdge {
    private final String id;
    private final long parentWayId;
    private final long fromNodeId;
    private final long toNodeId;
    private final List<double[]> geometry; // list of [lon, lat]
    private final Map<String, String> attributes;
    private final double lengthMeters;
    private RoutingClass computedRc;
    private RoutingClass existingRc;

    public RoadEdge(String id, long parentWayId, long fromNodeId, long toNodeId,
                    List<double[]> geometry, Map<String, String> attributes, double lengthMeters) {
        this.id = id;
        this.parentWayId = parentWayId;
        this.fromNodeId = fromNodeId;
        this.toNodeId = toNodeId;
        this.geometry = geometry;
        this.attributes = attributes;
        this.lengthMeters = lengthMeters;
    }

    public String getId() { return id; }
    public long getParentWayId() { return parentWayId; }
    public long getFromNodeId() { return fromNodeId; }
    public long getToNodeId() { return toNodeId; }
    public List<double[]> getGeometry() { return geometry; }
    public Map<String, String> getAttributes() { return attributes; }
    public double getLengthMeters() { return lengthMeters; }

    public String getAttribute(String key) { return attributes.get(key); }
    public boolean hasAttribute(String key) { return attributes.containsKey(key); }

    public RoutingClass getComputedRc() { return computedRc; }
    public void setComputedRc(RoutingClass rc) { this.computedRc = rc; }
    public RoutingClass getExistingRc() { return existingRc; }
    public void setExistingRc(RoutingClass rc) { this.existingRc = rc; }
}
