package com.tomtom.routing.algorithm;

import com.tomtom.routing.model.RoadEdge;
import com.tomtom.routing.model.RoadGraph;
import com.tomtom.routing.model.RoadNode;
import com.tomtom.routing.model.RoutingClass;

public class AttributeSeeder {

    public void seed(RoadGraph graph) {
        for (RoadEdge edge : graph.getEdges()) {
            edge.setComputedRc(computeSeed(edge));
        }
    }

    /**
     * Seed ferries after all road edges have been seeded.
     * Ferry RC = max (lowest value) RC of roads connected at its endpoints.
     */
    public void seedFerries(RoadGraph graph) {
        for (RoadEdge edge : graph.getEdges()) {
            if (edge.hasAttribute("ferry") && edge.getAttribute("highway") == null) {
                RoutingClass best = RoutingClass.RC5;
                RoadNode fromNode = graph.getNode(edge.getFromNodeId());
                RoadNode toNode = graph.getNode(edge.getToNodeId());
                if (fromNode != null) {
                    for (RoadEdge neighbor : fromNode.getOutgoingEdges()) {
                        if (neighbor != edge && neighbor.getComputedRc() != null
                            && neighbor.getComputedRc().value() < best.value()) {
                            best = neighbor.getComputedRc();
                        }
                    }
                }
                if (toNode != null) {
                    for (RoadEdge neighbor : toNode.getOutgoingEdges()) {
                        if (neighbor != edge && neighbor.getComputedRc() != null
                            && neighbor.getComputedRc().value() < best.value()) {
                            best = neighbor.getComputedRc();
                        }
                    }
                }
                edge.setComputedRc(best);
            }
        }
    }

    private RoutingClass computeSeed(RoadEdge edge) {
        String highway = edge.getAttribute("highway");
        if (highway == null) {
            return RoutingClass.RC5; // ferries resolved in seedFerries() after all roads seeded
        }

        return switch (highway) {
            case "motorway" -> hasControlledAccess(edge) ? RoutingClass.RC1 : RoutingClass.RC2;
            case "trunk" -> hasTrunkImportance(edge) ? RoutingClass.RC2 : RoutingClass.RC3;
            case "primary" -> computePrimarySeed(edge);
            case "secondary" -> hasDualCarriageway(edge) ? RoutingClass.RC3 : RoutingClass.RC4;
            case "tertiary" -> RoutingClass.RC4;
            case "motorway_link", "trunk_link", "primary_link" -> RoutingClass.RC4;
            default -> RoutingClass.RC5;
        };
    }

    private RoutingClass computePrimarySeed(RoadEdge edge) {
        if (edge.hasAttribute("int_ref")) return RoutingClass.RC2;
        if (hasDualCarriageway(edge)) return RoutingClass.RC3;
        return RoutingClass.RC3;
    }

    private boolean hasControlledAccess(RoadEdge edge) {
        return "yes".equals(edge.getAttribute("controlled_access"));
    }

    private boolean hasTrunkImportance(RoadEdge edge) {
        return hasControlledAccess(edge) || hasDualCarriageway(edge);
    }

    private boolean hasDualCarriageway(RoadEdge edge) {
        return "yes".equals(edge.getAttribute("dual_carriageway"));
    }
}
