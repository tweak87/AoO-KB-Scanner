package com.tweak87.aookbscanner.ocr;

import android.graphics.Bitmap;

import com.tweak87.aookbscanner.db.ScannerDatabase;
import com.tweak87.aookbscanner.db.ScannerDatabase.Progress;
import com.tweak87.aookbscanner.model.Models.AnalysisResult;
import com.tweak87.aookbscanner.model.Models.BonusFrame;
import com.tweak87.aookbscanner.model.Models.BoxState;
import com.tweak87.aookbscanner.model.Models.ParsedFrame;
import com.tweak87.aookbscanner.model.Models.ParticipantFrame;
import com.tweak87.aookbscanner.model.Models.ScreenType;
import com.tweak87.aookbscanner.model.Models.Side;
import com.tweak87.aookbscanner.model.Models.UnitFrame;
import com.tweak87.aookbscanner.util.Hashing;

import java.io.ByteArrayOutputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Combines overlapping OCR frames into one durable battle report. */
public final class ReportAssembler {
    private final ScannerDatabase database;
    private final Map<Side, Long> activeParticipants = new EnumMap<>(Side.class);
    private String currentReportId;
    private String currentDisplayId;

    public ReportAssembler(ScannerDatabase database) {
        this.database = database;
    }

    public synchronized AnalysisResult consume(ParsedFrame frame) {
        if (frame.screenType == ScreenType.BATTLE_SUMMARY) {
            acceptHeader(frame);
            return new AnalysisResult(frame.boxes, "Kampfbericht erkannt · " + currentDisplayId, BoxState.VALID);
        }
        if (frame.screenType == ScreenType.ARMY_INFO) {
            ensureFallbackReport();
            acceptArmyFrame(frame);
            Progress progress = database.getProgress(currentReportId);
            return new AnalysisResult(frame.boxes, progress.label(), progress.complete ? BoxState.VALID : BoxState.PENDING);
        }
        if (frame.screenType == ScreenType.MESSAGE_LIST) {
            return new AnalysisResult(frame.boxes, "Nachrichten erkannt · Bericht öffnen", BoxState.PENDING);
        }
        return new AnalysisResult(frame.boxes, "Scanner aktiv", BoxState.PENDING);
    }

    private void acceptHeader(ParsedFrame frame) {
        String seed = frame.fingerprintSeed == null ? "" : frame.fingerprintSeed;
        String fingerprint = seed.isEmpty() ? null : Hashing.sha256(seed);
        String existing = database.findReportByFingerprint(fingerprint);
        if (existing != null) {
            if (!existing.equals(currentReportId)) activeParticipants.clear();
            currentReportId = existing;
            currentDisplayId = displayId(frame.battleTimestamp, fingerprint);
            database.updateReportHeader(existing, fingerprint, frame.battleTimestamp, frame.result,
                    frame.reportX, frame.reportY, frame.expectedAttackers, frame.expectedDefenders);
            return;
        }

        // A newly opened summary starts a new capture, even if the previous report is still incomplete.
        currentReportId = UUID.randomUUID().toString();
        currentDisplayId = displayId(frame.battleTimestamp, fingerprint);
        activeParticipants.clear();
        database.insertReport(currentReportId, currentDisplayId, fingerprint, frame.battleTimestamp,
                frame.result, frame.reportX, frame.reportY,
                frame.expectedAttackers == null ? 0 : frame.expectedAttackers,
                frame.expectedDefenders == null ? 0 : frame.expectedDefenders);
    }

    private void ensureFallbackReport() {
        if (currentReportId != null) return;
        currentReportId = UUID.randomUUID().toString();
        currentDisplayId = displayId("", Hashing.sha256(currentReportId));
        database.insertReport(currentReportId, currentDisplayId, null, "", "", null, null, 0, 0);
    }

