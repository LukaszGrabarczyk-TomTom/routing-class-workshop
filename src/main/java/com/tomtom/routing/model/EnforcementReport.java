package com.tomtom.routing.model;

import java.util.*;

public class EnforcementReport {

    public record UnresolvableIsland(int rcLevel, List<String> edgeIds) {}
    public record ExceptionHit(String edgeId, String justification) {}

    private final List<RcChange> changes = new ArrayList<>();
    private final Map<Integer, int[]> componentCounts = new LinkedHashMap<>();
    private final List<UnresolvableIsland> unresolvableIslands = new ArrayList<>();
    private final List<ExceptionHit> exceptionHits = new ArrayList<>();

    public void addChange(RcChange change) {
        changes.add(change);
    }

    public List<RcChange> changes() {
        return Collections.unmodifiableList(changes);
    }

    public int totalUpgrades() {
        return (int) changes.stream().filter(c -> c.reason() == RcChange.Reason.UPGRADE).count();
    }

    public int totalDowngrades() {
        return (int) changes.stream().filter(c -> c.reason() == RcChange.Reason.DOWNGRADE).count();
    }

    public void recordComponentCount(int rcLevel, int before, int after) {
        componentCounts.put(rcLevel, new int[]{before, after});
    }

    public int componentCountBefore(int rcLevel) {
        return componentCounts.getOrDefault(rcLevel, new int[]{0, 0})[0];
    }

    public int componentCountAfter(int rcLevel) {
        return componentCounts.getOrDefault(rcLevel, new int[]{0, 0})[1];
    }

    public Map<Integer, int[]> componentCounts() {
        return Collections.unmodifiableMap(componentCounts);
    }

    public void addUnresolvableIsland(int rcLevel, List<String> edgeIds) {
        unresolvableIslands.add(new UnresolvableIsland(rcLevel, List.copyOf(edgeIds)));
    }

    public List<UnresolvableIsland> unresolvableIslands() {
        return Collections.unmodifiableList(unresolvableIslands);
    }

    public void addExceptionHit(String edgeId, String justification) {
        exceptionHits.add(new ExceptionHit(edgeId, justification));
    }

    public List<ExceptionHit> exceptionHits() {
        return Collections.unmodifiableList(exceptionHits);
    }
}
