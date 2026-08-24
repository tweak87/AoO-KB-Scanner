package com.tweak87.aookbscanner.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.tweak87.aookbscanner.event.EventScoring;
import com.tweak87.aookbscanner.model.Models.ParticipantFrame;
import com.tweak87.aookbscanner.model.Models.Side;
import com.tweak87.aookbscanner.model.Models.UnitFrame;

import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class ScannerDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "aoo_scanner.db";
    private static final int DB_VERSION = 2;

    public ScannerDatabase(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE reports (" +
                "id TEXT PRIMARY KEY," +
                "display_id TEXT NOT NULL," +
                "fingerprint TEXT UNIQUE," +
                "battle_timestamp TEXT," +
                "result TEXT," +
                "position_x INTEGER," +
                "position_y INTEGER," +
                "expected_attackers INTEGER NOT NULL DEFAULT 0," +
                "expected_defenders INTEGER NOT NULL DEFAULT 0," +
                "status TEXT NOT NULL DEFAULT 'SCANNT'," +
                "event_mode INTEGER NOT NULL DEFAULT 0," +
                "resource_field INTEGER NOT NULL DEFAULT 0," +
                "attacker_points INTEGER NOT NULL DEFAULT 0," +
                "defender_points INTEGER NOT NULL DEFAULT 0," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL," +
                "completed_at INTEGER)");
        db.execSQL("CREATE TABLE participants (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "report_id TEXT NOT NULL REFERENCES reports(id) ON DELETE CASCADE," +
                "side TEXT NOT NULL," +
                "stable_key TEXT NOT NULL," +
                "alliance_name TEXT," +
                "player_name TEXT," +
                "position_x INTEGER," +
                "position_y INTEGER," +
                "total INTEGER," +
                "power_loss INTEGER," +
                "kills INTEGER," +
                "fallen INTEGER," +
                "survivors INTEGER," +
                "wounded INTEGER," +
                "summary_complete INTEGER NOT NULL DEFAULT 0," +
                "units_seen INTEGER NOT NULL DEFAULT 0," +
                "technology_seen INTEGER NOT NULL DEFAULT 0," +
                "technology_end_seen INTEGER NOT NULL DEFAULT 0," +
                "UNIQUE(report_id, stable_key))");
        db.execSQL("CREATE TABLE unit_types (" +
                "signature TEXT PRIMARY KEY," +
                "display_name TEXT NOT NULL," +
                "category TEXT NOT NULL," +
                "event_type TEXT NOT NULL DEFAULT 'STANDARD'," +
                "representative_png BLOB," +
                "updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE unit_rows (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "participant_id INTEGER NOT NULL REFERENCES participants(id) ON DELETE CASCADE," +
                "signature TEXT NOT NULL REFERENCES unit_types(signature)," +
                "tier TEXT NOT NULL," +
                "survivors INTEGER," +
                "wounded INTEGER," +
                "fallen INTEGER," +
                "kills INTEGER," +
                "UNIQUE(participant_id, signature, tier))");
        db.execSQL("CREATE TABLE bonuses (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "participant_id INTEGER NOT NULL REFERENCES participants(id) ON DELETE CASCADE," +
                "label_key TEXT NOT NULL," +
                "label_raw TEXT NOT NULL," +
                "value_raw TEXT NOT NULL," +
                "primary_value REAL," +
                "UNIQUE(participant_id, label_key))");
        db.execSQL("CREATE INDEX idx_participants_report ON participants(report_id, side)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE reports ADD COLUMN event_mode INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE reports ADD COLUMN resource_field INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE reports ADD COLUMN attacker_points INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE reports ADD COLUMN defender_points INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE reports ADD COLUMN completed_at INTEGER");
            db.execSQL("ALTER TABLE unit_types ADD COLUMN event_type TEXT NOT NULL DEFAULT 'STANDARD'");
        }
    }

    public synchronized void insertReport(String id, String displayId, String fingerprint,
                                          String battleTimestamp, String result,
                                          Integer x, Integer y, int attackers, int defenders,
                                          boolean eventMode, boolean resourceField) {
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("display_id", displayId);
        if (fingerprint == null) values.putNull("fingerprint"); else values.put("fingerprint", fingerprint);
        values.put("battle_timestamp", battleTimestamp);
        values.put("result", result);
        putNullable(values, "position_x", x);
        putNullable(values, "position_y", y);
        values.put("expected_attackers", Math.max(attackers, 0));
        values.put("expected_defenders", Math.max(defenders, 0));
        values.put("status", "SCANNT");
        values.put("event_mode", eventMode ? 1 : 0);
        values.put("resource_field", eventMode && resourceField ? 1 : 0);
        values.put("created_at", now);
        values.put("updated_at", now);
        getWritableDatabase().insertOrThrow("reports", null, values);
    }

    public synchronized void updateReportHeader(String id, String ignoredFingerprint, String timestamp,
                                                String result, Integer x, Integer y,
                                                Integer attackers, Integer defenders) {
        ContentValues values = new ContentValues();
        if (timestamp != null && !timestamp.isEmpty()) values.put("battle_timestamp", timestamp);
        if (result != null && !result.isEmpty()) values.put("result", result);
        putNullable(values, "position_x", x);
        putNullable(values, "position_y", y);
        if (attackers != null && attackers > 0) values.put("expected_attackers", attackers);
        if (defenders != null && defenders > 0) values.put("expected_defenders", defenders);
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("reports", values, "id=?", new String[]{id});
    }

    public synchronized void finalizeReport(String reportId) {
        Progress progress = getProgress(reportId);
        recalculateEventPoints(reportId);
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("status", progress.complete ? "VOLLSTÄNDIG" : "UNVOLLSTÄNDIG");
        values.put("updated_at", now);
        values.put("completed_at", now);
        getWritableDatabase().update("reports", values, "id=?", new String[]{reportId});
    }

    public synchronized long upsertParticipant(String reportId, ParticipantFrame participant) {
        SQLiteDatabase db = getWritableDatabase();
        long id = findParticipantId(db, reportId, participant);
        if (id < 0) {
            ContentValues base = new ContentValues();
            base.put("report_id", reportId);
            base.put("side", participant.side.name());
            base.put("stable_key", participant.stableKey());
            id = db.insertOrThrow("participants", null, base);
        }
        boolean validSummary = participant.isSummaryValid();
        ContentValues values = participantValues(participant, validSummary);
        if (validSummary) values.put("summary_complete", 1);
        db.update("participants", values, "id=?", new String[]{Long.toString(id)});
        touchReport(reportId);
        return id;
    }

    public synchronized long latestParticipantId(String reportId, Side side) {
        try (Cursor cursor = getReadableDatabase().query("participants", new String[]{"id"},
                "report_id=? AND side=?", new String[]{reportId, side.name()},
                null, null, "id DESC", "1")) {
            return cursor.moveToFirst() ? cursor.getLong(0) : -1;
        }
    }

    public synchronized void markProgress(long participantId, boolean unitsSeen,
                                          boolean technologySeen, boolean technologyEndSeen) {
        if (participantId < 0) return;
        ContentValues values = new ContentValues();
        if (unitsSeen) values.put("units_seen", 1);
        if (technologySeen) values.put("technology_seen", 1);
        if (technologyEndSeen) values.put("technology_end_seen", 1);
        if (values.size() > 0) getWritableDatabase().update("participants", values, "id=?",
                new String[]{Long.toString(participantId)});
    }

    public synchronized String ensureUnitType(long hash, byte[] representativePng, String tier) {
        SQLiteDatabase db = getWritableDatabase();
        UnitTypeRow closest = findClosestUnitType(db, hash, 7);
        if (closest != null) return closest.signature;
        String signature = String.format(Locale.ROOT, "%016X", hash);
        int parsedTier = EventScoring.parseTier(tier);
        boolean standard = parsedTier >= 1 && parsedTier <= 13;
        ContentValues values = new ContentValues();
        values.put("signature", signature);
        values.put("display_name", "Unbekannte Einheit " + signature.substring(0, 6));
        values.put("category", standard ? "Unklassifiziert" : "Spezialeinheit");
        values.put("event_type", standard ? EventScoring.TYPE_STANDARD : EventScoring.TYPE_NONE);
        values.put("representative_png", representativePng);
        values.put("updated_at", System.currentTimeMillis());
        db.insertWithOnConflict("unit_types", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        return signature;
    }

    public synchronized void upsertUnit(long participantId, String signature, UnitFrame unit) {
        if (participantId < 0) return;
        ContentValues values = new ContentValues();
        values.put("participant_id", participantId);
        values.put("signature", signature);
        values.put("tier", unit.tier == null ? "?" : unit.tier);
        putNullable(values, "survivors", unit.survivors);
        putNullable(values, "wounded", unit.wounded);
        putNullable(values, "fallen", unit.fallen);
        putNullable(values, "kills", unit.kills);
        getWritableDatabase().insertWithOnConflict("unit_rows", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void upsertBonus(long participantId, String labelKey, String labelRaw,
                                         String valueRaw, Double primaryValue) {
        if (participantId < 0 || labelKey == null || labelKey.isEmpty()) return;
        ContentValues values = new ContentValues();
        values.put("participant_id", participantId);
        values.put("label_key", labelKey);
        values.put("label_raw", labelRaw);
        values.put("value_raw", valueRaw);
        if (primaryValue == null) values.putNull("primary_value"); else values.put("primary_value", primaryValue);
        getWritableDatabase().insertWithOnConflict("bonuses", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized Progress getProgress(String reportId) {
        int expectedA = scalarInt("SELECT expected_attackers FROM reports WHERE id=?", reportId);
        int expectedD = scalarInt("SELECT expected_defenders FROM reports WHERE id=?", reportId);
        int seenA = count("SELECT COUNT(*) FROM participants WHERE report_id=? AND side='ATTACKER'", reportId);
        int seenD = count("SELECT COUNT(*) FROM participants WHERE report_id=? AND side='DEFENDER'", reportId);
        int completeA = count("SELECT COUNT(*) FROM participants WHERE report_id=? AND side='ATTACKER' " +
                "AND summary_complete=1 AND (total=0 OR units_seen=1) AND technology_seen=1 AND technology_end_seen=1", reportId);
        int completeD = count("SELECT COUNT(*) FROM participants WHERE report_id=? AND side='DEFENDER' " +
                "AND summary_complete=1 AND (total=0 OR units_seen=1) AND technology_seen=1 AND technology_end_seen=1", reportId);
        int requiredA = expectedA > 0 ? expectedA : seenA;
        int requiredD = expectedD > 0 ? expectedD : seenD;
        boolean complete = requiredA > 0 && requiredD > 0 && completeA >= requiredA && completeD >= requiredD;
        touchReport(reportId);
        return new Progress(expectedA, expectedD, seenA, seenD, completeA, completeD, complete);
    }

    public synchronized EventPoints recalculateEventPoints(String reportId) {
        boolean eventMode = scalarInt("SELECT event_mode FROM reports WHERE id=?", reportId) == 1;
        boolean resourceField = scalarInt("SELECT resource_field FROM reports WHERE id=?", reportId) == 1;
        long attacker = 0;
        long defender = 0;
        if (eventMode) {
            String sql = "SELECT p.side,COALESCE(u.wounded,0),COALESCE(u.fallen,0),u.tier,t.event_type " +
                    "FROM unit_rows u JOIN participants p ON p.id=u.participant_id " +
                    "JOIN unit_types t ON t.signature=u.signature WHERE p.report_id=?";
            try (Cursor cursor = getReadableDatabase().rawQuery(sql, new String[]{reportId})) {
                while (cursor.moveToNext()) {
                    boolean attackerScore = "DEFENDER".equals(cursor.getString(0));
                    long score = EventScoring.score(cursor.getLong(1), cursor.getLong(2),
                            cursor.getString(4), cursor.getString(3), attackerScore);
                    if (attackerScore) attacker += score; else defender += score;
                }
            }
            attacker = EventScoring.resourceFieldAdjustment(attacker, resourceField);
            defender = EventScoring.resourceFieldAdjustment(defender, resourceField);
        }
        ContentValues values = new ContentValues();
        values.put("attacker_points", attacker);
        values.put("defender_points", defender);
        getWritableDatabase().update("reports", values, "id=?", new String[]{reportId});
        return new EventPoints(eventMode, resourceField, attacker, defender);
    }

    public synchronized EventPoints getEventPoints(String reportId) {
        try (Cursor cursor = getReadableDatabase().query("reports",
                new String[]{"event_mode", "resource_field", "attacker_points", "defender_points"},
                "id=?", new String[]{reportId}, null, null, null)) {
            if (cursor.moveToFirst()) return new EventPoints(cursor.getInt(0) == 1, cursor.getInt(1) == 1,
                    cursor.getLong(2), cursor.getLong(3));
        }
        return new EventPoints(false, false, 0, 0);
    }

    public synchronized List<ReportRow> listReports() {
        List<ReportRow> rows = new ArrayList<>();
        String sql = "SELECT r.id,r.display_id,r.status,r.battle_timestamp,r.result,r.updated_at," +
                "r.expected_attackers,r.expected_defenders,r.event_mode,r.resource_field," +
                "r.attacker_points,r.defender_points," +
                "SUM(CASE WHEN p.side='ATTACKER' THEN 1 ELSE 0 END)," +
                "SUM(CASE WHEN p.side='DEFENDER' THEN 1 ELSE 0 END) " +
                "FROM reports r LEFT JOIN participants p ON p.report_id=r.id " +
                "GROUP BY r.id ORDER BY r.updated_at DESC";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, null)) {
            while (cursor.moveToNext()) rows.add(new ReportRow(cursor.getString(0), cursor.getString(1),
                    cursor.getString(2), cursor.getString(3), cursor.getString(4), cursor.getLong(5),
                    cursor.getInt(6), cursor.getInt(7), cursor.getInt(12), cursor.getInt(13),
                    cursor.getInt(8) == 1, cursor.getInt(9) == 1, cursor.getLong(10), cursor.getLong(11)));
        }
        return rows;
    }

    public synchronized List<UnitTypeRow> listUnitTypes() {
        List<UnitTypeRow> rows = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("unit_types",
                new String[]{"signature", "display_name", "category", "event_type", "representative_png"},
                null, null, null, null, "updated_at DESC")) {
            while (cursor.moveToNext()) rows.add(new UnitTypeRow(cursor.getString(0), cursor.getString(1),
                    cursor.getString(2), cursor.getString(3), cursor.getBlob(4), 0));
        }
        return rows;
    }

    public synchronized void updateUnitType(String signature, String displayName,
                                            String category, String eventType) {
        ContentValues values = new ContentValues();
        values.put("display_name", cleanOr(displayName, "Unbekannte Einheit"));
        values.put("category", cleanOr(category, "Unklassifiziert"));
        values.put("event_type", cleanOr(eventType, EventScoring.TYPE_STANDARD));
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("unit_types", values, "signature=?", new String[]{signature});
        List<String> reportIds = new ArrayList<>();
        String sql = "SELECT DISTINCT p.report_id FROM unit_rows u JOIN participants p ON p.id=u.participant_id " +
                "WHERE u.signature=?";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, new String[]{signature})) {
            while (cursor.moveToNext()) reportIds.add(cursor.getString(0));
        }
        for (String reportId : reportIds) recalculateEventPoints(reportId);
    }

    public synchronized String reportDetails(String reportId) {
        StringBuilder text = new StringBuilder();
        EventPoints points = getEventPoints(reportId);
        try (Cursor report = getReadableDatabase().query("reports", null, "id=?",
                new String[]{reportId}, null, null, null)) {
            if (!report.moveToFirst()) return "Bericht nicht gefunden.";
            text.append("AGE OF ORIGINS – KAMPFBERICHT\n");
            text.append("========================================\n");
            text.append("Berichts-ID: ").append(report.getString(report.getColumnIndexOrThrow("display_id"))).append('\n');
            text.append("Status: ").append(report.getString(report.getColumnIndexOrThrow("status"))).append('\n');
            text.append("Ergebnis: ").append(nullToDash(report.getString(report.getColumnIndexOrThrow("result")))).append('\n');
            text.append("Spielzeit: ").append(nullToDash(report.getString(report.getColumnIndexOrThrow("battle_timestamp")))).append('\n');
            int x = report.getColumnIndexOrThrow("position_x");
            int y = report.getColumnIndexOrThrow("position_y");
            if (!report.isNull(x) && !report.isNull(y)) text.append("Kampfposition: X:")
                    .append(report.getInt(x)).append(" Y:").append(report.getInt(y)).append('\n');
            if (points.eventMode) {
                text.append("\nBATTLE FRENZY\n");
                text.append("Angreifer-Punkte: ").append(number(points.attackerPoints)).append('\n');
                text.append("Verteidiger-Punkte: ").append(number(points.defenderPoints)).append('\n');
                text.append("Ressourcenfeld (50 %): ").append(points.resourceField ? "Ja" : "Nein").append('\n');
            }
            text.append('\n');
        }
        appendSide(text, reportId, Side.ATTACKER);
        appendSide(text, reportId, Side.DEFENDER);
        return text.toString().trim() + "\n";
    }

    public synchronized String reportDisplayId(String reportId) {
        try (Cursor cursor = getReadableDatabase().query("reports", new String[]{"display_id"},
                "id=?", new String[]{reportId}, null, null, null)) {
            return cursor.moveToFirst() ? cursor.getString(0) : "Bericht";
        }
    }

    private void appendSide(StringBuilder text, String reportId, Side side) {
        text.append(side == Side.ATTACKER ? "ANGREIFER" : "VERTEIDIGER").append('\n');
        text.append("----------------------------------------\n");
        try (Cursor participants = getReadableDatabase().query("participants", null,
                "report_id=? AND side=?", new String[]{reportId, side.name()}, null, null, "id")) {
            int index = 0;
            while (participants.moveToNext()) {
                index++;
                long participantId = participants.getLong(participants.getColumnIndexOrThrow("id"));
                text.append(index).append(". ");
                String alliance = participants.getString(participants.getColumnIndexOrThrow("alliance_name"));
                if (alliance != null && !alliance.isEmpty()) text.append('(').append(alliance).append(") ");
                text.append(nullToDash(participants.getString(participants.getColumnIndexOrThrow("player_name")))).append('\n');
                int x = participants.getColumnIndexOrThrow("position_x");
                int y = participants.getColumnIndexOrThrow("position_y");
                if (!participants.isNull(x) && !participants.isNull(y)) text.append("   Position: X:")
                        .append(participants.getInt(x)).append(" Y:").append(participants.getInt(y)).append('\n');
                text.append("   Gesamt: ").append(longValue(participants, "total"))
                        .append(" | Kraftverlust: ").append(longValue(participants, "power_loss")).append('\n');
                text.append("   Getötete Feinde: ").append(longValue(participants, "kills"))
                        .append(" | Gefallene: ").append(longValue(participants, "fallen")).append('\n');
                text.append("   Überlebende: ").append(longValue(participants, "survivors"))
                        .append(" | Verwundete: ").append(longValue(participants, "wounded")).append('\n');
                appendUnits(text, participantId);
                appendBonuses(text, participantId);
                text.append('\n');
            }
            if (index == 0) text.append("Noch keine Teilnehmerdaten erkannt.\n\n");
        }
    }

    private void appendUnits(StringBuilder text, long participantId) {
        String sql = "SELECT t.display_name,t.category,t.event_type,u.tier,u.survivors,u.wounded,u.fallen,u.kills " +
                "FROM unit_rows u JOIN unit_types t ON t.signature=u.signature WHERE u.participant_id=? ORDER BY u.id";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, new String[]{Long.toString(participantId)})) {
            if (cursor.getCount() > 0) text.append("\n   EINHEITEN\n");
            while (cursor.moveToNext()) {
                text.append("   • Stufe ").append(cursor.getString(3)).append(' ').append(cursor.getString(0))
                        .append(" [").append(cursor.getString(1)).append("]");
                if (!EventScoring.TYPE_STANDARD.equals(cursor.getString(2))) text.append(" {BF: ")
                        .append(eventTypeLabel(cursor.getString(2))).append('}');
                text.append('\n');
                text.append("     Überl.: ").append(cursor.isNull(4) ? "—" : number(cursor.getLong(4)))
                        .append(" | Verw.: ").append(cursor.isNull(5) ? "—" : number(cursor.getLong(5)))
                        .append(" | Gef.: ").append(cursor.isNull(6) ? "—" : number(cursor.getLong(6)))
                        .append(" | Kills: ").append(cursor.isNull(7) ? "—" : number(cursor.getLong(7))).append('\n');
            }
        }
    }

    private void appendBonuses(StringBuilder text, long participantId) {
        try (Cursor cursor = getReadableDatabase().query("bonuses",
                new String[]{"label_raw", "value_raw"}, "participant_id=?",
                new String[]{Long.toString(participantId)}, null, null, "label_key")) {
            if (cursor.getCount() > 0) text.append("\n   TECHNOLOGIE-/STATUSWERTE\n");
            while (cursor.moveToNext()) text.append("   • ").append(cursor.getString(0))
                    .append(": ").append(cursor.getString(1)).append('\n');
        }
    }

    private UnitTypeRow findClosestUnitType(SQLiteDatabase db, long hash, int maxDistance) {
        UnitTypeRow best = null;
        int bestDistance = Integer.MAX_VALUE;
        try (Cursor cursor = db.query("unit_types",
                new String[]{"signature", "display_name", "category", "event_type", "representative_png"},
                null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                try {
                    long candidate = Long.parseUnsignedLong(cursor.getString(0), 16);
                    int distance = Long.bitCount(hash ^ candidate);
                    if (distance < bestDistance && distance <= maxDistance) {
                        bestDistance = distance;
                        best = new UnitTypeRow(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                                cursor.getString(3), cursor.getBlob(4), distance);
                    }
                } catch (NumberFormatException ignored) {
                    // Keep scanning valid signatures.
                }
            }
        }
        return best;
    }

    private long findParticipantId(SQLiteDatabase db, String reportId, ParticipantFrame participant) {
        try (Cursor exact = db.query("participants", new String[]{"id"}, "report_id=? AND stable_key=?",
                new String[]{reportId, participant.stableKey()}, null, null, null, "1")) {
            if (exact.moveToFirst()) return exact.getLong(0);
        }
        if (participant.x != null && participant.y != null) {
            try (Cursor coordinate = db.query("participants", new String[]{"id"},
                    "report_id=? AND side=? AND position_x=? AND position_y=?",
                    new String[]{reportId, participant.side.name(), Integer.toString(participant.x), Integer.toString(participant.y)},
                    null, null, null, "1")) {
                if (coordinate.moveToFirst()) return coordinate.getLong(0);
            }
        }
        String wanted = identity(participant.alliance + participant.name);
        try (Cursor candidates = db.query("participants", new String[]{"id", "alliance_name", "player_name"},
                "report_id=? AND side=?", new String[]{reportId, participant.side.name()}, null, null, null)) {
            while (candidates.moveToNext()) {
                String candidate = identity(candidates.getString(1) + candidates.getString(2));
                int threshold = Math.max(1, Math.min(wanted.length(), candidate.length()) / 10);
                if (!wanted.isEmpty() && editDistance(wanted, candidate) <= threshold) return candidates.getLong(0);
            }
        }
        return -1;
    }

    private ContentValues participantValues(ParticipantFrame p, boolean includeSummary) {
        ContentValues values = new ContentValues();
        if (!p.alliance.isEmpty()) values.put("alliance_name", p.alliance);
        if (!p.name.isEmpty()) values.put("player_name", p.name);
        putNullable(values, "position_x", p.x);
        putNullable(values, "position_y", p.y);
        // A partially or incorrectly read card must never overwrite a previously valid card.
        if (includeSummary) {
            putNullable(values, "total", p.total);
            putNullable(values, "power_loss", p.powerLoss);
            putNullable(values, "kills", p.kills);
            putNullable(values, "fallen", p.fallen);
            putNullable(values, "survivors", p.survivors);
            putNullable(values, "wounded", p.wounded);
        }
        return values;
    }

    private void touchReport(String reportId) {
        ContentValues values = new ContentValues();
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("reports", values, "id=?", new String[]{reportId});
    }

    private int scalarInt(String sql, String arg) {
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, new String[]{arg})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    private int count(String sql, String arg) { return scalarInt(sql, arg); }

    private static void putNullable(ContentValues values, String key, Number value) {
        if (value == null) return;
        if (value instanceof Integer) values.put(key, value.intValue()); else values.put(key, value.longValue());
    }

    private static String identity(String value) {
        if (value == null) return "";
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static int editDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) current[j] = Math.min(
                    Math.min(current[j - 1] + 1, previous[j] + 1),
                    previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1));
            previous = current;
        }
        return previous[right.length()];
    }

    private static String cleanOr(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String nullToDash(String value) { return value == null || value.isEmpty() ? "—" : value; }
    private static String number(long value) { return NumberFormat.getIntegerInstance(Locale.GERMANY).format(value); }

    private static String eventTypeLabel(String value) {
        if (EventScoring.TYPE_TITAN.equals(value)) return "Titan";
        if (EventScoring.TYPE_WARPLANE.equals(value)) return "Kampfflugzeug";
        if (EventScoring.TYPE_NONE.equals(value)) return "keine Punkte";
        return "Standard";
    }

    private static String longValue(Cursor cursor, String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        return cursor.isNull(index) ? "—" : number(cursor.getLong(index));
    }

    public static final class Progress {
        public final int expectedAttackers, expectedDefenders, seenAttackers, seenDefenders, completeAttackers, completeDefenders;
        public final boolean complete;

        public Progress(int expectedAttackers, int expectedDefenders, int seenAttackers, int seenDefenders,
                        int completeAttackers, int completeDefenders, boolean complete) {
            this.expectedAttackers = expectedAttackers; this.expectedDefenders = expectedDefenders;
            this.seenAttackers = seenAttackers; this.seenDefenders = seenDefenders;
            this.completeAttackers = completeAttackers; this.completeDefenders = completeDefenders;
            this.complete = complete;
        }

        public String label() {
            if (complete) return "✓ Bericht vollständig";
            String a = expectedAttackers > 0 ? completeAttackers + "/" + expectedAttackers : seenAttackers + "/?";
            String d = expectedDefenders > 0 ? completeDefenders + "/" + expectedDefenders : seenDefenders + "/?";
            return "A " + a + " · V " + d;
        }
    }

    public static final class EventPoints {
        public final boolean eventMode, resourceField;
        public final long attackerPoints, defenderPoints;

        public EventPoints(boolean eventMode, boolean resourceField, long attackerPoints, long defenderPoints) {
            this.eventMode = eventMode; this.resourceField = resourceField;
            this.attackerPoints = attackerPoints; this.defenderPoints = defenderPoints;
        }

        public String overlayLabel() {
            return "BF A " + number(attackerPoints) + " · V " + number(defenderPoints)
                    + (resourceField ? " · 50 %" : "");
        }
    }

    public static final class ReportRow {
        public final String id, displayId, status, battleTimestamp, result;
        public final long updatedAt, attackerPoints, defenderPoints;
        public final int expectedAttackers, expectedDefenders, seenAttackers, seenDefenders;
        public final boolean eventMode, resourceField;

        public ReportRow(String id, String displayId, String status, String battleTimestamp, String result,
                         long updatedAt, int expectedAttackers, int expectedDefenders, int seenAttackers,
                         int seenDefenders, boolean eventMode, boolean resourceField,
                         long attackerPoints, long defenderPoints) {
            this.id = id; this.displayId = displayId; this.status = status; this.battleTimestamp = battleTimestamp;
            this.result = result; this.updatedAt = updatedAt; this.expectedAttackers = expectedAttackers;
            this.expectedDefenders = expectedDefenders; this.seenAttackers = seenAttackers;
            this.seenDefenders = seenDefenders; this.eventMode = eventMode; this.resourceField = resourceField;
            this.attackerPoints = attackerPoints; this.defenderPoints = defenderPoints;
        }

        @Override public String toString() {
            String time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(updatedAt));
            String expectedA = expectedAttackers > 0 ? Integer.toString(expectedAttackers) : "?";
            String expectedD = expectedDefenders > 0 ? Integer.toString(expectedDefenders) : "?";
            String base = displayId + "\n" + status +
                    (result == null || result.isEmpty() ? "" : " · " + result) +
                    (battleTimestamp == null || battleTimestamp.isEmpty() ? "" : " · " + battleTimestamp) +
                    "\nTeilnehmer: A " + seenAttackers + "/" + expectedA +
                    " · V " + seenDefenders + "/" + expectedD;
            if (eventMode) base += "\nBF: A " + number(attackerPoints) + " · V " + number(defenderPoints)
                    + (resourceField ? " · Ressourcenfeld" : "");
            return base + "\nErfasst: " + time;
        }
    }

    public static final class UnitTypeRow {
        public final String signature, displayName, category, eventType;
        public final byte[] representativePng;
        public final int distance;

        public UnitTypeRow(String signature, String displayName, String category, String eventType,
                           byte[] representativePng, int distance) {
            this.signature = signature; this.displayName = displayName; this.category = category;
            this.eventType = eventType; this.representativePng = representativePng; this.distance = distance;
        }
    }
}
