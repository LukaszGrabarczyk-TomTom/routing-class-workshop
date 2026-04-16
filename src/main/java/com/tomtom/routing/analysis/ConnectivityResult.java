package com.tomtom.routing.analysis;

import java.util.List;
import java.util.Set;

public record ConnectivityResult(
    int rcLevel,
    Set<String> mainComponent,
    List<Set<String>> islands
) {
    public int totalComponents() {
        return 1 + islands.size();
    }

    public boolean isConnected() {
        return islands.isEmpty();
    }
}
