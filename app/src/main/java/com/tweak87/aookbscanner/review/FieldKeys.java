package com.tweak87.aookbscanner.review;

import com.tweak87.aookbscanner.model.Models.Side;
import com.tweak87.aookbscanner.ocr.NumberParser;
import com.tweak87.aookbscanner.ocr.TextNormalization;

import java.util.Locale;

/** Stable semantic keys used to compare OCR observations with the editable final report. */
public final class FieldKeys {
    public static final String CURRENT_OWNER = "@";

    private FieldKeys() {}

    public static String report(String field) {
        return "report|" + TextNormalization.key(field);
    }

    public static String participant(Side side, String alliance, String name,
                                     Integer x, Integer y, String field) {
        return participant(side == null ? "UNKNOWN" : side.name(),
                owner(alliance, name, x, y), field);
    }

    public static String participant(String side, String owner, String field) {
        return "participant|" + side + "|" + owner + "|" + TextNormalization.key(field);
    }

    public static String unit(Side side, String owner, long iconHash, String field) {
        return unit(side == null ? "UNKNOWN" : side.name(), owner,
                String.format(Locale.ROOT, "%016X", iconHash), field);
    }

    public static String unit(String side, String owner, String signature, String field) {
        String visual = signature == null ? "unknown" : signature;
        if (visual.length() > 16 && !visual.startsWith("MANUAL-")) visual = visual.substring(0, 16);
        return "unit|" + side + "|" + cleanOwner(owner) + "|" + visual + "|" + TextNormalization.key(field);
    }

    public static String bonus(Side side, String owner, String canonicalLabel) {
        return bonus(side == null ? "UNKNOWN" : side.name(), owner, canonicalLabel);
    }

    public static String bonus(String side, String owner, String canonicalLabel) {
        return "bonus|" + side + "|" + cleanOwner(owner) + "|" + TextNormalization.key(canonicalLabel);
    }

    public static String unmatched(Side side, int x, int y) {
        return "unmatched|" + (side == null ? "UNKNOWN" : side.name()) + "|" + x + "|" + y;
    }

    public static String owner(String alliance, String name, Integer x, Integer y) {
        if (x != null && y != null) return "x" + x + "y" + y;
        String identity = TextNormalization.key(clean(alliance) + " " + clean(name));
        if (!identity.isEmpty()) return identity;
        return "unknown";
    }

    public static String replaceCurrentOwner(String key, String owner) {
        if (key == null || key.isEmpty()) return key;
        return key.replace("|" + CURRENT_OWNER + "|", "|" + cleanOwner(owner) + "|");
    }

    public static String replaceUnitSignature(String key, long rawHash, String signature) {
        if (key == null || signature == null) return key;
        String raw = String.format(Locale.ROOT, "%016X", rawHash);
        String visual = signature.length() > 16 && !signature.startsWith("MANUAL-")
                ? signature.substring(0, 16) : signature;
        return key.replace("|" + raw + "|", "|" + visual + "|");
    }

    public static String value(Long value) {
        return value == null ? "" : Long.toString(value);
    }

    public static String normalizeValue(String raw) {
        if (raw == null || raw.trim().isEmpty() || "—".equals(raw.trim())) return "";
        Double decimal = NumberParser.parsePrimaryDecimal(raw);
        if (raw.contains("%") || raw.contains("(") || raw.matches(".*[.,]\\d{1,2}%?.*")) {
            if (decimal != null) return String.format(Locale.ROOT, "%.4f", decimal);
        }
        Long integer = NumberParser.parseLong(raw);
        if (integer != null) return Long.toString(integer);
        return TextNormalization.normalize(raw);
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String cleanOwner(String value) {
        if (CURRENT_OWNER.equals(value)) return value;
        String normalized = TextNormalization.key(clean(value));
        return normalized.isEmpty() ? "unknown" : normalized;
    }
}
