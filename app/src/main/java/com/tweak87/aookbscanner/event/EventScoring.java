package com.tweak87.aookbscanner.event;

import java.util.Locale;

/** Pure Battle-Frenzy point matrix. The score is applied per wounded or fallen unit. */
public final class EventScoring {
    public static final String TYPE_STANDARD = "STANDARD";
    public static final String TYPE_TITAN = "TITAN";
    public static final String TYPE_WARPLANE = "WARPLANE";
    public static final String TYPE_NONE = "NONE";

    private static final int[] ATTACKER = {0, 0, 0, 0, 0, 2, 4, 7, 14, 24, 39, 50, 65, 80};
    private static final int[] DEFENDER = {0, 0, 0, 0, 0, 1, 2, 3, 5, 8, 13, 16, 20, 25};

    private EventScoring() {}

    public static int pointsPerUnit(String eventType, String tier, boolean attackerScore) {
        String type = eventType == null ? TYPE_STANDARD : eventType.toUpperCase(Locale.ROOT);
        if (TYPE_TITAN.equals(type) || TYPE_WARPLANE.equals(type)) return attackerScore ? 6000 : 2000;
        if (TYPE_NONE.equals(type)) return 0;
        int level = parseTier(tier);
        if (level < 1 || level > 13) return 0;
        return attackerScore ? ATTACKER[level] : DEFENDER[level];
    }

    public static long score(long wounded, long fallen, String eventType,
                             String tier, boolean attackerScore) {
        long casualties = Math.max(0, wounded) + Math.max(0, fallen);
        return casualties * pointsPerUnit(eventType, tier, attackerScore);
    }

    /** The game awards half points on resource fields; odd totals are rounded down. */
    public static long resourceFieldAdjustment(long points, boolean resourceField) {
        return resourceField ? points / 2L : points;
    }

    public static int parseTier(String raw) {
        if (raw == null) return -1;
        String value = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^IVX0-9]", "");
        if (value.matches("\\d+")) {
            try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return -1; }
        }
        int total = 0;
        int previous = 0;
        for (int i = value.length() - 1; i >= 0; i--) {
            int current = roman(value.charAt(i));
            if (current < previous) total -= current; else { total += current; previous = current; }
        }
        return total == 0 ? -1 : total;
    }

    private static int roman(char value) {
        if (value == 'I') return 1;
        if (value == 'V') return 5;
        if (value == 'X') return 10;
        return 0;
    }
}
