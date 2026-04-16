package com.tomtom.routing.model;

public record RcChange(String edgeId, int oldRc, int newRc, Reason reason, String context) {

    public enum Reason {
        UPGRADE,
        DOWNGRADE
    }
}
