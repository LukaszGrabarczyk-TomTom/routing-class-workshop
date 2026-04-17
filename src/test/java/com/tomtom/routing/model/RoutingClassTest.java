package com.tomtom.routing.model;

import org.junit.Test;
import static org.junit.Assert.*;

public class RoutingClassTest {

    @Test
    public void valuesAreOneToFive() {
        assertEquals(1, RoutingClass.RC1.value());
        assertEquals(2, RoutingClass.RC2.value());
        assertEquals(3, RoutingClass.RC3.value());
        assertEquals(4, RoutingClass.RC4.value());
        assertEquals(5, RoutingClass.RC5.value());
    }

    @Test
    public void fromValueReturnsCorrectEnum() {
        assertEquals(RoutingClass.RC1, RoutingClass.fromValue(1));
        assertEquals(RoutingClass.RC5, RoutingClass.fromValue(5));
    }

    @Test(expected = IllegalArgumentException.class)
    public void fromValueThrowsForInvalidValue() {
        RoutingClass.fromValue(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void fromValueThrowsForSix() {
        RoutingClass.fromValue(6);
    }

    @Test
    public void promoteReducesValue() {
        assertEquals(RoutingClass.RC1, RoutingClass.RC2.promote());
    }

    @Test
    public void promoteAtRC1ReturnsRC1() {
        assertEquals(RoutingClass.RC1, RoutingClass.RC1.promote());
    }

    @Test
    public void demoteIncreasesValue() {
        assertEquals(RoutingClass.RC5, RoutingClass.RC4.demote());
    }

    @Test
    public void demoteAtRC5ReturnsRC5() {
        assertEquals(RoutingClass.RC5, RoutingClass.RC5.demote());
    }
}
