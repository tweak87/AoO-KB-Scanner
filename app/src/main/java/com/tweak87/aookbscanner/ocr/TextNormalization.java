package com.tweak87.aookbscanner.ocr;

import java.text.Normalizer;
import java.util.Locale;

public final class TextNormalization {
    private TextNormalization() {}

    public static String normalize(String value) {
        if (value == null) return "";
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.GERMAN)
                .replace('0', 'o');
        return decomposed.replaceAll("[^a-z0-9%]+", " ").trim().replaceAll("\\s+", " ");
    }

    public static String key(String value) {
        return normalize(value).replace(" ", "_");
    }

    public static boolean contains(String haystack, String needle) {
        return normalize(haystack).contains(normalize(needle));
    }
}
