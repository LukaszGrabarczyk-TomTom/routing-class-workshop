package com.tomtom.routing.model;

import java.util.*;

public class RoadGraphBuilder {

    private final Map<Long, double[]> nodeCoords = new HashMap<>();
    private final List<RawWay> rawWays = new ArrayList<>();

    public void addNode(long id, double lat, double lon) {
        nodeCoords.put(id, new double[]{lon, lat});
    }

    public void addWay(long id, long[] nodeRefs, Map<String, String> tags) {
        rawWays.add(new RawWay(id, nodeRefs, tags));
    }

    public RoadGraph build() {
        Set<Long> intersectionNodes = findIntersectionNodes();
        Map<Long, RoadNode> nodes = new HashMap<>();
        List<RoadEdge> edges = new ArrayList<>();

        for (RawWay way : rawWays) {
            List<long[]> segments = splitAtIntersections(way.nodeRefs, intersectionNodes);

            for (int s = 0; s < segments.size(); s++) {
                long[] segNodeRefs = segments.get(s);
                long fromId = segNodeRefs[0];
                long toId = segNodeRefs[segNodeRefs.length - 1];

                List<double[]> geometry = buildGeometry(segNodeRefs);
                double length = computeLength(geometry);

                ensureNode(nodes, fromId);
                ensureNode(nodes, toId);

                boolean isOneway = isOneway(way.tags);
                boolean isReverse = "-1".equals(way.tags.get("oneway"));

                if (isOneway && isReverse) {
                    RoadEdge backward = createEdge(way, s, toId, fromId, reverseGeometry(geometry), length);
                    extractExistingRc(backward, way.tags);
                    edges.add(backward);
                    nodes.get(toId).addOutgoingEdge(backward);
                } else if (isOneway) {
                    RoadEdge forward = createEdge(way, s, fromId, toId, geometry, length);
                    extractExistingRc(forward, way.tags);
                    edges.add(forward);
                    nodes.get(fromId).addOutgoingEdge(forward);
                } else {
                    RoadEdge forward = createEdge(way, s, fromId, toId, geometry, length);
                    extractExistingRc(forward, way.tags);
                    edges.add(forward);
                    nodes.get(fromId).addOutgoingEdge(forward);

                    RoadEdge backward = createEdge(way, s, toId, fromId, reverseGeometry(geometry), length);
                    extractExistingRc(backward, way.tags);
                    edges.add(backward);
                    nodes.get(toId).addOutgoingEdge(backward);
                }
            }
        }

        return new RoadGraph(nodes, edges);
    }

    private Set<Long> findIntersectionNodes() {
        Map<Long, Integer> refCounts = new HashMap<>();
        Set<Long> endpoints = new HashSet<>();

        for (RawWay way : rawWays) {
            endpoints.add(way.nodeRefs[0]);
            endpoints.add(way.nodeRefs[way.nodeRefs.length - 1]);
            for (long ref : way.nodeRefs) {
                refCounts.merge(ref, 1, Integer::sum);
            }
        }

        Set<Long> intersections = new HashSet<>(endpoints);
        for (Map.Entry<Long, Integer> entry : refCounts.entrySet()) {
            if (entry.getValue() > 1) {
                intersections.add(entry.getKey());
            }
        }
        return intersections;
    }

    private List<long[]> splitAtIntersections(long[] nodeRefs, Set<Long> intersections) {
        List<long[]> segments = new ArrayList<>();
        int start = 0;

        for (int i = 1; i < nodeRefs.length; i++) {
            if (intersections.contains(nodeRefs[i]) || i == nodeRefs.length - 1) {
                long[] segment = Arrays.copyOfRange(nodeRefs, start, i + 1);
                segments.add(segment);
                start = i;
            }
        }
        return segments;
    }

    private List<double[]> buildGeometry(long[] segNodeRefs) {
        List<double[]> geom = new ArrayList<>();
        for (long ref : segNodeRefs) {
            double[] coord = nodeCoords.get(ref);
            if (coord != null) {
                geom.add(coord);
            }
        }
        return geom;
    }

    private List<double[]> reverseGeometry(List<double[]> geometry) {
        List<double[]> reversed = new ArrayList<>(geometry);
        Collections.reverse(reversed);
        return reversed;
    }

    private double computeLength(List<double[]> geometry) {
        double totalMeters = 0;
        for (int i = 1; i < geometry.size(); i++) {
            totalMeters += haversineMeters(
                geometry.get(i - 1)[1], geometry.get(i - 1)[0],
                geometry.get(i)[1], geometry.get(i)[0]
            );
        }
        return totalMeters;
    }

    static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double R = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    private RoadEdge createEdge(RawWay way, int segIndex, long fromId, long toId,
                                List<double[]> geometry, double length) {
        String edgeId = way.id + "_" + segIndex;
        return new RoadEdge(edgeId, way.id, fromId, toId, geometry,
                            new HashMap<>(way.tags), length);
    }

    private void extractExistingRc(RoadEdge edge, Map<String, String> tags) {
        String rcStr = tags.get("routing_class");
        if (rcStr != null) {
            try {
                edge.setExistingRc(RoutingClass.fromValue(Integer.parseInt(rcStr)));
            } catch (IllegalArgumentException e) {
                // Ignore invalid RC values
            }
        }
    }

    private boolean isOneway(Map<String, String> tags) {
        if ("yes".equals(tags.get("oneway")) || "-1".equals(tags.get("oneway"))) return true;
        if ("roundabout".equals(tags.get("junction")) || "circular".equals(tags.get("junction"))) return true;
        return false;
    }

    private void ensureNode(Map<Long, RoadNode> nodes, long id) {
        if (!nodes.containsKey(id)) {
            double[] coord = nodeCoords.getOrDefault(id, new double[]{0, 0});
            nodes.put(id, new RoadNode(id, coord[1], coord[0]));
        }
    }

    private record RawWay(long id, long[] nodeRefs, Map<String, String> tags) {}
}
