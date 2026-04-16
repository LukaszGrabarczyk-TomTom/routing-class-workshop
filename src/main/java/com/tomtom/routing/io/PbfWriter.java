package com.tomtom.routing.io;

import com.tomtom.routing.model.RoadEdge;
import com.tomtom.routing.model.RoadGraph;
import com.tomtom.routing.model.RoutingClass;
import com.slimjars.dist.gnu.trove.list.array.TLongArrayList;
import de.topobyte.osm4j.core.access.OsmOutputStream;
import de.topobyte.osm4j.core.model.impl.Node;
import de.topobyte.osm4j.core.model.impl.Tag;
import de.topobyte.osm4j.core.model.impl.Way;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;

public class PbfWriter {

    public void write(RoadGraph graph, String outputPath) throws IOException {
        // Aggregate per-segment RC back to per-way (lowest RC value = highest importance)
        Map<Long, RoutingClass> wayRc = aggregateWayRc(graph);

        // Group edges by parent way to reconstruct geometries
        Map<Long, List<RoadEdge>> edgesByWay = new LinkedHashMap<>();
        for (RoadEdge edge : graph.getEdges()) {
            edgesByWay.computeIfAbsent(edge.getParentWayId(), k -> new ArrayList<>()).add(edge);
        }

        // Build nodes and way structures
        Map<Long, double[]> nodeCoords = new LinkedHashMap<>();
        Map<Long, long[]> wayNodeRefMap = new LinkedHashMap<>();
        long nodeIdCounter = 1;

        for (Map.Entry<Long, List<RoadEdge>> entry : edgesByWay.entrySet()) {
            long wayId = entry.getKey();
            if (!wayRc.containsKey(wayId)) continue;

            // Use first forward-direction edge's geometry as representative
            RoadEdge representative = entry.getValue().get(0);
            List<double[]> geom = representative.getGeometry();

            long[] refs = new long[geom.size()];
            for (int i = 0; i < geom.size(); i++) {
                long nid = nodeIdCounter++;
                refs[i] = nid;
                nodeCoords.put(nid, geom.get(i)); // [lon, lat]
            }
            wayNodeRefMap.put(wayId, refs);
        }

        // Write PBF
        try (OutputStream out = new FileOutputStream(outputPath)) {
            OsmOutputStream osmOut = new de.topobyte.osm4j.pbf.seq.PbfWriter(out, true);

            // Write nodes first
            for (Map.Entry<Long, double[]> entry : nodeCoords.entrySet()) {
                double[] coord = entry.getValue(); // [lon, lat]
                osmOut.write(new Node(entry.getKey(), coord[1], coord[0]));
            }

            // Write ways with routing_class tag
            for (Map.Entry<Long, long[]> entry : wayNodeRefMap.entrySet()) {
                long wayId = entry.getKey();
                RoutingClass rc = wayRc.get(wayId);
                if (rc == null) continue;

                TLongArrayList nodeRefs = new TLongArrayList(entry.getValue());
                List<Tag> tags = List.of(new Tag("routing_class", String.valueOf(rc.value())));
                osmOut.write(new Way(wayId, nodeRefs, tags));
            }

            osmOut.complete();
        }
    }

    private Map<Long, RoutingClass> aggregateWayRc(RoadGraph graph) {
        Map<Long, RoutingClass> result = new HashMap<>();
        for (RoadEdge edge : graph.getEdges()) {
            if (edge.getComputedRc() == null) continue;
            result.merge(edge.getParentWayId(), edge.getComputedRc(),
                (existing, incoming) -> existing.value() < incoming.value() ? existing : incoming);
        }
        return result;
    }
}
