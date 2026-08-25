package com.tweak87.aookbscanner.ocr;

import android.graphics.Bitmap;

import com.tweak87.aookbscanner.db.ScannerDatabase;
import com.tweak87.aookbscanner.db.ScannerDatabase.EventPoints;
import com.tweak87.aookbscanner.db.ScannerDatabase.Progress;
import com.tweak87.aookbscanner.model.Models.AnalysisResult;
import com.tweak87.aookbscanner.model.Models.BonusFrame;
import com.tweak87.aookbscanner.model.Models.BoxState;
import com.tweak87.aookbscanner.model.Models.ParsedFrame;
import com.tweak87.aookbscanner.model.Models.ParticipantFrame;
import com.tweak87.aookbscanner.model.Models.ScreenType;
import com.tweak87.aookbscanner.model.Models.Side;
import com.tweak87.aookbscanner.model.Models.UnitFrame;
import com.tweak87.aookbscanner.model.Models.OverlayBox;
import com.tweak87.aookbscanner.review.FieldKeys;
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

/** Combines every overlapping OCR frame between explicit Start and Finish into one report. */
public final class ReportAssembler {
    private final ScannerDatabase database;
    private final Map<Side, Long> activeParticipants = new EnumMap<>(Side.class);
    private final Map<Side, String> activeOwners = new EnumMap<>(Side.class);
    private String currentReportId;
    private String currentDisplayId;

    public ReportAssembler(ScannerDatabase database) {
        this.database = database;
    }

    public synchronized AnalysisResult startSession(ParsedFrame header,
                                                    boolean eventMode, boolean resourceField) {
        if (currentReportId != null) return status(header.boxes);
        currentReportId = UUID.randomUUID().toString();
        String fingerprint = Hashing.sha256("scan-session|" + currentReportId);
        currentDisplayId = displayId(header.battleTimestamp, fingerprint);
        activeParticipants.clear();
        activeOwners.clear();
        database.insertReport(currentReportId, currentDisplayId, fingerprint,
                header.battleTimestamp, header.result, header.reportX, header.reportY,
                header.expectedAttackers == null ? 0 : header.expectedAttackers,
                header.expectedDefenders == null ? 0 : header.expectedDefenders,
                eventMode, resourceField);
        return status(header.boxes);
    }

    /** Reopens an existing report so a selected set of uncertain fields can be scanned again. */
    public synchronized AnalysisResult resumeSession(String reportId) {
        if (currentReportId != null || reportId == null || reportId.trim().isEmpty()) {
            return new AnalysisResult(new ArrayList<>(), "Nachscan konnte nicht gestartet werden", BoxState.INVALID);
        }
        currentReportId = reportId;
        currentDisplayId = database.reportDisplayId(reportId);
        activeParticipants.clear();
        activeOwners.clear();
        return status(new ArrayList<>());
    }

    public synchronized AnalysisResult acceptFrame(ParsedFrame frame) {
        if (currentReportId == null) {
            return new AnalysisResult(frame.boxes, "Kein Scan gestartet", BoxState.PENDING);
        }
        if (frame.screenType == ScreenType.BATTLE_SUMMARY) {
            database.updateReportHeader(currentReportId, null, frame.battleTimestamp, frame.result,
                    frame.reportX, frame.reportY, frame.expectedAttackers, frame.expectedDefenders);
        } else if (frame.screenType == ScreenType.ARMY_INFO) {
            acceptArmyFrame(frame);
        }
        return status(frame.boxes);
    }

    public synchronized AnalysisResult finishSession() {
        if (currentReportId == null) {
            return new AnalysisResult(new ArrayList<>(), "Kein Scan aktiv", BoxState.PENDING);
        }
        String display = currentDisplayId;
        database.finalizeReport(currentReportId);
        Progress progress = database.getProgress(currentReportId);
        EventPoints points = database.getEventPoints(currentReportId);
        String message = "STATUS | " + (progress.complete ? "VOLLSTÄNDIG" : "UNVOLLSTÄNDIG") +
                "\nID     | " + display +
                "\nANGR.  | " + progress.completeAttackers + "/" +
                (progress.expectedAttackers > 0 ? progress.expectedAttackers : "?") +
                "   VER. | " + progress.completeDefenders + "/" +
                (progress.expectedDefenders > 0 ? progress.expectedDefenders : "?");
        if (points.eventMode) message += "\n" + points.overlayLabel();
        currentReportId = null;
        currentDisplayId = null;
        activeParticipants.clear();
        activeOwners.clear();
        return new AnalysisResult(new ArrayList<>(), message,
                progress.complete ? BoxState.VALID : BoxState.PENDING);
    }

