package com.tweak87.aookbscanner.ocr;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class BonusCatalogTest {
    @Test public void normalizesCommonOcrVariations() {
        assertEquals("Nahkampfleben", BonusCatalog.matchKnown("Nahkampf leben"));
        assertEquals("Truppen-Leben-Zusatzerhöhung",
                BonusCatalog.matchKnown("Truppen Leben Zusatzerhohung"));
        assertEquals("Feindlicher Fernkampfangriff verringert",
                BonusCatalog.matchKnown("Feindlicher Fernkampfangrif verringert"));
    }
}