    private void acceptArmyFrame(ParsedFrame frame) {
        Side side = frame.side;
        if (side == Side.UNKNOWN) return;
        long active = activeParticipants.containsKey(side)
                ? activeParticipants.get(side) : database.latestParticipantId(currentReportId, side);

        List<Event> events = new ArrayList<>();
        for (ParticipantFrame participant : frame.participants) events.add(Event.participant(participant));
        for (UnitFrame unit : frame.units) events.add(Event.unit(unit));
        for (BonusFrame bonus : frame.bonuses) events.add(Event.bonus(bonus));
        if (frame.technologyHeaderSeen && frame.technologyHeaderY >= 0) {
            events.add(Event.technologyHeader(frame.technologyHeaderY));
        }
        if (frame.technologyEndSeen && frame.technologyEndY >= 0) {
            events.add(Event.technologyEnd(frame.technologyEndY));
        }
        events.sort(Comparator.comparingInt(event -> event.y));

        for (Event event : events) {
            if (event.participant != null) {
                active = database.upsertParticipant(currentReportId, event.participant);
                activeParticipants.put(side, active);
            } else if (event.unit != null && active >= 0) {
                UnitFrame unit = event.unit;
                try {
                    String signature = database.ensureUnitType(unit.iconHash, png(unit.icon), unit.tier);
                    database.upsertUnit(active, signature, unit);
                    database.markProgress(active, true, false, false);
                } finally {
                    if (unit.icon != null && !unit.icon.isRecycled()) unit.icon.recycle();
                }
            } else if (event.bonus != null && active >= 0) {
                BonusFrame bonus = event.bonus;
                database.upsertBonus(active, TextNormalization.key(bonus.label), bonus.label,
                        bonus.rawValue, bonus.primaryValue);
                database.markProgress(active, false, true, false);
            } else if (event.technologyHeader && active >= 0) {
                database.markProgress(active, false, true, false);
            } else if (event.technologyEnd && active >= 0) {
                database.markProgress(active, false, true, true);
            }
        }
        if (active >= 0) activeParticipants.put(side, active);
    }

    private String displayId(String gameTimestamp, String fingerprint) {
        Calendar calendar = Calendar.getInstance();
        if (gameTimestamp != null && !gameTimestamp.isEmpty()) {
            try {
                Calendar parsed = Calendar.getInstance();
                parsed.setTime(new SimpleDateFormat("MM-dd HH:mm", Locale.GERMANY).parse(gameTimestamp));
                calendar.set(Calendar.MONTH, parsed.get(Calendar.MONTH));
                calendar.set(Calendar.DAY_OF_MONTH, parsed.get(Calendar.DAY_OF_MONTH));
                calendar.set(Calendar.HOUR_OF_DAY, parsed.get(Calendar.HOUR_OF_DAY));
                calendar.set(Calendar.MINUTE, parsed.get(Calendar.MINUTE));
            } catch (ParseException ignored) {
                // The current date still produces a unique and readable fallback ID.
            }
        }
        String date = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(calendar.getTime());
        String suffix = fingerprint == null ? UUID.randomUUID().toString().substring(0, 8)
                : fingerprint.substring(0, Math.min(8, fingerprint.length()));
        return "KB-" + date + "-" + suffix.toUpperCase(Locale.ROOT);
    }

    private byte[] png(Bitmap bitmap) {
        if (bitmap == null) return null;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        return output.toByteArray();
    }

    private static final class Event {
        final int y;
        final ParticipantFrame participant;
        final UnitFrame unit;
        final BonusFrame bonus;
        final boolean technologyHeader;
        final boolean technologyEnd;

        private Event(int y, ParticipantFrame participant, UnitFrame unit, BonusFrame bonus,
                      boolean technologyHeader, boolean technologyEnd) {
            this.y = y; this.participant = participant; this.unit = unit; this.bonus = bonus;
            this.technologyHeader = technologyHeader; this.technologyEnd = technologyEnd;
        }

        static Event participant(ParticipantFrame value) { return new Event(value.top, value, null, null, false, false); }
        static Event unit(UnitFrame value) { return new Event(value.centerY, null, value, null, false, false); }
        static Event bonus(BonusFrame value) { return new Event(value.centerY, null, null, value, false, false); }
        static Event technologyHeader(int y) { return new Event(y, null, null, null, true, false); }
        static Event technologyEnd(int y) { return new Event(y, null, null, null, false, true); }
    }
}
