package com.tomtom.routing.model;

public enum RoutingClass {
    RC1(1), RC2(2), RC3(3), RC4(4), RC5(5);

    private final int value;

    RoutingClass(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public static RoutingClass fromValue(int value) {
        for (RoutingClass rc : values()) {
            if (rc.value == value) {
                return rc;
            }
        }
        throw new IllegalArgumentException("Invalid routing class value: " + value);
    }

    public RoutingClass promote() {
        if (this == RC1) return RC1;
        return fromValue(value - 1);
    }

    public RoutingClass demote() {
        if (this == RC5) return RC5;
        return fromValue(value + 1);
    }
}