    public synchronized boolean isSessionActive() { return currentReportId != null; }
    public synchronized String currentReportId() { return currentReportId; }

    private AnalysisResult status(List<com.tweak87.aookbscanner.model.Models.OverlayBox> boxes) {
        Progress progress = database.getProgress(currentReportId);
        EventPoints points = database.recalculateEventPoints(currentReportId);
        String message = progress.label();
        if (points.eventMode) message += "\n" + points.overlayLabel();
        return new AnalysisResult(boxes, message, progress.complete ? BoxState.VALID : BoxState.PENDING);
    }

    private void acceptArmyFrame(ParsedFrame frame) {
        Side side = frame.side;
        if (side == Side.UNKNOWN) {
            recycleUnits(frame.units);
            return;
        }
        long active = activeParticipants.containsKey(side)
                ? activeParticipants.get(side) : database.latestParticipantId(currentReportId, side);
        String startingOwner = activeOwners.get(side);
        if ((startingOwner == null || startingOwner.isEmpty()) && active >= 0) {
            startingOwner = database.participantOwner(active);
        }
        annotateOwners(frame, side, startingOwner);

        List<Event> events = new ArrayList<>();
        for (ParticipantFrame participant : frame.participants) events.add(Event.participant(participant));
        for (UnitFrame unit : frame.units) events.add(Event.unit(unit));
        for (BonusFrame bonus : frame.bonuses) events.add(Event.bonus(bonus));
        if (frame.technologyHeaderSeen && frame.technologyHeaderY >= 0) events.add(Event.technologyHeader(frame.technologyHeaderY));
        if (frame.technologyEndSeen && frame.technologyEndY >= 0) events.add(Event.technologyEnd(frame.technologyEndY));
        events.sort(Comparator.comparingInt(event -> event.y));

        for (Event event : events) {
            if (event.participant != null) {
                active = database.upsertParticipant(currentReportId, event.participant);
                activeParticipants.put(side, active);
                activeOwners.put(side, FieldKeys.owner(event.participant.alliance, event.participant.name,
                        event.participant.x, event.participant.y));
            } else if (event.unit != null) {
                UnitFrame unit = event.unit;
                try {
                    if (active >= 0) {
                        String signature = database.ensureUnitType(unit.iconHash, unit.tierBadgeHash,
                                png(unit.icon), unit.tier);
                        remapUnitSignature(frame, unit, signature);
                        database.upsertUnit(active, signature, unit);
                        database.markProgress(active, true, false, false);
                    }
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

    private void annotateOwners(ParsedFrame frame, Side side, String startingOwner) {
        List<ParticipantFrame> participants = new ArrayList<>(frame.participants);
        participants.sort(Comparator.comparingInt(value -> value.top));
        for (OverlayBox box : frame.boxes) {
            if (box.fieldKey == null || !box.fieldKey.contains("|" + FieldKeys.CURRENT_OWNER + "|")) continue;
            String owner = startingOwner;
            for (ParticipantFrame participant : participants) {
                if (participant.top <= box.bounds.centerY()) {
                    owner = FieldKeys.owner(participant.alliance, participant.name, participant.x, participant.y);
                } else break;
            }
            box.fieldKey = FieldKeys.replaceCurrentOwner(box.fieldKey,
                    owner == null || owner.isEmpty() ? "unknown" : owner);
        }
    }

    private void remapUnitSignature(ParsedFrame frame, UnitFrame unit, String signature) {
        int tolerance = Math.max(18, unit.bounds == null ? 18 : unit.bounds.height());
        for (OverlayBox box : frame.boxes) {
            if (box.fieldKey == null || !box.fieldKey.startsWith("unit|") ||
                    Math.abs(box.bounds.centerY() - unit.centerY) > tolerance) continue;
            box.fieldKey = FieldKeys.replaceUnitSignature(box.fieldKey, unit.iconHash, signature);
        }
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
                // Current time is a readable fallback.
            }
        }
        String date = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(calendar.getTime());
        return "KB-" + date + "-" + fingerprint.substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private byte[] png(Bitmap bitmap) {
        if (bitmap == null) return null;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        return output.toByteArray();
    }

    private void recycleUnits(List<UnitFrame> units) {
        for (UnitFrame unit : units) if (unit.icon != null && !unit.icon.isRecycled()) unit.icon.recycle();
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
