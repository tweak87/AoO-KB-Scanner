package com.tweak87.aookbscanner.review;

import com.tweak87.aookbscanner.model.Models.Side;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class FieldKeysTest {
    @Test public void keepsParticipantStableAcrossNameCorrectionsWhenCoordinatesExist() {
        assertEquals("x634y641", FieldKeys.owner("STU", "OCR-Fehler", 634, 641));
        assertEquals("x634y641", FieldKeys.owner("STU", "DeadRightHand", 634, 641));
    }

    @Test public void resolvesCurrentOwnerAndVisualSignature() {
        String key = FieldKeys.unit(Side.ATTACKER, FieldKeys.CURRENT_OWNER,
                0x1234L, "kills");
        key = FieldKeys.replaceCurrentOwner(key, "x552y558");
        key = FieldKeys.replaceUnitSignature(key, 0x1234L,
                "000000000000ABCD0000000000000022");
        assertEquals("unit|ATTACKER|x552y558|000000000000ABCD|kills", key);
        assertFalse(key.contains(FieldKeys.CURRENT_OWNER));
    }

    @Test public void normalizesFormattedValuesForComparison() {
        assertEquals(FieldKeys.normalizeValue("1,403,171"), FieldKeys.normalizeValue("1403171"));
        assertEquals(FieldKeys.normalizeValue("422.9%"), FieldKeys.normalizeValue("422,9 %"));
    }
}
