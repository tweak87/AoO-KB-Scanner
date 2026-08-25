package com.tweak87.aookbscanner.ocr;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.List;

/** Canonical German labels used to merge slightly different OCR readings of one status field. */
public final class BonusCatalog {
    private static final List<String> LABELS = Arrays.asList(
            "Nahkampfleben",
            "Mittelkampfleben",
            "Fernkampfleben",
            "Nahkampfangriff",
            "Mittelkampfangriff",
            "Fernkampfangriff",
            "Nahkampfverteidigung",
            "Mittelkampfverteidigung",
            "Fernkampfverteidigung",
            "Schaden gegen Nahkampf-Truppen erhöht",
            "Schaden gegen Mittelkampf-Truppen erhöht",
            "Schaden gegen Fernkampf-Truppen erhöht",
            "Durch Nahkampf-Truppen erlittener Schaden verringert",
            "Durch Mittelkampf-Truppen erlittener Schaden verringert",
            "Durch Fernkampf-Truppen erlittener Schaden verringert",
            "Angriff der Biochemie-Zombies erhöht",
            "Angriff gegen Biochemie-Zombies erhöht",
            "Leben der Biochemie-Zombies erhöht",
            "Verteidigung der Biochemie-Zombies erhöht",
            "Blocken aller Truppenarten",
            "Störresistenz aller Truppen",
            "Fehlzündung-Resistenz aller Truppen",
            "Angriff gegen Nahkampf erhöht",
            "Angriff gegen Mittelkampf erhöht",
            "Angriff gegen Fernkampf erhöht",
            "Verteidigung im Nahkampf erhöht",
            "Verteidigung im Mittelkampf erhöht",
            "Verteidigung im Fernkampf erhöht",
            "Feindlicher Nahkampfangriff verringert",
            "Feindlicher Mittelkampfangriff verringert",
            "Feindlicher Fernkampfangriff verringert",
            "Angriff von feindlichen Biochemie-Zombies verringert",
            "Nahkampfverteidigung des Feindes verringert",
            "Mittelkampfverteidigung des Feindes verringert",
            "Fernkampfverteidigung des Feindes verringert",
            "Verteidigung von Biochemie-Zombies des Feindes verringert",
            "Truppen-Angriff-Zusatzsteigerung",
            "Schadenssteigerung gegen Titanen",
            "Verringerung von Titanschaden",
            "Truppen-Leben-Zusatzerhöhung",
            "Verringerter Schaden von Nahkampftruppen durch Nah-, Mittel- und Fernkampftruppen"
    );

    private BonusCatalog() {}

    public static String canonicalize(String raw) {
        String known = matchKnown(raw);
        return known == null ? (raw == null ? "" : raw.trim()) : known;
    }

    public static List<String> labels() {
        return Collections.unmodifiableList(LABELS);
    }

    public static String matchKnown(String raw, Map<String, String> configuredAliases) {
        String normalized = TextNormalization.normalize(raw);
        if (configuredAliases != null) {
            String exact = configuredAliases.get(normalized);
            if (exact != null) return exact;
            String compactCandidate = compact(raw);
            for (Map.Entry<String, String> alias : configuredAliases.entrySet()) {
                String compactAlias = compact(alias.getKey());
                if (!compactAlias.isEmpty() && (compactCandidate.contains(compactAlias) ||
                        compactAlias.contains(compactCandidate))) return alias.getValue();
            }
        }
        return matchKnown(raw);
    }

    public static String matchKnown(String raw) {
        String candidate = compact(raw);
        if (candidate.length() < 4) return null;
        String best = null;
        double bestScore = 0;
        for (String label : LABELS) {
            String expected = compact(label);
            if (candidate.equals(expected) || candidate.contains(expected) || expected.contains(candidate)) return label;
            int distance = editDistance(candidate, expected);
            double score = 1.0 - distance / (double) Math.max(candidate.length(), expected.length());
            if (score > bestScore) {
                bestScore = score;
                best = label;
            }
        }
        return bestScore >= 0.58 ? best : null;
    }

    private static String compact(String value) {
        return TextNormalization.normalize(value).replaceAll("[^a-z]", "");
    }

    private static int editDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1));
            }
            previous = current;
        }
        return previous[right.length()];
    }
}
