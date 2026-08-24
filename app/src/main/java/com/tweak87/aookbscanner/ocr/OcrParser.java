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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OcrParser {
    private static final Pattern PLAYER = Pattern.compile("^\\s*\\(([^)#]+)\\)\\s*(.+?)\\s*$");
    private static final Pattern COORDINATE = Pattern.compile("(?i)X\\s*[:;]?\\s*(\\d+)\\s*[, .-]*Y\\s*[:;]?\\s*(\\d+)");
    private static final Pattern BATTLE_TIME = Pattern.compile("(\\d{2})[-.](\\d{2})\\s+(\\d{2}):(\\d{2})");
    private static final Pattern TIER = Pattern.compile("^(?:XII|XI|X|IX|VIII|VII|VI|V|IV|III|II|I|[1-9]|[12]\\d|30)$", Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> SUMMARY_LABELS = new HashMap<>();
    private static final Set<String> BONUS_MARKERS = new HashSet<>(Arrays.asList(
            "leben", "angriff", "verteidigung", "schaden", "blocken", "resistenz",
            "verringert", "verringerung", "zusatzsteigerung", "zusatzerhohung", "titanschaden"));

    static {
        SUMMARY_LABELS.put("insgesamt", "total");
        SUMMARY_LABELS.put("kraftverlust", "powerLoss");
        SUMMARY_LABELS.put("getotete feinde", "kills");
        SUMMARY_LABELS.put("gefallene", "fallen");
        SUMMARY_LABELS.put("uberlebende", "survivors");
        SUMMARY_LABELS.put("verwundete", "wounded");
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
        if (normalizedAll.contains("armee info")) {
            parsed.screenType = ScreenType.ARMY_INFO;
            parsed.side = findArmySide(lines);
            parseArmyInfo(parsed, lines, elements, frame);
        } else if (normalizedAll.contains("schlachtbericht") &&
                (normalizedAll.contains("sieg") || normalizedAll.contains("niederlage") || normalizedAll.contains("details ansehen"))) {
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
                if (n.equals("technologiebonus")) parsed.technologyHeaderSeen = true;
            }
        }

        List<ParticipantFrame> participants = parseParticipants(parsed, lines, elements, width, height);
        parsed.participants.addAll(participants);
        int technologyY = findTextY(lines, "technologiebonus", height + 1);
        parsed.units.addAll(parseUnitRows(parsed, elements, frame, technologyY));
        parsed.bonuses.addAll(parseBonuses(parsed, lines));
        parsed.technologyEndSeen = hasTechnologyEnd(lines);
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
                    parsed.boxes.add(new OverlayBox(line.bounds, BoxState.VALID));
                }
            }
            parsed.boxes.add(new OverlayBox(header.bounds, BoxState.VALID));
            parseSummaryFields(parsed, participant, lines, elements, header.bounds.top, end, width, height);
            result.add(participant);
        }
        return result;
    }

    private void parseSummaryFields(ParsedFrame parsed, ParticipantFrame participant,
                                    List<OcrItem> lines, List<OcrItem> elements,
                                    int start, int end, int width, int height) {
        Set<String> found = new HashSet<>();
        for (Map.Entry<String, String> entry : SUMMARY_LABELS.entrySet()) {
            OcrItem labelLine = null;
            Long value = null;
            for (OcrItem line : lines) {
                if (line.centerY() < start || line.centerY() >= end) continue;
                if (TextNormalization.normalize(line.text).contains(entry.getKey())) {
                    labelLine = line;
                    OcrItem anchor = findLabelAnchor(elements, entry.getKey(), start, end);
                    if (anchor != null) value = nearestNumberToRight(elements, anchor, width);
                    if (value == null && summaryLabelsInLine(line.text) == 1) {
                        value = NumberParser.parseLong(NumberParser.findLastNumber(line.text));
                    }
                    break;
                }
            }
            if (labelLine != null) {
                assignSummary(participant, entry.getValue(), value);
                found.add(entry.getValue());
                parsed.boxes.add(new OverlayBox(labelLine.bounds, value == null ? BoxState.INVALID : BoxState.VALID));
            }
        }
        boolean fullCardVisible = end < height * 0.88f;
        if (fullCardVisible) {
            String[] ordered = {"total", "powerLoss", "kills", "fallen", "survivors", "wounded"};
            for (int i = 0; i < ordered.length; i++) {
                if (!found.contains(ordered[i])) parsed.boxes.add(new OverlayBox(estimatedSummaryBox(start, i, width), BoxState.PENDING));
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
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            unit.tier = findTier(elements, iconBounds, unit.centerY, tolerance * 2);
            parsed.boxes.add(new OverlayBox(unit.bounds, BoxState.VALID));
            parsed.boxes.add(new OverlayBox(iconBounds, "?".equals(unit.tier) ? BoxState.PENDING : BoxState.VALID));
            units.add(unit);
        }
        return units;
    }

    private List<BonusFrame> parseBonuses(ParsedFrame parsed, List<OcrItem> lines) {
        List<BonusFrame> bonuses = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (OcrItem line : lines) {
            String normalized = TextNormalization.normalize(line.text);
            String rawValue = NumberParser.findLastNumber(line.text);
            if (rawValue == null || (!rawValue.contains("%") && !containsBonusMarker(normalized))) continue;
            int valueAt = line.text.lastIndexOf(rawValue);
            String label = valueAt > 0 ? line.text.substring(0, valueAt).trim() : "";
            String key = TextNormalization.key(label);
            if (label.length() < 3 || key.isEmpty() || isSummaryLabel(normalized) || !containsBonusMarker(normalized) || !seen.add(key)) continue;
            BonusFrame bonus = new BonusFrame();
            bonus.label = label;
            bonus.rawValue = rawValue;
            bonus.primaryValue = NumberParser.parsePrimaryDecimal(rawValue);
            bonus.bounds = line.bounds;
            bonus.centerY = line.centerY();
            bonuses.add(bonus);
            parsed.boxes.add(new OverlayBox(line.bounds, bonus.primaryValue == null ? BoxState.INVALID : BoxState.VALID));
        }
        return bonuses;
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

    private Long nearestNumberToRight(List<OcrItem> elements, OcrItem label, int width) {
        OcrItem best = null;
        double score = Double.MAX_VALUE;
        for (OcrItem element : elements) {
            if (!NumberParser.isNumericToken(element.text) || element.centerX() <= label.centerX()) continue;
            int dy = Math.abs(element.centerY() - label.centerY());
            if (dy > width * 0.04f) continue;
            double candidate = dy * 4.0 + element.centerX() - label.centerX();
            if (candidate < score) { score = candidate; best = element; }
        }
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
            String candidate = element.text.toUpperCase(Locale.ROOT).replaceAll("[^IVX0-9]", "");
            if (TIER.matcher(candidate).matches()) best = candidate;
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
