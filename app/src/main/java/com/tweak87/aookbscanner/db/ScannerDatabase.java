package com.tweak87.aookbscanner.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.tweak87.aookbscanner.model.Models.ParticipantFrame;
import com.tweak87.aookbscanner.model.Models.Side;
import com.tweak87.aookbscanner.model.Models.UnitFrame;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class ScannerDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "aoo_scanner.db";
    private static final int DB_VERSION = 1;

    public ScannerDatabase(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
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
                "status TEXT NOT NULL DEFAULT 'ENTWURF'," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL)");
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

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // First schema version. Future migrations must preserve captured reports.
    }

    public synchronized String findReportByFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) return null;
        try (Cursor cursor = getReadableDatabase().query("reports", new String[]{"id"},
                "fingerprint=?", new String[]{fingerprint}, null, null, null, "1")) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    public synchronized void insertReport(String id, String displayId, String fingerprint,
                                          String battleTimestamp, String result,
                                          Integer x, Integer y, int attackers, int defenders) {
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("display_id", displayId);
        values.put("fingerprint", fingerprint);
        values.put("battle_timestamp", battleTimestamp);
        values.put("result", result);
        putNullable(values, "position_x", x);
        putNullable(values, "position_y", y);
        values.put("expected_attackers", Math.max(attackers, 0));
        values.put("expected_defenders", Math.max(defenders, 0));
        values.put("created_at", now);
        values.put("updated_at", now);
        getWritableDatabase().insertWithOnConflict("reports", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public synchronized void updateReportHeader(String id, String fingerprint, String timestamp,
                                                String result, Integer x, Integer y,
                                                Integer attackers, Integer defenders) {
        ContentValues values = new ContentValues();
        if (fingerprint != null && !fingerprint.isEmpty()) values.put("fingerprint", fingerprint);
        if (timestamp != null && !timestamp.isEmpty()) values.put("battle_timestamp", timestamp);
        if (result != null && !result.isEmpty()) values.put("result", result);
        putNullable(values, "position_x", x);
        putNullable(values, "position_y", y);
        if (attackers != null) values.put("expected_attackers", attackers);
        if (defenders != null) values.put("expected_defenders", defenders);
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("reports", values, "id=?", new String[]{id});
    }

    public synchronized long upsertParticipant(String reportId, ParticipantFrame participant) {
        SQLiteDatabase db = getWritableDatabase();
        String stableKey = participant.stableKey();
        long id = findParticipantId(db, reportId, stableKey);
        if (id < 0) {
            ContentValues base = new ContentValues();
            base.put("report_id", reportId);
            base.put("side", participant.side.name());
            base.put("stable_key", stableKey);
            id = db.insertOrThrow("participants", null, base);
        }
        ContentValues values = participantValues(participant);
        // Never downgrade a participant when a later, partially visible frame only
        // contains the name card but not all six summary fields.
        if (participant.isSummaryValid()) values.put("summary_complete", 1);
        db.update("participants", values, "id=?", new String[]{Long.toString(id)});
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
        if (values.size() > 0) {
            getWritableDatabase().update("participants", values, "id=?",
                    new String[]{Long.toString(participantId)});
        }
    }

    public synchronized String ensureUnitType(long hash, byte[] representativePng, String tier) {
        SQLiteDatabase db = getWritableDatabase();
        UnitTypeRow closest = findClosestUnitType(db, hash, 7);
        if (closest != null) return closest.signature;
        String signature = String.format(Locale.ROOT, "%016X", hash);
        ContentValues values = new ContentValues();
        values.put("signature", signature);
        values.put("display_name", "Unbekannte Einheit " + signature.substring(0, 6));
        values.put("category", tier != null && tier.matches("\\d+") ? "Spezialeinheit" : "Unklassifiziert");
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
                "AND summary_complete=1 AND units_seen=1 AND technology_seen=1 AND technology_end_seen=1", reportId);
        int completeD = count("SELECT COUNT(*) FROM participants WHERE report_id=? AND side='DEFENDER' " +
                "AND summary_complete=1 AND technology_seen=1 AND technology_end_seen=1", reportId);
        boolean complete = expectedA > 0 && expectedD > 0 && completeA >= expectedA && completeD >= expectedD;
        ContentValues values = new ContentValues();
        values.put("status", complete ? "VOLLSTÄNDIG" : "ENTWURF");
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("reports", values, "id=?", new String[]{reportId});
        return new Progress(expectedA, expectedD, seenA, seenD, completeA, completeD, complete);
    }

    public synchronized List<ReportRow> listReports() {
        List<ReportRow> rows = new ArrayList<>();
        String sql = "SELECT r.id,r.display_id,r.status,r.battle_timestamp,r.result,r.updated_at," +
                "r.expected_attackers,r.expected_defenders," +
                "SUM(CASE WHEN p.side='ATTACKER' THEN 1 ELSE 0 END)," +
                "SUM(CASE WHEN p.side='DEFENDER' THEN 1 ELSE 0 END) " +
                "FROM reports r LEFT JOIN participants p ON p.report_id=r.id " +
                "GROUP BY r.id ORDER BY r.updated_at DESC";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, null)) {
            while (cursor.moveToNext()) {
                rows.add(new ReportRow(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                        cursor.getString(3), cursor.getString(4), cursor.getLong(5),
                        cursor.getInt(6), cursor.getInt(7), cursor.getInt(8), cursor.getInt(9)));
            }
        }
        return rows;
    }

    public synchronized List<UnitTypeRow> listUnitTypes() {
        List<UnitTypeRow> rows = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("unit_types",
                new String[]{"signature", "display_name", "category", "representative_png"},
                null, null, null, null, "updated_at DESC")) {
            while (cursor.moveToNext()) {
                rows.add(new UnitTypeRow(cursor.getString(0), cursor.getString(1),
                        cursor.getString(2), cursor.getBlob(3), 0));
            }
        }
        return rows;
    }

    public synchronized void updateUnitType(String signature, String displayName, String category) {
        ContentValues values = new ContentValues();
        values.put("display_name", displayName.trim().isEmpty() ? "Unbekannte Einheit" : displayName.trim());
        values.put("category", category.trim().isEmpty() ? "Unklassifiziert" : category.trim());
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("unit_types", values, "signature=?", new String[]{signature});
    }

    public synchronized String reportDetails(String reportId) {
        StringBuilder text = new StringBuilder();
        try (Cursor report = getReadableDatabase().query("reports", null, "id=?",
                new String[]{reportId}, null, null, null)) {
            if (report.moveToFirst()) {
                text.append(report.getString(report.getColumnIndexOrThrow("display_id"))).append('\n');
                text.append(report.getString(report.getColumnIndexOrThrow("status"))).append(" · ")
                        .append(nullToDash(report.getString(report.getColumnIndexOrThrow("result")))).append('\n');
                text.append("Spielzeit: ").append(nullToDash(report.getString(report.getColumnIndexOrThrow("battle_timestamp")))).append("\n\n");
            }
        }
        try (Cursor participants = getReadableDatabase().query("participants", null, "report_id=?",
                new String[]{reportId}, null, null, "side,id")) {
            while (participants.moveToNext()) {
                long participantId = participants.getLong(participants.getColumnIndexOrThrow("id"));
                text.append(participants.getString(participants.getColumnIndexOrThrow("side")).equals("ATTACKER")
                        ? "ANGREIFER" : "VERTEIDIGER").append("\n");
                text.append(nullToDash(participants.getString(participants.getColumnIndexOrThrow("alliance_name"))))
                        .append(' ').append(nullToDash(participants.getString(participants.getColumnIndexOrThrow("player_name")))).append('\n');
                int xIndex = participants.getColumnIndexOrThrow("position_x");
                int yIndex = participants.getColumnIndexOrThrow("position_y");
                if (!participants.isNull(xIndex) && !participants.isNull(yIndex)) {
                    text.append("Position X:").append(participants.getInt(xIndex))
                            .append(" Y:").append(participants.getInt(yIndex)).append('\n');
                }
                text.append("Gesamt ").append(longValue(participants, "total"))
                        .append(" · Kraftverlust ").append(longValue(participants, "power_loss")).append('\n')
                        .append("Getötete Feinde ").append(longValue(participants, "kills"))
                        .append(" · Gefallene ").append(longValue(participants, "fallen")).append('\n')
                        .append(" · Überlebend ").append(longValue(participants, "survivors"))
                        .append(" · Verwundet ").append(longValue(participants, "wounded")).append('\n');
                appendUnits(text, participantId);
                appendBonuses(text, participantId);
                text.append('\n');
            }
        }
        return text.toString();
    }

    private void appendUnits(StringBuilder text, long participantId) {
        String sql = "SELECT t.display_name,t.category,u.tier,u.survivors,u.wounded,u.fallen,u.kills " +
                "FROM unit_rows u JOIN unit_types t ON t.signature=u.signature WHERE u.participant_id=? ORDER BY u.id";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, new String[]{Long.toString(participantId)})) {
            if (cursor.getCount() > 0) text.append("Einheiten:\n");
            while (cursor.moveToNext()) {
                text.append("• ").append(cursor.getString(0)).append(" [").append(cursor.getString(1))
                        .append("] Stufe ").append(cursor.getString(2)).append(": ")
                        .append(cursor.isNull(3) ? "—" : cursor.getLong(3)).append('/')
                        .append(cursor.isNull(4) ? "—" : cursor.getLong(4)).append('/')
                        .append(cursor.isNull(5) ? "—" : cursor.getLong(5)).append('/')
                        .append(cursor.isNull(6) ? "—" : cursor.getLong(6)).append('\n');
            }
        }
    }

    private void appendBonuses(StringBuilder text, long participantId) {
        try (Cursor cursor = getReadableDatabase().query("bonuses",
                new String[]{"label_raw", "value_raw"}, "participant_id=?",
                new String[]{Long.toString(participantId)}, null, null, "id")) {
            if (cursor.getCount() > 0) text.append("Technologieboni:\n");
            while (cursor.moveToNext()) {
                text.append("• ").append(cursor.getString(0)).append(": ").append(cursor.getString(1)).append('\n');
            }
        }
    }

    private UnitTypeRow findClosestUnitType(SQLiteDatabase db, long hash, int maxDistance) {
        UnitTypeRow best = null;
        int bestDistance = Integer.MAX_VALUE;
        try (Cursor cursor = db.query("unit_types", new String[]{"signature", "display_name", "category", "representative_png"},
                null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                try {
                    long candidate = Long.parseUnsignedLong(cursor.getString(0), 16);
                    int distance = Long.bitCount(hash ^ candidate);
                    if (distance < bestDistance && distance <= maxDistance) {
                        bestDistance = distance;
                        best = new UnitTypeRow(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getBlob(3), distance);
                    }
                } catch (NumberFormatException ignored) {
                    // Keep scanning valid signatures.
                }
            }
        }
        return best;
    }

    private long findParticipantId(SQLiteDatabase db, String reportId, String stableKey) {
        try (Cursor cursor = db.query("participants", new String[]{"id"}, "report_id=? AND stable_key=?",
                new String[]{reportId, stableKey}, null, null, null, "1")) {
            return cursor.moveToFirst() ? cursor.getLong(0) : -1;
        }
    }

    private ContentValues participantValues(ParticipantFrame p) {
        ContentValues values = new ContentValues();
        values.put("alliance_name", p.alliance);
        values.put("player_name", p.name);
        putNullable(values, "position_x", p.x);
        putNullable(values, "position_y", p.y);
        putNullable(values, "total", p.total);
        putNullable(values, "power_loss", p.powerLoss);
        putNullable(values, "kills", p.kills);
        putNullable(values, "fallen", p.fallen);
        putNullable(values, "survivors", p.survivors);
        putNullable(values, "wounded", p.wounded);
        return values;
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

    private static String nullToDash(String value) { return value == null || value.isEmpty() ? "—" : value; }

    private static String longValue(Cursor cursor, String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        return cursor.isNull(index) ? "—" : Long.toString(cursor.getLong(index));
    }

    public static final class Progress {
        public final int expectedAttackers, expectedDefenders, seenAttackers, seenDefenders, completeAttackers, completeDefenders;
        public final boolean complete;

        public Progress(int expectedAttackers, int expectedDefenders, int seenAttackers, int seenDefenders,
                        int completeAttackers, int completeDefenders, boolean complete) {
            this.expectedAttackers = expectedAttackers;
            this.expectedDefenders = expectedDefenders;
            this.seenAttackers = seenAttackers;
            this.seenDefenders = seenDefenders;
            this.completeAttackers = completeAttackers;
            this.completeDefenders = completeDefenders;
            this.complete = complete;
        }

        public String label() {
            if (complete) return "✓ Bericht vollständig";
            String a = expectedAttackers > 0 ? completeAttackers + "/" + expectedAttackers : seenAttackers + "/?";
            String d = expectedDefenders > 0 ? completeDefenders + "/" + expectedDefenders : seenDefenders + "/?";
            return "A " + a + " · V " + d;
        }
    }

    public static final class ReportRow {
        public final String id, displayId, status, battleTimestamp, result;
        public final long updatedAt;
        public final int expectedAttackers, expectedDefenders, seenAttackers, seenDefenders;

        public ReportRow(String id, String displayId, String status, String battleTimestamp, String result,
                         long updatedAt, int expectedAttackers, int expectedDefenders, int seenAttackers, int seenDefenders) {
            this.id = id; this.displayId = displayId; this.status = status;
            this.battleTimestamp = battleTimestamp; this.result = result; this.updatedAt = updatedAt;
            this.expectedAttackers = expectedAttackers; this.expectedDefenders = expectedDefenders;
            this.seenAttackers = seenAttackers; this.seenDefenders = seenDefenders;
        }

        @Override public String toString() {
            String time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(updatedAt));
            return displayId + "\n" + status + " · " + (result == null ? "" : result) + "\nA " +
                    seenAttackers + "/" + expectedAttackers + " · V " + seenDefenders + "/" + expectedDefenders + " · " + time;
        }
    }

    public static final class UnitTypeRow {
        public final String signature, displayName, category;
        public final byte[] representativePng;
        public final int distance;

        public UnitTypeRow(String signature, String displayName, String category, byte[] representativePng, int distance) {
            this.signature = signature; this.displayName = displayName; this.category = category;
            this.representativePng = representativePng; this.distance = distance;
        }
    }
}
