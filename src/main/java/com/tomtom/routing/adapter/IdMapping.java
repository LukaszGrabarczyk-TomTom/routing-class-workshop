package com.tomtom.routing.adapter;

import java.util.Optional;

public interface IdMapping {
    Optional<String> toEdgeId(String productId);
}
