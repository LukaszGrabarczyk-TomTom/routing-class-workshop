package com.tomtom.routing.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RoadGraph {
    private final Map<Long, RoadNode> nodes;
    private final List<RoadEdge> edges;

    public RoadGraph(Map<Long, RoadNode> nodes, List<RoadEdge> edges) {
        this.nodes = Collections.unmodifiableMap(new HashMap<>(nodes));
        this.edges = Collections.unmodifiableList(new ArrayList<>(edges));
    }

    public RoadNode getNode(long id) { return nodes.get(id); }
    public Map<Long, RoadNode> getNodes() { return nodes; }
    public List<RoadEdge> getEdges() { return edges; }
    public int nodeCount() { return nodes.size(); }
    public int edgeCount() { return edges.size(); }
}
