package com.tweak87.aookbscanner.event;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class EventScoringTest {
    @Test public void usesBattleFrenzyMatrix() {
        assertEquals(0, EventScoring.pointsPerUnit(EventScoring.TYPE_STANDARD, "IV", true));
        assertEquals(39, EventScoring.pointsPerUnit(EventScoring.TYPE_STANDARD, "X", true));
        assertEquals(13, EventScoring.pointsPerUnit(EventScoring.TYPE_STANDARD, "10", false));
        assertEquals(80, EventScoring.pointsPerUnit(EventScoring.TYPE_STANDARD, "XIII", true));
    }

    @Test public void handlesTitanAndWarplane() {
        assertEquals(6000, EventScoring.pointsPerUnit(EventScoring.TYPE_TITAN, "?", true));
        assertEquals(2000, EventScoring.pointsPerUnit(EventScoring.TYPE_WARPLANE, "?", false));
    }

    @Test public void scoresCasualtiesAndResourceFields() {
        assertEquals(780, EventScoring.score(10, 10, EventScoring.TYPE_STANDARD, "X", true));
        assertEquals(390, EventScoring.resourceFieldAdjustment(780, true));
        assertEquals(3, EventScoring.resourceFieldAdjustment(7, true));
    }

    @Test public void normalizesRomanTierConfiguration() {
        assertEquals("XIII", EventScoring.normalizedTier("T XIII"));
        assertEquals("IX", EventScoring.normalizedTier("9"));
        assertEquals("?", EventScoring.normalizedTier("unbekannt"));
    }
}
