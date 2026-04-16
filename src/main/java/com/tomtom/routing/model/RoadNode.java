package com.tomtom.routing.model;

import java.util.ArrayList;
import java.util.List;

public class RoadNode {
    private final long id;
    private final double lat;
    private final double lon;
    private final List<RoadEdge> outgoingEdges = new ArrayList<>();

    public RoadNode(long id, double lat, double lon) {
        this.id = id;
        this.lat = lat;
        this.lon = lon;
    }

    public long getId() { return id; }
    public double getLat() { return lat; }
    public double getLon() { return lon; }
    public List<RoadEdge> getOutgoingEdges() { return outgoingEdges; }

    public void addOutgoingEdge(RoadEdge edge) {
        outgoingEdges.add(edge);
    }
}
