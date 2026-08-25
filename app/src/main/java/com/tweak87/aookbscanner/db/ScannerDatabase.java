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
import com.tweak87.aookbscanner.ocr.BonusCatalog;
import com.tweak87.aookbscanner.ocr.NumberParser;
import com.tweak87.aookbscanner.ocr.TextNormalization;

import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class ScannerDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "aoo_scanner.db";
    private static final int DB_VERSION = 3;

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
                "default_tier TEXT NOT NULL DEFAULT '?'," +
                "badge_hash INTEGER," +
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
        createVersionThreeTables(db);
        seedStatusTypes(db);
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
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE unit_types ADD COLUMN default_tier TEXT NOT NULL DEFAULT '?'");
            db.execSQL("ALTER TABLE unit_types ADD COLUMN badge_hash INTEGER");
            createVersionThreeTables(db);
            seedStatusTypes(db);
        }
    }

    private void createVersionThreeTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS status_types (" +
                "canonical_key TEXT PRIMARY KEY," +
                "canonical_name TEXT NOT NULL," +
                "display_name TEXT NOT NULL," +
                "aliases TEXT NOT NULL DEFAULT ''," +
                "sort_order INTEGER NOT NULL DEFAULT 0," +
                "updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS evidence_frames (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "report_id TEXT NOT NULL REFERENCES reports(id) ON DELETE CASCADE," +
                "sequence INTEGER NOT NULL," +
                "file_path TEXT NOT NULL," +
                "screen_type TEXT NOT NULL," +
                "side TEXT NOT NULL," +
                "recognized_count INTEGER NOT NULL DEFAULT 0," +
                "pending_count INTEGER NOT NULL DEFAULT 0," +
                "invalid_count INTEGER NOT NULL DEFAULT 0," +
                "created_at INTEGER NOT NULL," +
                "UNIQUE(report_id, sequence))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_evidence_report ON evidence_frames(report_id, sequence)");
    }

    private void seedStatusTypes(SQLiteDatabase db) {
        int order = 0;
        for (String label : BonusCatalog.labels()) {
            ContentValues values = new ContentValues();
            values.put("canonical_key", TextNormalization.key(label));
            values.put("canonical_name", label);
            values.put("display_name", label);
            values.put("aliases", "");
            values.put("sort_order", order++);
            values.put("updated_at", System.currentTimeMillis());
            db.insertWithOnConflict("status_types", null, values, SQLiteDatabase.CONFLICT_IGNORE);
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

    public synchronized String ensureUnitType(long hash, long badgeHash,
                                              byte[] representativePng, String tier) {
        SQLiteDatabase db = getWritableDatabase();
        String normalizedTier = EventScoring.normalizedTier(tier);
        UnitTypeRow closest = findClosestUnitType(db, hash, badgeHash, 22);
        if (closest != null) return closest.signature;
        String signature = String.format(Locale.ROOT, "%016X%016X", hash, badgeHash);
        int parsedTier = EventScoring.parseTier(tier);
        ContentValues values = new ContentValues();
        values.put("signature", signature);
        values.put("display_name", "Unbekannte Einheit " + signature.substring(0, 6));
        values.put("category", "Unklassifiziert");
        values.put("event_type", EventScoring.TYPE_STANDARD);
        values.put("default_tier", parsedTier >= 1 && parsedTier <= 13 ? normalizedTier : "?");
        values.put("badge_hash", badgeHash);
        values.put("representative_png", representativePng);
        values.put("updated_at", System.currentTimeMillis());
        db.insertWithOnConflict("unit_types", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        return signature;
    }

    public synchronized void upsertUnit(long participantId, String signature, UnitFrame unit) {
        if (participantId < 0) return;
        String tier = EventScoring.normalizedTier(unit.tier);
        if ("?".equals(tier)) tier = unitTypeTier(signature);
        ContentValues values = new ContentValues();
        values.put("participant_id", participantId);
        values.put("signature", signature);
        values.put("tier", tier);
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
        int units = count("SELECT COUNT(*) FROM unit_rows u JOIN participants p ON p.id=u.participant_id WHERE p.report_id=?", reportId);
        int bonuses = count("SELECT COUNT(*) FROM bonuses b JOIN participants p ON p.id=b.participant_id WHERE p.report_id=?", reportId);
        int unknownTiers = count("SELECT COUNT(*) FROM unit_rows u JOIN participants p ON p.id=u.participant_id " +
                "WHERE p.report_id=? AND (u.tier='?' OR u.tier='')", reportId);
        int requiredA = expectedA > 0 ? expectedA : seenA;
        int requiredD = expectedD > 0 ? expectedD : seenD;
        boolean complete = requiredA > 0 && requiredD > 0 && completeA >= requiredA && completeD >= requiredD;
        touchReport(reportId);
        return new Progress(expectedA, expectedD, seenA, seenD, completeA, completeD,
                units, bonuses, unknownTiers, complete);
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
                new String[]{"signature", "display_name", "category", "event_type", "default_tier",
                        "badge_hash", "representative_png"},
                null, null, null, null, "updated_at DESC")) {
            while (cursor.moveToNext()) rows.add(new UnitTypeRow(cursor.getString(0), cursor.getString(1),
                    cursor.getString(2), cursor.getString(3), cursor.getString(4),
                    cursor.isNull(5) ? 0 : cursor.getLong(5), cursor.getBlob(6), 0));
        }
        return rows;
    }

    public synchronized void updateUnitType(String signature, String displayName,
                                            String category, String eventType, String defaultTier) {
        String normalizedTier = EventScoring.normalizedTier(defaultTier);
        ContentValues values = new ContentValues();
        values.put("display_name", cleanOr(displayName, "Unbekannte Einheit"));
        values.put("category", cleanOr(category, "Unklassifiziert"));
        values.put("event_type", cleanOr(eventType, EventScoring.TYPE_STANDARD));
        values.put("default_tier", normalizedTier);
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("unit_types", values, "signature=?", new String[]{signature});
        if (!"?".equals(normalizedTier)) {
            ContentValues tiers = new ContentValues();
            tiers.put("tier", normalizedTier);
            getWritableDatabase().update("unit_rows", tiers, "signature=?", new String[]{signature});
        }
        List<String> reportIds = new ArrayList<>();
        String sql = "SELECT DISTINCT p.report_id FROM unit_rows u JOIN participants p ON p.id=u.participant_id " +
                "WHERE u.signature=?";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, new String[]{signature})) {
            while (cursor.moveToNext()) reportIds.add(cursor.getString(0));
        }
        for (String reportId : reportIds) recalculateEventPoints(reportId);
    }

    public synchronized List<StatusTypeRow> listStatusTypes() {
        List<StatusTypeRow> rows = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("status_types",
                new String[]{"canonical_key", "canonical_name", "display_name", "aliases", "sort_order"},
                null, null, null, null, "sort_order, display_name")) {
            while (cursor.moveToNext()) rows.add(new StatusTypeRow(cursor.getString(0), cursor.getString(1),
                    cursor.getString(2), cursor.getString(3), cursor.getInt(4)));
        }
        return rows;
    }

    public synchronized Map<String, String> statusAliasMap() {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (StatusTypeRow row : listStatusTypes()) {
            aliases.put(TextNormalization.normalize(row.canonicalName), row.canonicalName);
            aliases.put(TextNormalization.normalize(row.displayName), row.canonicalName);
            for (String alias : row.aliases.split("[\\n,;]+")) {
                String normalized = TextNormalization.normalize(alias);
                if (!normalized.isEmpty()) aliases.put(normalized, row.canonicalName);
            }
        }
        return aliases;
    }

    public synchronized void updateStatusType(String canonicalKey, String displayName, String aliases) {
        ContentValues values = new ContentValues();
        values.put("display_name", cleanOr(displayName, "Statuswert"));
        values.put("aliases", aliases == null ? "" : aliases.trim());
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("status_types", values, "canonical_key=?", new String[]{canonicalKey});
    }

    public synchronized void addStatusType(String name, String aliases) {
        String canonical = cleanOr(name, "Eigener Statuswert");
        String key = TextNormalization.key(canonical);
        ContentValues values = new ContentValues();
        values.put("canonical_key", key);
        values.put("canonical_name", canonical);
        values.put("display_name", canonical);
        values.put("aliases", aliases == null ? "" : aliases.trim());
        values.put("sort_order", scalarIntNoArgs("SELECT COALESCE(MAX(sort_order),0)+1 FROM status_types"));
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("status_types", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public synchronized int evidenceCount(String reportId) {
        return count("SELECT COUNT(*) FROM evidence_frames WHERE report_id=?", reportId);
    }

    public synchronized void insertEvidenceFrame(String reportId, int sequence, String path,
                                                  String screenType, String side,
                                                  int recognized, int pending, int invalid) {
        ContentValues values = new ContentValues();
        values.put("report_id", reportId);
        values.put("sequence", sequence);
        values.put("file_path", path);
        values.put("screen_type", screenType);
        values.put("side", side);
        values.put("recognized_count", recognized);
        values.put("pending_count", pending);
        values.put("invalid_count", invalid);
        values.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("evidence_frames", null, values,
                SQLiteDatabase.CONFLICT_IGNORE);
    }

    public synchronized List<EvidenceFrameRow> listEvidence(String reportId) {
        List<EvidenceFrameRow> rows = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("evidence_frames",
                new String[]{"id", "sequence", "file_path", "screen_type", "side",
                        "recognized_count", "pending_count", "invalid_count"},
                "report_id=?", new String[]{reportId}, null, null, "sequence")) {
            while (cursor.moveToNext()) rows.add(new EvidenceFrameRow(cursor.getLong(0), cursor.getInt(1),
                    cursor.getString(2), cursor.getString(3), cursor.getString(4), cursor.getInt(5),
                    cursor.getInt(6), cursor.getInt(7)));
        }
        return rows;
    }

    public synchronized List<EditableParticipant> listEditableParticipants(String reportId) {
        List<EditableParticipant> rows = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("participants", null, "report_id=?",
                new String[]{reportId}, null, null, "side, id")) {
            while (cursor.moveToNext()) rows.add(new EditableParticipant(cursor));
        }
        return rows;
    }

    public synchronized List<EditableUnit> listEditableUnits(long participantId) {
        List<EditableUnit> rows = new ArrayList<>();
        String sql = "SELECT u.id,u.signature,t.display_name,t.category,u.tier,u.survivors,u.wounded," +
                "u.fallen,u.kills FROM unit_rows u JOIN unit_types t ON t.signature=u.signature " +
                "WHERE u.participant_id=? ORDER BY u.id";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, new String[]{Long.toString(participantId)})) {
            while (cursor.moveToNext()) rows.add(new EditableUnit(cursor));
        }
        return rows;
    }

    public synchronized List<EditableBonus> listEditableBonuses(long participantId) {
        List<EditableBonus> rows = new ArrayList<>();
        String sql = "SELECT b.id,b.label_key,COALESCE(s.display_name,b.label_raw),b.value_raw " +
                "FROM bonuses b LEFT JOIN status_types s ON s.canonical_key=b.label_key " +
                "WHERE b.participant_id=? ORDER BY COALESCE(s.sort_order,9999),b.label_key";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, new String[]{Long.toString(participantId)})) {
            while (cursor.moveToNext()) rows.add(new EditableBonus(cursor.getLong(0), cursor.getString(1),
                    cursor.getString(2), cursor.getString(3)));
        }
        return rows;
    }

    public synchronized void updateParticipant(long id, String alliance, String name, Integer x, Integer y,
                                               Long total, Long powerLoss, Long kills, Long fallen,
                                               Long survivors, Long wounded) {
        ContentValues values = new ContentValues();
        values.put("alliance_name", alliance == null ? "" : alliance.trim());
        values.put("player_name", name == null ? "" : name.trim());
        putOrNull(values, "position_x", x);
        putOrNull(values, "position_y", y);
        putOrNull(values, "total", total);
        putOrNull(values, "power_loss", powerLoss);
        putOrNull(values, "kills", kills);
        putOrNull(values, "fallen", fallen);
        putOrNull(values, "survivors", survivors);
        putOrNull(values, "wounded", wounded);
        boolean complete = total != null && fallen != null && survivors != null && wounded != null &&
                total == fallen + survivors + wounded;
        values.put("summary_complete", complete ? 1 : 0);
        getWritableDatabase().update("participants", values, "id=?", new String[]{Long.toString(id)});
        touchReport(reportIdForParticipant(id));
    }

    public synchronized void updateUnitRow(long id, String tier, Long survivors, Long wounded,
                                           Long fallen, Long kills) {
        ContentValues values = new ContentValues();
        values.put("tier", EventScoring.normalizedTier(tier));
        putOrNull(values, "survivors", survivors);
        putOrNull(values, "wounded", wounded);
        putOrNull(values, "fallen", fallen);
        putOrNull(values, "kills", kills);
        getWritableDatabase().update("unit_rows", values, "id=?", new String[]{Long.toString(id)});
        String reportId = reportIdForUnit(id);
        recalculateEventPoints(reportId);
        touchReport(reportId);
    }

    public synchronized void updateBonus(long id, String valueRaw) {
        ContentValues values = new ContentValues();
        values.put("value_raw", cleanOr(valueRaw, "0"));
        Double parsed = NumberParser.parsePrimaryDecimal(valueRaw);
        if (parsed == null) values.putNull("primary_value"); else values.put("primary_value", parsed);
        getWritableDatabase().update("bonuses", values, "id=?", new String[]{Long.toString(id)});
        touchReport(reportIdForBonus(id));
    }

    public synchronized void addBonus(long participantId, String canonicalKey, String valueRaw) {
        String canonicalName = statusCanonicalName(canonicalKey);
        upsertBonus(participantId, canonicalKey, canonicalName, cleanOr(valueRaw, "0"),
                NumberParser.parsePrimaryDecimal(valueRaw));
        markProgress(participantId, false, true, false);
        touchReport(reportIdForParticipant(participantId));
    }

    public synchronized void addUnitRow(long participantId, String signature, String tier,
                                        Long survivors, Long wounded, Long fallen, Long kills) {
        ContentValues values = new ContentValues();
        values.put("participant_id", participantId);
        values.put("signature", signature);
        String normalized = EventScoring.normalizedTier(tier);
        values.put("tier", "?".equals(normalized) ? unitTypeTier(signature) : normalized);
        putOrNull(values, "survivors", survivors);
        putOrNull(values, "wounded", wounded);
        putOrNull(values, "fallen", fallen);
        putOrNull(values, "kills", kills);
        getWritableDatabase().insertWithOnConflict("unit_rows", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        markProgress(participantId, true, false, false);
        String reportId = reportIdForParticipant(participantId);
        recalculateEventPoints(reportId);
        touchReport(reportId);
    }

    public synchronized String createManualUnitType(String name, String category, String tier) {
        String signature = "MANUAL-" + UUID.randomUUID();
        ContentValues values = new ContentValues();
        values.put("signature", signature);
        values.put("display_name", cleanOr(name, "Manuelle Einheit"));
        values.put("category", cleanOr(category, "Unklassifiziert"));
        values.put("event_type", EventScoring.TYPE_STANDARD);
        values.put("default_tier", EventScoring.normalizedTier(tier));
        values.putNull("badge_hash");
        values.putNull("representative_png");
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().insertOrThrow("unit_types", null, values);
        return signature;
    }

    public synchronized void markReportReviewed(String reportId) {
        recalculateEventPoints(reportId);
        ContentValues values = new ContentValues();
        values.put("status", "MANUELL GEPRÜFT");
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("reports", values, "id=?", new String[]{reportId});
    }

    public synchronized String coverageTable(String reportId) {
        int participants = count("SELECT COUNT(*) FROM participants WHERE report_id=?", reportId);
        int units = count("SELECT COUNT(*) FROM unit_rows u JOIN participants p ON p.id=u.participant_id WHERE p.report_id=?", reportId);
        int unknownTiers = count("SELECT COUNT(*) FROM unit_rows u JOIN participants p ON p.id=u.participant_id " +
                "WHERE p.report_id=? AND (u.tier='?' OR u.tier='')", reportId);
        int bonuses = count("SELECT COUNT(*) FROM bonuses b JOIN participants p ON p.id=b.participant_id WHERE p.report_id=?", reportId);
        int pictures = evidenceCount(reportId);
        return "| Prüfpunkt | Erfasst |\n|---|---:|\n" +
                "| Spieler | " + participants + " |\n" +
                "| Einheitenzeilen | " + units + " |\n" +
                "| Stufen noch offen | " + unknownTiers + " |\n" +
                "| Statuswerte | " + bonuses + " |\n" +
                "| Belegbilder | " + pictures + " |";
    }

    public synchronized String reportDetails(String reportId) {
        StringBuilder text = new StringBuilder();
        EventPoints points = getEventPoints(reportId);
        try (Cursor report = getReadableDatabase().query("reports", null, "id=?",
                new String[]{reportId}, null, null, null)) {
            if (!report.moveToFirst()) return "Bericht nicht gefunden.";
            text.append("# AGE OF ORIGINS – KAMPFBERICHT\n\n");
            text.append("| Bericht | Wert |\n|---|---|\n");
            text.append("| Berichts-ID | ").append(md(report.getString(report.getColumnIndexOrThrow("display_id")))).append(" |\n");
            text.append("| Status | ").append(md(report.getString(report.getColumnIndexOrThrow("status")))).append(" |\n");
            text.append("| Ergebnis | ").append(md(nullToDash(report.getString(report.getColumnIndexOrThrow("result"))))).append(" |\n");
            text.append("| Spielzeit | ").append(md(nullToDash(report.getString(report.getColumnIndexOrThrow("battle_timestamp"))))).append(" |\n");
            int x = report.getColumnIndexOrThrow("position_x");
            int y = report.getColumnIndexOrThrow("position_y");
            text.append("| Kampfposition | ");
            if (!report.isNull(x) && !report.isNull(y)) text.append("X:")
                    .append(report.getInt(x)).append(" Y:").append(report.getInt(y));
            else text.append("—");
            text.append(" |\n");
            if (points.eventMode) {
                text.append("| BF Angreifer | ").append(number(points.attackerPoints)).append(" |\n");
                text.append("| BF Verteidiger | ").append(number(points.defenderPoints)).append(" |\n");
                text.append("| Ressourcenfeld (50 %) | ").append(points.resourceField ? "Ja" : "Nein").append(" |\n");
            }
            text.append("\n## Erfassungsstatus\n\n").append(coverageTable(reportId)).append("\n\n");
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
        text.append("## ").append(side == Side.ATTACKER ? "ANGREIFER" : "VERTEIDIGER").append("\n\n");
        try (Cursor participants = getReadableDatabase().query("participants", null,
                "report_id=? AND side=?", new String[]{reportId, side.name()}, null, null, "id")) {
            int index = 0;
            while (participants.moveToNext()) {
                index++;
                long participantId = participants.getLong(participants.getColumnIndexOrThrow("id"));
                text.append("### ").append(index).append(". ");
                String alliance = participants.getString(participants.getColumnIndexOrThrow("alliance_name"));
                if (alliance != null && !alliance.isEmpty()) text.append('(').append(alliance).append(") ");
                text.append(md(nullToDash(participants.getString(participants.getColumnIndexOrThrow("player_name"))))).append("\n\n");
                int x = participants.getColumnIndexOrThrow("position_x");
                int y = participants.getColumnIndexOrThrow("position_y");
                text.append("| Spielerwert | Wert | Spielerwert | Wert |\n|---|---:|---|---:|\n");
                text.append("| Position | ");
                if (!participants.isNull(x) && !participants.isNull(y)) text.append("X:")
                        .append(participants.getInt(x)).append(" Y:").append(participants.getInt(y));
                else text.append("—");
                text.append(" | Gesamt | ").append(longValue(participants, "total")).append(" |\n");
                text.append("| Kraftverlust | ").append(longValue(participants, "power_loss"))
                        .append(" | Getötete Feinde | ").append(longValue(participants, "kills")).append(" |\n");
                text.append("| Gefallene | ").append(longValue(participants, "fallen"))
                        .append(" | Verwundete | ").append(longValue(participants, "wounded")).append(" |\n");
                text.append("| Überlebende | ").append(longValue(participants, "survivors"))
                        .append(" | Zusammenfassung | ")
                        .append(participants.getInt(participants.getColumnIndexOrThrow("summary_complete")) == 1 ? "OK" : "OFFEN")
                        .append(" |\n");
                appendUnits(text, participantId);
                appendBonuses(text, participantId);
                text.append('\n');
            }
            if (index == 0) text.append("| Hinweis | Wert |\n|---|---|\n| Teilnehmer | Noch nicht erkannt |\n\n");
        }
    }

    private void appendUnits(StringBuilder text, long participantId) {
        String sql = "SELECT t.display_name,t.category,t.event_type,u.tier,u.survivors,u.wounded,u.fallen,u.kills " +
                "FROM unit_rows u JOIN unit_types t ON t.signature=u.signature WHERE u.participant_id=? ORDER BY u.id";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, new String[]{Long.toString(participantId)})) {
            if (cursor.getCount() > 0) text.append("\n#### Einheiten\n\n")
                    .append("| Stufe | Einheit | Art | Überlebende | Verwundete | Gefallene | Kills |\n")
                    .append("|---|---|---|---:|---:|---:|---:|\n");
            while (cursor.moveToNext()) {
                String type = cursor.getString(1);
                if (!EventScoring.TYPE_STANDARD.equals(cursor.getString(2))) type += " / BF " + eventTypeLabel(cursor.getString(2));
                text.append("| ").append(md(cursor.getString(3))).append(" | ").append(md(cursor.getString(0)))
                        .append(" | ").append(md(type)).append(" | ")
                        .append(cursor.isNull(4) ? "—" : number(cursor.getLong(4))).append(" | ")
                        .append(cursor.isNull(5) ? "—" : number(cursor.getLong(5))).append(" | ")
                        .append(cursor.isNull(6) ? "—" : number(cursor.getLong(6))).append(" | ")
                        .append(cursor.isNull(7) ? "—" : number(cursor.getLong(7))).append(" |\n");
            }
        }
    }

    private void appendBonuses(StringBuilder text, long participantId) {
        String sql = "SELECT COALESCE(s.display_name,b.label_raw),b.value_raw FROM bonuses b " +
                "LEFT JOIN status_types s ON s.canonical_key=b.label_key WHERE b.participant_id=? " +
                "ORDER BY COALESCE(s.sort_order,9999),b.label_key";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, new String[]{Long.toString(participantId)})) {
            if (cursor.getCount() > 0) text.append("\n#### Technologie-/Statuswerte\n\n")
                    .append("| Statuswert | Wert |\n|---|---:|\n");
            while (cursor.moveToNext()) text.append("| ").append(md(cursor.getString(0)))
                    .append(" | ").append(md(cursor.getString(1))).append(" |\n");
        }
    }

    private UnitTypeRow findClosestUnitType(SQLiteDatabase db, long hash, long badgeHash,
                                            int maxDistance) {
        UnitTypeRow best = null;
        int bestDistance = Integer.MAX_VALUE;
        try (Cursor cursor = db.query("unit_types",
                new String[]{"signature", "display_name", "category", "event_type", "default_tier",
                        "badge_hash", "representative_png"},
                null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                try {
                    String candidateSignature = cursor.getString(0);
                    if (candidateSignature.length() < 16 || candidateSignature.startsWith("MANUAL-")) continue;
                    long candidate = Long.parseUnsignedLong(candidateSignature.substring(0, 16), 16);
                    int distance = Long.bitCount(hash ^ candidate);
                    if (!cursor.isNull(5)) distance += Long.bitCount(badgeHash ^ cursor.getLong(5)) * 2;
                    else distance *= 2;
                    if (distance < bestDistance && distance <= maxDistance) {
                        bestDistance = distance;
                        best = new UnitTypeRow(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                                cursor.getString(3), cursor.getString(4),
                                cursor.isNull(5) ? 0 : cursor.getLong(5), cursor.getBlob(6), distance);
                    }
                } catch (NumberFormatException ignored) {
                    // Keep scanning valid signatures.
                }
            }
        }
        return best;
    }

    private String unitTypeTier(String signature) {
        try (Cursor cursor = getReadableDatabase().query("unit_types", new String[]{"default_tier"},
                "signature=?", new String[]{signature}, null, null, null)) {
            return cursor.moveToFirst() ? cursor.getString(0) : "?";
        }
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
        if (reportId == null || reportId.isEmpty()) return;
        ContentValues values = new ContentValues();
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("reports", values, "id=?", new String[]{reportId});
    }

    private int scalarInt(String sql, String arg) {
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, new String[]{arg})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    private int scalarIntNoArgs(String sql) {
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    private int count(String sql, String arg) { return scalarInt(sql, arg); }

    private static void putNullable(ContentValues values, String key, Number value) {
        if (value == null) return;
        if (value instanceof Integer) values.put(key, value.intValue()); else values.put(key, value.longValue());
    }

    private static void putOrNull(ContentValues values, String key, Number value) {
        if (value == null) values.putNull(key);
        else if (value instanceof Integer) values.put(key, value.intValue());
        else values.put(key, value.longValue());
    }

    private String reportIdForParticipant(long participantId) {
        try (Cursor cursor = getReadableDatabase().query("participants", new String[]{"report_id"},
                "id=?", new String[]{Long.toString(participantId)}, null, null, null)) {
            return cursor.moveToFirst() ? cursor.getString(0) : "";
        }
    }

    private String reportIdForUnit(long unitId) {
        String sql = "SELECT p.report_id FROM unit_rows u JOIN participants p ON p.id=u.participant_id WHERE u.id=?";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, new String[]{Long.toString(unitId)})) {
            return cursor.moveToFirst() ? cursor.getString(0) : "";
        }
    }

    private String reportIdForBonus(long bonusId) {
        String sql = "SELECT p.report_id FROM bonuses b JOIN participants p ON p.id=b.participant_id WHERE b.id=?";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, new String[]{Long.toString(bonusId)})) {
            return cursor.moveToFirst() ? cursor.getString(0) : "";
        }
    }

    private String statusCanonicalName(String canonicalKey) {
        try (Cursor cursor = getReadableDatabase().query("status_types", new String[]{"canonical_name"},
                "canonical_key=?", new String[]{canonicalKey}, null, null, null)) {
            return cursor.moveToFirst() ? cursor.getString(0) : canonicalKey.replace('_', ' ');
        }
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
    private static String md(String value) { return value == null ? "—" : value.replace("|", "\\|").replace('\n', ' '); }
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
        public final int units, bonuses, unknownTiers;
        public final boolean complete;

        public Progress(int expectedAttackers, int expectedDefenders, int seenAttackers, int seenDefenders,
                        int completeAttackers, int completeDefenders, int units, int bonuses,
                        int unknownTiers, boolean complete) {
            this.expectedAttackers = expectedAttackers; this.expectedDefenders = expectedDefenders;
            this.seenAttackers = seenAttackers; this.seenDefenders = seenDefenders;
            this.completeAttackers = completeAttackers; this.completeDefenders = completeDefenders;
            this.units = units; this.bonuses = bonuses; this.unknownTiers = unknownTiers;
            this.complete = complete;
        }

        public String label() {
            String a = expectedAttackers > 0 ? completeAttackers + "/" + expectedAttackers : seenAttackers + "/?";
            String d = expectedDefenders > 0 ? completeDefenders + "/" + expectedDefenders : seenDefenders + "/?";
            return "STATUS | " + (complete ? "VOLLSTÄNDIG" : "SCAN LÄUFT") +
                    "\nANGR.  | " + a + "   VER. | " + d +
                    "\nDATEN  | " + units + " Einh. · " + bonuses + " Status" +
                    (unknownTiers > 0 ? " · " + unknownTiers + " Stufe offen" : "");
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
            return "BF     | A " + number(attackerPoints) + " · V " + number(defenderPoints)
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
        public final String signature, displayName, category, eventType, defaultTier;
        public final long badgeHash;
        public final byte[] representativePng;
        public final int distance;

        public UnitTypeRow(String signature, String displayName, String category, String eventType,
                           String defaultTier, long badgeHash, byte[] representativePng, int distance) {
            this.signature = signature; this.displayName = displayName; this.category = category;
            this.eventType = eventType; this.defaultTier = defaultTier; this.badgeHash = badgeHash;
            this.representativePng = representativePng; this.distance = distance;
        }
    }

    public static final class StatusTypeRow {
        public final String canonicalKey, canonicalName, displayName, aliases;
        public final int sortOrder;

        public StatusTypeRow(String canonicalKey, String canonicalName, String displayName,
                             String aliases, int sortOrder) {
            this.canonicalKey = canonicalKey; this.canonicalName = canonicalName;
            this.displayName = displayName; this.aliases = aliases; this.sortOrder = sortOrder;
        }

        @Override public String toString() { return displayName; }
    }

    public static final class EvidenceFrameRow {
        public final long id;
        public final int sequence, recognizedCount, pendingCount, invalidCount;
        public final String filePath, screenType, side;

        public EvidenceFrameRow(long id, int sequence, String filePath, String screenType, String side,
                                int recognizedCount, int pendingCount, int invalidCount) {
            this.id = id; this.sequence = sequence; this.filePath = filePath;
            this.screenType = screenType; this.side = side; this.recognizedCount = recognizedCount;
            this.pendingCount = pendingCount; this.invalidCount = invalidCount;
        }

        public String label() {
            String area = "ATTACKER".equals(side) ? "Angreifer" :
                    "DEFENDER".equals(side) ? "Verteidiger" :
                            "BATTLE_SUMMARY".equals(screenType) ? "Übersicht" : screenType;
            return "BILD    | " + sequence + " · " + area +
                    "\nFELDER  | erkannt " + recognizedCount + " · offen " + pendingCount +
                    " · fehlerhaft " + invalidCount;
        }
    }

    public static final class EditableParticipant {
        public final long id;
        public final String side, alliance, name;
        public final Integer x, y;
        public final Long total, powerLoss, kills, fallen, survivors, wounded;

        EditableParticipant(Cursor cursor) {
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
            side = cursor.getString(cursor.getColumnIndexOrThrow("side"));
            alliance = nullToEmpty(cursor.getString(cursor.getColumnIndexOrThrow("alliance_name")));
            name = nullToEmpty(cursor.getString(cursor.getColumnIndexOrThrow("player_name")));
            x = nullableInt(cursor, "position_x"); y = nullableInt(cursor, "position_y");
            total = nullableLong(cursor, "total"); powerLoss = nullableLong(cursor, "power_loss");
            kills = nullableLong(cursor, "kills"); fallen = nullableLong(cursor, "fallen");
            survivors = nullableLong(cursor, "survivors"); wounded = nullableLong(cursor, "wounded");
        }

        @Override public String toString() {
            return ("ATTACKER".equals(side) ? "Angreifer" : "Verteidiger") + " · " +
                    (alliance.isEmpty() ? "" : "(" + alliance + ") ") + (name.isEmpty() ? "Unbekannt" : name);
        }
    }

    public static final class EditableUnit {
        public final long id;
        public final String signature, displayName, category, tier;
        public final Long survivors, wounded, fallen, kills;

        EditableUnit(Cursor cursor) {
            id = cursor.getLong(0); signature = cursor.getString(1); displayName = cursor.getString(2);
            category = cursor.getString(3); tier = cursor.getString(4);
            survivors = cursor.isNull(5) ? null : cursor.getLong(5);
            wounded = cursor.isNull(6) ? null : cursor.getLong(6);
            fallen = cursor.isNull(7) ? null : cursor.getLong(7);
            kills = cursor.isNull(8) ? null : cursor.getLong(8);
        }
    }

    public static final class EditableBonus {
        public final long id;
        public final String canonicalKey, displayName, valueRaw;

        EditableBonus(long id, String canonicalKey, String displayName, String valueRaw) {
            this.id = id; this.canonicalKey = canonicalKey;
            this.displayName = displayName; this.valueRaw = valueRaw;
        }
    }

    private static Integer nullableInt(Cursor cursor, String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        return cursor.isNull(index) ? null : cursor.getInt(index);
    }

    private static Long nullableLong(Cursor cursor, String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        return cursor.isNull(index) ? null : cursor.getLong(index);
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }
}
