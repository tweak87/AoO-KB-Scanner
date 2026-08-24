package com.tweak87.aookbscanner.ocr;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NumberParser {
    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d[\\d.,]*(?:\\s*\\([^)]*%\\))?%?");

    private NumberParser() {}

    public static String findLastNumber(String text) {
        if (text == null) return null;
        Matcher matcher = NUMBER.matcher(text.replace('\u00a0', ' '));
        String found = null;
        while (matcher.find()) found = matcher.group().trim();
        return found;
    }

    public static Long parseLong(String raw) {
        if (raw == null) return null;
        String cleaned = raw.trim();
        boolean negative = cleaned.startsWith("-");
        cleaned = cleaned.replaceAll("[^0-9]", "");
        if (cleaned.isEmpty()) return null;
        try {
            long value = Long.parseLong(cleaned);
            return negative ? -value : value;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static Double parsePrimaryDecimal(String raw) {
        if (raw == null) return null;
        Matcher matcher = Pattern.compile("[-+]?\\d[\\d.,]*").matcher(raw);
        if (!matcher.find()) return null;
        String value = matcher.group();
        int commas = count(value, ',');
        int dots = count(value, '.');
        if (commas > 0 && dots > 0) {
            char decimal = value.lastIndexOf(',') > value.lastIndexOf('.') ? ',' : '.';
            value = decimal == ',' ? value.replace(".", "").replace(',', '.') : value.replace(",", "");
        } else if (commas == 1 && digitsAfter(value, ',') <= 2) {
            value = value.replace(',', '.');
        } else if (commas > 0) {
            value = value.replace(",", "");
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static boolean isNumericToken(String text) {
        if (text == null) return false;
        String compact = text.trim().replace(" ", "");
        return compact.matches("[-+]?\\d[\\d.,]*(%|\\([^)]*%\\))?");
    }

    public static String normalizedInteger(Long value) {
        return value == null ? "—" : String.format(Locale.ROOT, "%d", value);
    }

    private static int count(String value, char needle) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) == needle) count++;
        return count;
    }

    private static int digitsAfter(String value, char separator) {
        int index = value.lastIndexOf(separator);
        return index < 0 ? 0 : value.length() - index - 1;
    }
}
