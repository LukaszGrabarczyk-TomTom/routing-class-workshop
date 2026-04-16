package com.tomtom.routing.model;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class EnforcementReportTest {

    @Test
    public void emptyReportHasZeroCounts() {
        EnforcementReport report = new EnforcementReport();
        assertEquals(0, report.totalUpgrades());
        assertEquals(0, report.totalDowngrades());
        assertTrue(report.changes().isEmpty());
        assertTrue(report.unresolvableIslands().isEmpty());
    }

    @Test
    public void recordChangesAndCounts() {
        EnforcementReport report = new EnforcementReport();
        report.addChange(new RcChange("e1", 3, 1, RcChange.Reason.UPGRADE, "bridge from island-7 to main"));
        report.addChange(new RcChange("e2", 1, 2, RcChange.Reason.DOWNGRADE, "island-3 at RC1, no bridge found"));

        assertEquals(1, report.totalUpgrades());
        assertEquals(1, report.totalDowngrades());
        assertEquals(2, report.changes().size());
    }

    @Test
    public void recordComponentCounts() {
        EnforcementReport report = new EnforcementReport();
        report.recordComponentCount(1, 67, 1);

        assertEquals(67, report.componentCountBefore(1));
        assertEquals(1, report.componentCountAfter(1));
    }

    @Test
    public void recordUnresolvableIslands() {
        EnforcementReport report = new EnforcementReport();
        report.addUnresolvableIsland(5, List.of("e10", "e11"));

        assertEquals(1, report.unresolvableIslands().size());
        assertEquals(List.of("e10", "e11"), report.unresolvableIslands().get(0).edgeIds());
    }

    @Test
    public void recordExceptionHits() {
        EnforcementReport report = new EnforcementReport();
        report.addExceptionHit("e5", "Peninsula dead end");

        assertEquals(1, report.exceptionHits().size());
        assertEquals("e5", report.exceptionHits().get(0).edgeId());
    }
}
