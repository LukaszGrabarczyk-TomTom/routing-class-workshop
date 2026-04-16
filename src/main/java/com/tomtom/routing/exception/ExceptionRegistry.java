package com.tomtom.routing.exception;

import java.util.*;

public class ExceptionRegistry {

    private final Map<String, String> exceptions;

    public ExceptionRegistry(Map<String, String> exceptions) {
        this.exceptions = Map.copyOf(exceptions);
    }

    public ExceptionRegistry() {
        this.exceptions = Map.of();
    }

    public boolean isException(String edgeId) {
        return exceptions.containsKey(edgeId);
    }

    public Optional<String> justification(String edgeId) {
        return Optional.ofNullable(exceptions.get(edgeId));
    }

    public int size() {
        return exceptions.size();
    }
}
