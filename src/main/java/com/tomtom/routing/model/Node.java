package com.tomtom.routing.model;

import java.util.Objects;

public record Node(String id) {
    public Node {
        Objects.requireNonNull(id, "Node id must not be null");
    }
}
