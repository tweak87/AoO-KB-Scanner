package com.tweak87.aookbscanner.model;

import android.graphics.Bitmap;
import android.graphics.Rect;

import java.util.ArrayList;
import java.util.List;

public final class Models {
    private Models() {}

    public enum Side { ATTACKER, DEFENDER, UNKNOWN }
    public enum ScreenType { NONE, MESSAGE_LIST, BATTLE_SUMMARY, ARMY_INFO }
    public enum BoxState { VALID, PENDING, INVALID }

    public static final class OcrItem {
        public final String text;
        public final Rect bounds;

        public OcrItem(String text, Rect bounds) {
            this.text = text == null ? "" : text.trim();
            this.bounds = bounds == null ? new Rect() : new Rect(bounds);
        }

        public int centerX() { return bounds.centerX(); }
        public int centerY() { return bounds.centerY(); }
    }

    public static final class OverlayBox {
        public final Rect bounds;
        public final BoxState state;

        public OverlayBox(Rect bounds, BoxState state) {
            this.bounds = new Rect(bounds);
            this.state = state;
        }
    }

    public static final class ParticipantFrame {
        public Side side = Side.UNKNOWN;
        public String alliance = "";
        public String name = "";
        public Integer x;
        public Integer y;
        public Long total;
        public Long powerLoss;
        public Long kills;
        public Long fallen;
        public Long survivors;
        public Long wounded;
        public int top;

        public String stableKey() {
            // Coordinates are occasionally missed by OCR on one frame and found on the next.
            // Names are stable inside one report, so excluding coordinates prevents duplicates.
            return side.name() + "|" + alliance.trim() + "|" + name.trim();
        }

        public boolean isSummaryValid() {
            if (total == null || survivors == null || wounded == null || fallen == null) return false;
            return total == survivors + wounded + fallen;
        }
    }

    public static final class UnitFrame {
        public long iconHash;
        public long tierBadgeHash;
        public Bitmap icon;
        public String tier = "?";
        public Long survivors;
        public Long wounded;
        public Long fallen;
        public Long kills;
        public Rect bounds;
        public int centerY;
    }

    public static final class BonusFrame {
        public String label = "";
        public String rawValue = "";
        public Double primaryValue;
        public Rect bounds;
        public int centerY;
    }

    public static final class ParsedFrame {
        public ScreenType screenType = ScreenType.NONE;
        public Side side = Side.UNKNOWN;
        public String battleTimestamp = "";
        public String result = "";
        public Integer reportX;
        public Integer reportY;
        public Integer expectedAttackers;
        public Integer expectedDefenders;
        public String fingerprintSeed = "";
        public boolean technologyHeaderSeen;
        public boolean technologyEndSeen;
        public int technologyHeaderY = -1;
        public int technologyEndY = -1;
        public final List<ParticipantFrame> participants = new ArrayList<>();
        public final List<UnitFrame> units = new ArrayList<>();
        public final List<BonusFrame> bonuses = new ArrayList<>();
        public final List<OverlayBox> boxes = new ArrayList<>();
        public String debugSummary = "";
    }

    public static final class AnalysisResult {
        public final List<OverlayBox> boxes;
        public final String status;
        public final BoxState statusState;

        public AnalysisResult(List<OverlayBox> boxes, String status, BoxState statusState) {
            this.boxes = boxes;
            this.status = status;
            this.statusState = statusState;
        }
    }
}
