package com.tweak87.aookbscanner.ocr;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.mlkit.vision.text.Text;
import com.tweak87.aookbscanner.model.Models.BonusFrame;
import com.tweak87.aookbscanner.model.Models.BoxState;
import com.tweak87.aookbscanner.model.Models.OcrItem;
import com.tweak87.aookbscanner.model.Models.OverlayBox;
import com.tweak87.aookbscanner.model.Models.ParsedFrame;
import com.tweak87.aookbscanner.model.Models.ParticipantFrame;
import com.tweak87.aookbscanner.model.Models.ScreenType;
import com.tweak87.aookbscanner.model.Models.Side;
import com.tweak87.aookbscanner.model.Models.UnitFrame;
import com.tweak87.aookbscanner.review.FieldKeys;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OcrParser {
    private static final Pattern PLAYER = Pattern.compile("^\\s*\\(([^)#]+)\\)\\s*(.+?)\\s*$");
    private static final Pattern COORDINATE = Pattern.compile("(?i)X\\s*[:;]?\\s*(\\d+)\\s*[, .-]*Y\\s*[:;]?\\s*(\\d+)");
    private static final Pattern BATTLE_TIME = Pattern.compile("(\\d{2})[-.](\\d{2})\\s+(\\d{2}):(\\d{2})");
    private static final Pattern TIER = Pattern.compile(
            "^(?:XIII|XII|XI|X|IX|VIII|VII|VI|V|IV|III|II|I)$",
            Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> SUMMARY_LABELS = new HashMap<>();
    private static final Set<String> BONUS_MARKERS = new HashSet<>(Arrays.asList(
            "leben", "angriff", "verteidigung", "schaden", "blocken", "resistenz",
            "verringert", "verringerung", "zusatzsteigerung", "zusatzerhohung", "titanschaden"));
    private final Map<String, String> configuredStatusAliases;

    static {
        SUMMARY_LABELS.put("insgesamt", "total");
        SUMMARY_LABELS.put("kraftverlust", "powerLoss");
        SUMMARY_LABELS.put("getotete feinde", "kills");
        SUMMARY_LABELS.put("gefallene", "fallen");
        SUMMARY_LABELS.put("uberlebende", "survivors");
        SUMMARY_LABELS.put("verwundete", "wounded");
    }

    public OcrParser() {
        this(Collections.emptyMap());
    }

    public OcrParser(Map<String, String> configuredStatusAliases) {
        this.configuredStatusAliases = configuredStatusAliases == null
                ? Collections.emptyMap() : configuredStatusAliases;
    }

    public ParsedFrame parse(Text result, Bitmap frame) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        List<OcrItem> lines = new ArrayList<>();
        List<OcrItem> elements = new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                if (line.getBoundingBox() != null) lines.add(new OcrItem(line.getText(), line.getBoundingBox()));
                for (Text.Element element : line.getElements()) {
                    if (element.getBoundingBox() != null) elements.add(new OcrItem(element.getText(), element.getBoundingBox()));
                }
            }
        }
        Comparator<OcrItem> order = Comparator.comparingInt(OcrItem::centerY).thenComparingInt(OcrItem::centerX);
        Collections.sort(lines, order);
        Collections.sort(elements, order);

        ParsedFrame parsed = new ParsedFrame();
        String allText = result.getText();
        String normalizedAll = TextNormalization.normalize(allText);
        int armyScore = score(normalizedAll, "armee info", "technologiebonus", "uberlebende",
                "verwundete", "gefallene", "getotete feinde");
        int battleScore = score(normalizedAll, "schlachtbericht", "angreifer", "verteidiger",
                "teilnehmer", "insgesamt", "kraftverlust", "details ansehen", "video replay", "position");
        boolean armyInfo = normalizedAll.contains("armee info") ||
                ((normalizedAll.contains("angreifer") || normalizedAll.contains("verteidiger")) && armyScore >= 4);
        boolean battleSummary = (normalizedAll.contains("schlachtbericht") && battleScore >= 3) || battleScore >= 6;
        if (armyInfo) {
            parsed.screenType = ScreenType.ARMY_INFO;
            parsed.side = findArmySide(lines);
            parseArmyInfo(parsed, lines, elements, frame);
        } else if (battleSummary) {
            parsed.screenType = ScreenType.BATTLE_SUMMARY;
            parseBattleSummary(parsed, lines, width);
        } else if (looksLikeMessageList(normalizedAll)) {
            parsed.screenType = ScreenType.MESSAGE_LIST;
        }
        parsed.debugSummary = parsed.screenType + " / " + parsed.side + " / P=" + parsed.participants.size()
                + " U=" + parsed.units.size() + " B=" + parsed.bonuses.size();
        return parsed;
    }

    private void parseBattleSummary(ParsedFrame parsed, List<OcrItem> lines, int width) {
        List<String> identity = new ArrayList<>();
        for (OcrItem line : lines) {
            String normalized = TextNormalization.normalize(line.text);
            Matcher time = BATTLE_TIME.matcher(line.text);
            if (time.find()) {
                parsed.battleTimestamp = time.group();
                identity.add("time=" + parsed.battleTimestamp);
                parsed.boxes.add(new OverlayBox(line.bounds, BoxState.VALID));
            }
            if (normalized.equals("sieg") || normalized.equals("niederlage")) {
                parsed.result = normalized.equals("sieg") ? "Sieg" : "Niederlage";
                identity.add("result=" + parsed.result);
                parsed.boxes.add(new OverlayBox(line.bounds, BoxState.VALID));
            }
            Matcher coordinate = COORDINATE.matcher(line.text.replace(" ", ""));
            if (coordinate.find()) {
                int x = Integer.parseInt(coordinate.group(1));
                int y = Integer.parseInt(coordinate.group(2));
                identity.add("xy=" + x + ":" + y);
                if (normalized.contains("position")) {
                    parsed.reportX = x;
                    parsed.reportY = y;
                }
                parsed.boxes.add(new OverlayBox(line.bounds, BoxState.VALID));
            }
            if (normalized.contains("teilnehmer")) {
                Long count = NumberParser.parseLong(NumberParser.findLastNumber(line.text));
                if (count == null) count = nearestNumberOnSameRow(lines, line, width);
                if (count != null) {
                    if (line.centerX() < width / 2) parsed.expectedAttackers = count.intValue();
                    else parsed.expectedDefenders = count.intValue();
                    identity.add((line.centerX() < width / 2 ? "a=" : "d=") + count);
                    parsed.boxes.add(new OverlayBox(line.bounds, BoxState.VALID));
                }
            }
            if (normalized.contains("insgesamt") || normalized.contains("kraftverlust") ||
                    line.text.matches(".*\\(#?\\d+\\).*")) {
                identity.add(normalized);
                parsed.boxes.add(new OverlayBox(line.bounds,
                        NumberParser.findLastNumber(line.text) != null || line.text.contains("(") ? BoxState.VALID : BoxState.PENDING));
            }
        }
        Collections.sort(identity);
        parsed.fingerprintSeed = String.join("|", identity);
    }

    private void parseArmyInfo(ParsedFrame parsed, List<OcrItem> lines, List<OcrItem> elements, Bitmap frame) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        for (OcrItem line : lines) {
            String n = TextNormalization.normalize(line.text);
            if (n.equals("armee info") || n.equals("angreifer") || n.equals("verteidiger") || n.equals("technologiebonus")) {
                parsed.boxes.add(new OverlayBox(line.bounds, BoxState.VALID));
                if (n.equals("technologiebonus")) {
                    parsed.technologyHeaderSeen = true;
                    parsed.technologyHeaderY = line.centerY();
                }
            }
        }

        List<ParticipantFrame> participants = parseParticipants(parsed, lines, elements, width, height);
        parsed.participants.addAll(participants);
        int technologyY = findTextY(lines, "technologiebonus", height + 1);
        parsed.units.addAll(parseUnitRows(parsed, elements, frame, technologyY));
        parsed.bonuses.addAll(parseBonuses(parsed, lines, elements, width, height));
        parsed.technologyEndSeen = hasTechnologyEnd(lines);
        if (parsed.technologyEndSeen) parsed.technologyEndY = technologyEndY(lines);
    }

    private List<ParticipantFrame> parseParticipants(ParsedFrame parsed, List<OcrItem> lines,
                                                     List<OcrItem> elements, int width, int height) {
        List<OcrItem> headers = new ArrayList<>();
        for (OcrItem line : lines) {
            Matcher matcher = PLAYER.matcher(line.text);
            if (matcher.matches() && !matcher.group(1).matches("#?\\d+")) headers.add(line);
        }
        List<ParticipantFrame> result = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) {
            OcrItem header = headers.get(i);
            Matcher matcher = PLAYER.matcher(header.text);
            if (!matcher.matches()) continue;
            ParticipantFrame participant = new ParticipantFrame();
            participant.side = parsed.side;
            participant.alliance = matcher.group(1).trim();
            participant.name = matcher.group(2).trim();
            Matcher coordinateInName = COORDINATE.matcher(participant.name.replace(" ", ""));
            if (coordinateInName.find()) {
                int rawCoordinate = participant.name.toUpperCase(Locale.ROOT).lastIndexOf('X');
                if (rawCoordinate > 0) participant.name = participant.name.substring(0, rawCoordinate).trim();
            }
            participant.top = header.bounds.top;
            int end = Math.min(height, i + 1 < headers.size() ? headers.get(i + 1).bounds.top :
                    header.bounds.top + Math.round(width * 0.31f));
            for (OcrItem line : lines) {
                if (line.centerY() < header.bounds.top || line.centerY() >= end) continue;
                Matcher coordinate = COORDINATE.matcher(line.text.replace(" ", ""));
                if (coordinate.find()) {
                    participant.x = Integer.parseInt(coordinate.group(1));
                    participant.y = Integer.parseInt(coordinate.group(2));
                    parsed.boxes.add(new OverlayBox(line.bounds, BoxState.VALID,
                            FieldKeys.participant(participant.side, participant.alliance, participant.name,
                                    participant.x, participant.y, "x"),
                            participant.name + " · Position X", Integer.toString(participant.x), .98f));
                    parsed.boxes.add(new OverlayBox(line.bounds, BoxState.VALID,
                            FieldKeys.participant(participant.side, participant.alliance, participant.name,
                                    participant.x, participant.y, "y"),
                            participant.name + " · Position Y", Integer.toString(participant.y), .98f));
                }
            }
            parsed.boxes.add(new OverlayBox(header.bounds, BoxState.VALID,
                    FieldKeys.participant(participant.side, participant.alliance, participant.name,
                            participant.x, participant.y, "name"),
                    "Spielername", participant.name, .98f));
            parsed.boxes.add(new OverlayBox(header.bounds, BoxState.VALID,
                    FieldKeys.participant(participant.side, participant.alliance, participant.name,
                            participant.x, participant.y, "alliance"),
                    "Allianz", participant.alliance, .98f));
            parseSummaryFields(parsed, participant, lines, elements, header.bounds.top, end, width, height);
            result.add(participant);
        }
        return result;
    }

    private void parseSummaryFields(ParsedFrame parsed, ParticipantFrame participant,
                                    List<OcrItem> lines, List<OcrItem> elements,
                                    int start, int end, int width, int height) {
        Set<String> found = new HashSet<>();
        Map<String, Rect> boundsByField = new HashMap<>();
        Map<String, Long> valueByField = new HashMap<>();
        for (Map.Entry<String, String> entry : SUMMARY_LABELS.entrySet()) {
            OcrItem labelLine = null;
            Long value = null;
            Rect fieldBounds = null;
            for (OcrItem line : lines) {
                if (line.centerY() < start || line.centerY() >= end) continue;
                if (TextNormalization.normalize(line.text).contains(entry.getKey())) {
                    labelLine = line;
                    OcrItem anchor = findLabelAnchor(elements, entry.getKey(), start, end);
                    OcrItem valueItem = anchor == null ? null : nearestNumericItemToRight(elements, anchor, width);
                    if (valueItem != null) value = NumberParser.parseLong(valueItem.text);
                    if (value == null && summaryLabelsInLine(line.text) == 1) {
                        value = NumberParser.parseLong(NumberParser.findLastNumber(line.text));
                    }
                    fieldBounds = anchor == null ? new Rect(line.bounds) : new Rect(anchor.bounds);
                    if (valueItem != null) fieldBounds.union(valueItem.bounds);
                    break;
                }
            }
            if (labelLine != null) {
                assignSummary(participant, entry.getValue(), value);
                found.add(entry.getValue());
                boundsByField.put(entry.getValue(), fieldBounds == null ? new Rect(labelLine.bounds) : fieldBounds);
                valueByField.put(entry.getValue(), value);
            }
        }
        boolean summaryValid = participant.isSummaryValid();
        for (String field : found) {
            Long value = valueByField.get(field);
            float confidence = value == null ? 0f : summaryValid ? .98f : .72f;
            parsed.boxes.add(new OverlayBox(boundsByField.get(field),
                    value == null ? BoxState.INVALID : summaryValid ? BoxState.VALID : BoxState.INVALID,
                    FieldKeys.participant(participant.side, participant.alliance, participant.name,
                            participant.x, participant.y, field), summaryDisplay(field),
                    FieldKeys.value(value), confidence));
        }
        boolean fullCardVisible = end < height * 0.88f;
        if (fullCardVisible) {
            String[] ordered = {"total", "powerLoss", "kills", "fallen", "survivors", "wounded"};
            for (int i = 0; i < ordered.length; i++) {
                String field = ordered[i];
                if (!found.contains(field)) parsed.boxes.add(new OverlayBox(estimatedSummaryBox(start, i, width),
                        BoxState.PENDING, FieldKeys.participant(participant.side, participant.alliance,
                        participant.name, participant.x, participant.y, field), summaryDisplay(field), null, 0f));
            }
        }
    }

    private List<UnitFrame> parseUnitRows(ParsedFrame parsed, List<OcrItem> elements,
                                          Bitmap frame, int technologyY) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        int tolerance = Math.max(12, Math.round(width * 0.018f));
        List<List<OcrItem>> groups = new ArrayList<>();
        for (OcrItem element : elements) {
            if (element.centerX() < width * 0.18f || element.centerY() < height * 0.22f ||
                    element.centerY() > height * 0.86f || element.centerY() >= technologyY ||
                    !NumberParser.isNumericToken(element.text) || element.text.contains("%")) continue;
            List<OcrItem> group = null;
            for (List<OcrItem> candidate : groups) {
                if (Math.abs(candidate.get(0).centerY() - element.centerY()) <= tolerance) { group = candidate; break; }
            }
            if (group == null) { group = new ArrayList<>(); groups.add(group); }
            group.add(element);
        }

        List<UnitFrame> units = new ArrayList<>();
        for (List<OcrItem> group : groups) {
            OcrItem[] columns = new OcrItem[4];
            for (OcrItem item : group) {
                float x = item.centerX() / (float) width;
                int column = x < 0.40f ? 0 : x < 0.59f ? 1 : x < 0.78f ? 2 : 3;
                float[] centers = {0.30f, 0.49f, 0.69f, 0.88f};
                if (columns[column] == null || Math.abs(x - centers[column]) <
                        Math.abs(columns[column].centerX() / (float) width - centers[column])) columns[column] = item;
            }
            if (columns[0] == null || columns[1] == null || columns[2] == null || columns[3] == null) continue;
            UnitFrame unit = new UnitFrame();
            unit.survivors = NumberParser.parseLong(columns[0].text);
            unit.wounded = NumberParser.parseLong(columns[1].text);
            unit.fallen = NumberParser.parseLong(columns[2].text);
            unit.kills = NumberParser.parseLong(columns[3].text);
            unit.centerY = averageY(columns);
            unit.bounds = union(columns);
            Rect iconBounds = iconBounds(width, height, unit.centerY);
            try {
                unit.icon = Bitmap.createBitmap(frame, iconBounds.left, iconBounds.top, iconBounds.width(), iconBounds.height());
                unit.iconHash = ImageHash.differenceHash(unit.icon);
                int badgeLeft = Math.max(0, Math.round(unit.icon.getWidth() * 0.58f));
                int badgeTop = Math.max(0, Math.round(unit.icon.getHeight() * 0.52f));
                Bitmap badge = Bitmap.createBitmap(unit.icon, badgeLeft, badgeTop,
                        unit.icon.getWidth() - badgeLeft, unit.icon.getHeight() - badgeTop);
                unit.tierBadgeHash = ImageHash.differenceHashFull(badge);
                badge.recycle();
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            unit.tier = findTier(elements, iconBounds, unit.centerY, tolerance * 2);
            String[] fields = {"survivors", "wounded", "fallen", "kills"};
            Long[] values = {unit.survivors, unit.wounded, unit.fallen, unit.kills};
            String[] labels = {"Überlebende", "Verwundete", "Gefallene", "Getötete Feinde"};
            for (int i = 0; i < columns.length; i++) {
                parsed.boxes.add(new OverlayBox(columns[i].bounds, BoxState.VALID,
                        FieldKeys.unit(parsed.side, FieldKeys.CURRENT_OWNER, unit.iconHash, fields[i]),
                        "Einheit · " + labels[i], FieldKeys.value(values[i]), .97f));
            }
            Rect tierBounds = new Rect(iconBounds.left + Math.round(iconBounds.width() * .52f),
                    iconBounds.top + Math.round(iconBounds.height() * .50f), iconBounds.right, iconBounds.bottom);
            parsed.boxes.add(new OverlayBox(tierBounds, "?".equals(unit.tier) ? BoxState.PENDING : BoxState.INVALID,
                    FieldKeys.unit(parsed.side, FieldKeys.CURRENT_OWNER, unit.iconHash, "tier"),
                    "Einheit · Stufe", "?".equals(unit.tier) ? null : unit.tier,
                    "?".equals(unit.tier) ? 0f : .88f));
            units.add(unit);
        }
        return units;
    }

    private List<BonusFrame> parseBonuses(ParsedFrame parsed, List<OcrItem> lines,
                                          List<OcrItem> elements, int width, int height) {
        Map<String, BonusFrame> bonuses = new LinkedHashMap<>();
        for (OcrItem line : lines) {
            String normalized = TextNormalization.normalize(line.text);
            String rawValue = NumberParser.findLastNumber(line.text);
            if (rawValue == null || (!rawValue.contains("%") && !containsBonusMarker(normalized))) continue;
            int valueAt = line.text.lastIndexOf(rawValue);
            String rawLabel = valueAt > 0 ? line.text.substring(0, valueAt).trim() : "";
            String knownLabel = BonusCatalog.matchKnown(rawLabel, configuredStatusAliases);
            // A line spanning both columns mixes two cells; the spatial pass below separates it.
            if (knownLabel == null && line.bounds.width() > width * 0.52f) continue;
            String label = knownLabel == null ? BonusCatalog.canonicalize(rawLabel) : knownLabel;
            String key = TextNormalization.key(label);
            if (label.length() < 3 || key.isEmpty() || isSummaryLabel(normalized) || !containsBonusMarker(normalized)) continue;
            putBonus(bonuses, label, rawValue, line.bounds, line.centerY(),
                    bonusConfidence(rawLabel, label, knownLabel != null));
        }

        // ML Kit often emits the small two-line label and its large value as separate lines.
        // Reconstruct each grid cell spatially and map it to the canonical label catalog.
        int headerY = findTextY(lines, "technologiebonus", height + 1);
        int tolerance = Math.max(12, Math.round(width * 0.030f));
        if (headerY < height) {
            Set<String> visitedCells = new HashSet<>();
            for (OcrItem value : elements) {
                if (!isBonusValue(value.text) || value.centerY() <= headerY ||
                        value.centerY() > height * 0.87f || value.centerX() < width * 0.24f) continue;
                boolean leftHalf = value.centerX() < width / 2;
                String cellKey = (leftHalf ? "L" : "R") + (value.centerY() / Math.max(1, tolerance));
                if (!visitedCells.add(cellKey)) continue;
                int cellLeft = Math.round(width * (leftHalf ? 0.05f : 0.50f));
                int cellRight = Math.round(width * (leftHalf ? 0.50f : 0.96f));
                List<OcrItem> values = new ArrayList<>();
                for (OcrItem candidate : elements) {
                    if (candidate.centerX() < cellLeft || candidate.centerX() >= cellRight ||
                            Math.abs(candidate.centerY() - value.centerY()) > Math.max(7, tolerance / 2) ||
                            !isBonusValue(candidate.text)) continue;
                    values.add(candidate);
                }
                if (values.isEmpty()) continue;
                values.sort(Comparator.comparingInt(OcrItem::centerX));
                int firstValueX = values.get(0).bounds.left;
                List<OcrItem> labels = new ArrayList<>();
                for (OcrItem candidate : elements) {
                    if (candidate.centerX() < cellLeft || candidate.centerX() >= cellRight ||
                            candidate.bounds.right >= firstValueX ||
                            Math.abs(candidate.centerY() - value.centerY()) > tolerance ||
                            isBonusValue(candidate.text)) continue;
                    String normalized = TextNormalization.normalize(candidate.text);
                    if (normalized.equals("technologiebonus") || normalized.equals("angreifer") ||
                            normalized.equals("verteidiger") || normalized.equals("armee info")) continue;
                    labels.add(candidate);
                }
                if (labels.isEmpty()) {
                    Rect valueBounds = new Rect(values.get(0).bounds);
                    for (OcrItem item : values) valueBounds.union(item.bounds);
                    String rawCandidate = values.get(0).text;
                    parsed.boxes.add(new OverlayBox(valueBounds, BoxState.PENDING,
                            FieldKeys.unmatched(parsed.side, valueBounds.centerX(), valueBounds.centerY()),
                            "Statusname nicht erkannt", rawCandidate, .45f));
                    continue;
                }
                labels.sort(Comparator.comparingInt(OcrItem::centerY).thenComparingInt(OcrItem::centerX));
                StringBuilder rawLabel = new StringBuilder();
                for (OcrItem label : labels) rawLabel.append(label.text).append(' ');
                String canonical = BonusCatalog.matchKnown(rawLabel.toString(), configuredStatusAliases);
                Rect bounds = new Rect(labels.get(0).bounds);
                for (OcrItem item : labels) bounds.union(item.bounds);
                for (OcrItem item : values) bounds.union(item.bounds);
                if (canonical == null) {
                    StringBuilder candidateText = new StringBuilder();
                    for (OcrItem item : values) candidateText.append(item.text).append(' ');
                    parsed.boxes.add(new OverlayBox(bounds, BoxState.PENDING,
                            FieldKeys.unmatched(parsed.side, bounds.centerX(), bounds.centerY()),
                            rawLabel.toString().trim(), candidateText.toString().trim(), .55f));
                    continue;
                }
                StringBuilder rawValue = new StringBuilder();
                for (OcrItem item : values) rawValue.append(item.text).append(' ');
                putBonus(bonuses, canonical, rawValue.toString().trim(), bounds, value.centerY(),
                        bonusConfidence(rawLabel.toString(), canonical, true));
            }
        }

        List<BonusFrame> result = new ArrayList<>(bonuses.values());
        for (BonusFrame bonus : result) {
            BoxState state = bonus.primaryValue == null ? BoxState.INVALID :
                    bonus.confidence >= .92f ? BoxState.VALID : BoxState.INVALID;
            parsed.boxes.add(new OverlayBox(bonus.bounds, state,
                    FieldKeys.bonus(parsed.side, FieldKeys.CURRENT_OWNER, bonus.label),
                    bonus.label, bonus.rawValue, bonus.confidence));
        }
        return result;
    }

    private void putBonus(Map<String, BonusFrame> bonuses, String label, String rawValue,
                          Rect bounds, int centerY, float confidence) {
        String key = TextNormalization.key(label);
        BonusFrame current = bonuses.get(key);
        if (current != null && current.primaryValue != null &&
                current.rawValue.length() >= rawValue.length()) return;
        BonusFrame bonus = new BonusFrame();
        bonus.label = label;
        bonus.rawValue = rawValue;
        bonus.primaryValue = NumberParser.parsePrimaryDecimal(rawValue);
        bonus.confidence = confidence;
        bonus.bounds = new Rect(bounds);
        bonus.centerY = centerY;
        bonuses.put(key, bonus);
    }

    private float bonusConfidence(String rawLabel, String canonical, boolean matchedKnown) {
        String raw = TextNormalization.normalize(rawLabel);
        String known = TextNormalization.normalize(canonical);
        if (!raw.isEmpty() && (raw.equals(known) || raw.contains(known) || known.contains(raw))) return .97f;
        if (configuredStatusAliases.containsKey(raw)) return .95f;
        return matchedKnown ? .88f : .70f;
    }

    private boolean isBonusValue(String text) {
        if (text == null || NumberParser.findLastNumber(text) == null) return false;
        return text.replaceAll("[\\d\\s.,%()+-]", "").isEmpty();
    }

    private boolean hasTechnologyEnd(List<OcrItem> lines) {
        StringBuilder joined = new StringBuilder();
        for (OcrItem line : lines) joined.append(' ').append(TextNormalization.normalize(line.text));
        String text = joined.toString();
        boolean titan = text.contains("verringerung von titanschaden") || text.contains("verringerung titanschaden") ||
                text.contains("schadenssteigerung gegen titanen");
        boolean troopLife = text.contains("truppen leben zusatzerhohung") || text.contains("truppen leben zusatz");
        boolean finalSpecial = text.contains("nahkampftruppen durch nah mittel und fernkampftruppen");
        return (titan && troopLife) || finalSpecial;
    }

    private int technologyEndY(List<OcrItem> lines) {
        int result = -1;
        for (OcrItem line : lines) {
            String text = TextNormalization.normalize(line.text);
            if (text.contains("titanschaden") || text.contains("titanen") ||
                    text.contains("truppen leben zusatz") ||
                    text.contains("nahkampftruppen durch nah mittel und fernkampftruppen")) {
                result = Math.max(result, line.centerY());
            }
        }
        return result;
    }

    private Side findArmySide(List<OcrItem> lines) {
        OcrItem title = null;
        for (OcrItem line : lines) if (TextNormalization.normalize(line.text).equals("armee info")) { title = line; break; }
        OcrItem best = null;
        Side side = Side.UNKNOWN;
        for (OcrItem line : lines) {
            String n = TextNormalization.normalize(line.text);
            Side candidate = n.equals("angreifer") ? Side.ATTACKER : n.equals("verteidiger") ? Side.DEFENDER : Side.UNKNOWN;
            if (candidate == Side.UNKNOWN || (title != null && line.centerY() <= title.centerY())) continue;
            if (best == null || line.centerY() < best.centerY()) { best = line; side = candidate; }
        }
        return side;
    }

    private boolean looksLikeMessageList(String normalized) {
        return normalized.contains("post") && (normalized.contains("angriff") || normalized.contains("verteidigungserfolg"));
    }

    private boolean containsBonusMarker(String normalized) {
        for (String marker : BONUS_MARKERS) if (normalized.contains(marker)) return true;
        return false;
    }

    private boolean isSummaryLabel(String normalized) {
        for (String label : SUMMARY_LABELS.keySet()) if (normalized.contains(label)) return true;
        return false;
    }

    private OcrItem nearestNumericItemToRight(List<OcrItem> elements, OcrItem label, int width) {
        OcrItem best = null;
        double score = Double.MAX_VALUE;
        for (OcrItem element : elements) {
            if (!NumberParser.isNumericToken(element.text) || element.centerX() <= label.centerX()) continue;
            int dy = Math.abs(element.centerY() - label.centerY());
            if (dy > width * 0.04f) continue;
            double candidate = dy * 4.0 + element.centerX() - label.centerX();
            if (candidate < score) { score = candidate; best = element; }
        }
        return best;
    }

    private Long nearestNumberToRight(List<OcrItem> elements, OcrItem label, int width) {
        OcrItem best = nearestNumericItemToRight(elements, label, width);
        return best == null ? null : NumberParser.parseLong(best.text);
    }

    private OcrItem findLabelAnchor(List<OcrItem> elements, String normalizedLabel, int start, int end) {
        String firstWord = normalizedLabel.split(" ")[0];
        for (OcrItem element : elements) {
            if (element.centerY() < start || element.centerY() >= end) continue;
            String normalized = TextNormalization.normalize(element.text);
            if (normalized.equals(firstWord) || normalized.startsWith(firstWord)) return element;
        }
        return null;
    }

    private int summaryLabelsInLine(String text) {
        String normalized = TextNormalization.normalize(text);
        int count = 0;
        for (String label : SUMMARY_LABELS.keySet()) if (normalized.contains(label)) count++;
        return count;
    }

    private Long nearestNumberOnSameRow(List<OcrItem> lines, OcrItem label, int width) {
        OcrItem best = null;
        int bestDistance = Integer.MAX_VALUE;
        boolean leftHalf = label.centerX() < width / 2;
        for (OcrItem candidate : lines) {
            if (candidate == label || (candidate.centerX() < width / 2) != leftHalf) continue;
            String raw = NumberParser.findLastNumber(candidate.text);
            if (raw == null || candidate.text.contains(":")) continue;
            int dy = Math.abs(candidate.centerY() - label.centerY());
            int dx = Math.abs(candidate.centerX() - label.centerX());
            if (dy > width * 0.04f || candidate.centerX() <= label.centerX()) continue;
            int distance = dy * 4 + dx;
            if (distance < bestDistance) { bestDistance = distance; best = candidate; }
        }
        return best == null ? null : NumberParser.parseLong(NumberParser.findLastNumber(best.text));
    }

    private void assignSummary(ParticipantFrame participant, String field, Long value) {
        switch (field) {
            case "total": participant.total = value; break;
            case "powerLoss": participant.powerLoss = value; break;
            case "kills": participant.kills = value; break;
            case "fallen": participant.fallen = value; break;
            case "survivors": participant.survivors = value; break;
            case "wounded": participant.wounded = value; break;
        }
    }

    private String summaryDisplay(String field) {
        switch (field) {
            case "total": return "Insgesamt";
            case "powerLoss": return "Kraftverlust";
            case "kills": return "Getötete Feinde";
            case "fallen": return "Gefallene";
            case "survivors": return "Überlebende";
            case "wounded": return "Verwundete";
            default: return field;
        }
    }

    private int score(String text, String... markers) {
        int result = 0;
        for (String marker : markers) if (text.contains(marker)) result++;
        return result;
    }

    private Rect estimatedSummaryBox(int top, int index, int width) {
        int row = index / 2;
        boolean right = index % 2 == 1;
        int left = Math.round(width * (right ? 0.58f : 0.23f));
        int r = Math.round(width * (right ? 0.95f : 0.57f));
        int y = top + Math.round(width * (0.065f + row * 0.055f));
        return new Rect(left, y, r, y + Math.round(width * 0.045f));
    }

    private Rect iconBounds(int width, int height, int centerY) {
        int left = Math.round(width * 0.068f);
        int right = Math.round(width * 0.181f);
        int half = Math.round(width * 0.053f);
        return new Rect(left, Math.max(0, centerY - half), right, Math.min(height, centerY + half));
    }

    private String findTier(List<OcrItem> elements, Rect icon, int centerY, int tolerance) {
        String best = "?";
        for (OcrItem element : elements) {
            if (Math.abs(element.centerY() - centerY) > tolerance || element.centerX() > icon.right + icon.width() / 3) continue;
            String candidate = element.text.toUpperCase(Locale.ROOT)
                    .replace('L', 'I').replace('1', 'I').replace('|', 'I').replace('!', 'I')
                    .replaceAll("[^IVX]", "");
            int tier = com.tweak87.aookbscanner.event.EventScoring.parseTier(candidate);
            if (TIER.matcher(candidate).matches() && tier >= 1 && tier <= 13) {
                best = com.tweak87.aookbscanner.event.EventScoring.romanTier(tier);
            }
        }
        return best;
    }

    private int findTextY(List<OcrItem> lines, String value, int fallback) {
        for (OcrItem line : lines) if (TextNormalization.normalize(line.text).equals(TextNormalization.normalize(value))) return line.bounds.top;
        return fallback;
    }

    private int averageY(OcrItem[] items) {
        int sum = 0;
        for (OcrItem item : items) sum += item.centerY();
        return sum / items.length;
    }

    private Rect union(OcrItem[] items) {
        Rect result = new Rect(items[0].bounds);
        for (int i = 1; i < items.length; i++) result.union(items[i].bounds);
        return result;
    }
}
