package com.tomtom.routing.io;

import com.tomtom.routing.model.RoadGraph;
import com.tomtom.routing.model.RoadGraphBuilder;
import de.topobyte.osm4j.core.access.OsmIterator;
import de.topobyte.osm4j.core.model.iface.*;
import de.topobyte.osm4j.core.model.util.OsmModelUtil;
import de.topobyte.osm4j.pbf.seq.PbfIterator;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public class PbfReader {

    private static final Set<String> EXCLUDED_HIGHWAY_TYPES = Set.of(
        "footway", "steps", "path", "cycleway", "pedestrian"
    );

    private static final Set<String> EXCLUDED_NAVIGABILITY = Set.of(
        "closed", "prohibited"
    );

    public RoadGraph read(String pbfPath) throws IOException {
        RoadGraphBuilder builder = new RoadGraphBuilder();

        // First pass: collect ways and their node references
        Set<Long> neededNodeIds = new HashSet<>();

        try (InputStream input = new FileInputStream(pbfPath)) {
            OsmIterator iterator = new PbfIterator(input, true);
            for (EntityContainer container : iterator) {
                if (container.getType() == EntityType.Way) {
                    OsmWay way = (OsmWay) container.getEntity();
                    Map<String, String> tags = OsmModelUtil.getTagsAsMap(way);

                    if (!isRoadOrFerry(tags)) continue;
                    if (isExcluded(tags)) continue;

                    long[] nodeRefs = new long[way.getNumberOfNodes()];
                    for (int i = 0; i < way.getNumberOfNodes(); i++) {
                        nodeRefs[i] = way.getNodeId(i);
                        neededNodeIds.add(nodeRefs[i]);
                    }
                    builder.addWay(way.getId(), nodeRefs, tags);
                }
            }
        }

        // Second pass: collect node coordinates
        try (InputStream input = new FileInputStream(pbfPath)) {
            OsmIterator iterator = new PbfIterator(input, true);
            for (EntityContainer container : iterator) {
                if (container.getType() == EntityType.Node) {
                    OsmNode node = (OsmNode) container.getEntity();
                    if (neededNodeIds.contains(node.getId())) {
                        builder.addNode(node.getId(), node.getLatitude(), node.getLongitude());
                    }
                }
            }
        }

        return builder.build();
    }

    private boolean isRoadOrFerry(Map<String, String> tags) {
        return tags.containsKey("highway") || tags.containsKey("ferry");
    }

    private boolean isExcluded(Map<String, String> tags) {
        String highway = tags.get("highway");
        if (highway != null && EXCLUDED_HIGHWAY_TYPES.contains(highway)) return true;

        String navigability = tags.get("navigability");
        if (navigability != null && EXCLUDED_NAVIGABILITY.contains(navigability)) return true;

        return false;
    }
}
