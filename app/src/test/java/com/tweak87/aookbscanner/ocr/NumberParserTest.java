package com.tweak87.aookbscanner.ocr;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class NumberParserTest {
    @Test public void parsesGermanGameIntegers() {
        assertEquals(Long.valueOf(1403171), NumberParser.parseLong("1,403,171"));
        assertEquals(Long.valueOf(-8497007), NumberParser.parseLong("-8497007"));
        assertEquals(Long.valueOf(0), NumberParser.parseLong("0"));
    }

    @Test public void extractsLastValueFromLabel() {
        assertEquals("422.9%", NumberParser.findLastNumber("Nahkampfleben 422.9%"));
        assertEquals("65.6 (6.2%)", NumberParser.findLastNumber("Blocken aller Truppenarten 65.6 (6.2%)"));
    }

    @Test public void parsesPrimaryBonusValue() {
        assertEquals(422.9, NumberParser.parsePrimaryDecimal("422.9%"), 0.0001);
        assertEquals(65.6, NumberParser.parsePrimaryDecimal("65.6 (6.2%)"), 0.0001);
        assertNull(NumberParser.parsePrimaryDecimal("—"));
    }

    @Test public void recognizesTableNumbers() {
        assertTrue(NumberParser.isNumericToken("209,505"));
        assertTrue(NumberParser.isNumericToken("0"));
    }
}
