package com.tweak87.aookbscanner.review;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.tweak87.aookbscanner.db.ScannerDatabase;
import com.tweak87.aookbscanner.db.ScannerDatabase.EvidenceBoxRow;
import com.tweak87.aookbscanner.db.ScannerDatabase.EvidenceFrameRow;
import com.tweak87.aookbscanner.db.ScannerDatabase.ReviewFieldRow;
import com.tweak87.aookbscanner.db.ScannerDatabase.ReviewSnapshot;
import com.tweak87.aookbscanner.db.ScannerDatabase.ReviewState;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds one continuous, de-duplicated visual document from all report frames. */
public final class ReportCollageBuilder {
    private static final int DEFAULT_WIDTH = 420;
    private static final int SMALL_WIDTH = 320;
    private static final int TINY_WIDTH = 240;
    private static final int MAX_HEIGHT = 30_000;
    private static final int HEADER_HEIGHT = 112;
    private static final int SECTION_HEIGHT = 34;

    private final Context context;
    private final ScannerDatabase database;

    public ReportCollageBuilder(Context context, ScannerDatabase database) {
        this.context = context.getApplicationContext();
        this.database = database;
    }

    public static File file(Context context, String reportId) {
        return new File(new File(context.getFilesDir(), "report-collages"), reportId + ".jpg");
    }

    public synchronized File build(String reportId) {
        List<EvidenceFrameRow> frames = database.listEvidence(reportId);
        if (frames.isEmpty()) return null;
        ReviewSnapshot review = database.reviewSnapshot(reportId);
        Layout layout = layout(frames, DEFAULT_WIDTH);
        if (layout.height + missingFooterHeight(review) > MAX_HEIGHT) layout = layout(frames, SMALL_WIDTH);
        if (layout.height + missingFooterHeight(review) > MAX_HEIGHT) layout = layout(frames, TINY_WIDTH);
        if (layout.height + missingFooterHeight(review) > MAX_HEIGHT) {
            int adaptiveWidth = Math.max(140, Math.round(TINY_WIDTH * MAX_HEIGHT /
                    (float) (layout.height + missingFooterHeight(review))));
            layout = layout(frames, adaptiveWidth);
        }
        int footerHeight = Math.min(missingFooterHeight(review),
                Math.max(0, MAX_HEIGHT - layout.height));
        int finalHeight = Math.min(MAX_HEIGHT, layout.height + footerHeight);
        if (finalHeight <= HEADER_HEIGHT) return null;

        Bitmap document;
        try {
            document = Bitmap.createBitmap(layout.width, finalHeight, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError error) {
            return null;
        }
        Canvas canvas = new Canvas(document);
        canvas.drawColor(Color.rgb(11, 21, 32));
        drawHeader(canvas, layout.width, review);
        List<DrawBox> drawBoxes = new ArrayList<>();
        for (Piece piece : layout.pieces) {
            if (piece.startY >= finalHeight) break;
            if (piece.sectionHeader) drawSection(canvas, piece.startY - SECTION_HEIGHT,
                    layout.width, sectionName(piece.frame));
            Bitmap scaled = decodeScaled(piece.frame.filePath, layout.width);
            if (scaled == null) continue;
            int bottom = Math.min(piece.cropBottom, scaled.getHeight());
            int visibleHeight = Math.min(bottom - piece.cropTop, finalHeight - piece.startY);
            if (visibleHeight > 0) {
                Rect source = new Rect(0, piece.cropTop, scaled.getWidth(), piece.cropTop + visibleHeight);
                Rect destination = new Rect(0, piece.startY, layout.width, piece.startY + visibleHeight);
                canvas.drawBitmap(scaled, source, destination, null);
                for (EvidenceBoxRow box : database.listEvidenceBoxes(piece.frame.id)) {
                    if (box.fieldKey == null) continue;
                    float center = ((box.top + box.bottom) / 2f) * scaled.getHeight();
                    if (center < piece.cropTop || center > piece.cropTop + visibleHeight) continue;
                    RectF mapped = new RectF(box.left * layout.width,
                            piece.startY + box.top * scaled.getHeight() - piece.cropTop,
                            box.right * layout.width,
                            piece.startY + box.bottom * scaled.getHeight() - piece.cropTop);
                    drawBoxes.add(new DrawBox(mapped, review.stateFor(box)));
                }
            }
            scaled.recycle();
        }
        for (DrawBox box : drawBoxes) drawBox(canvas, box, layout.width);
        if (footerHeight > 0 && layout.height < finalHeight) {
            drawMissingFooter(canvas, review, layout.height, layout.width, finalHeight);
        }

        File destination = file(context, reportId);
        File directory = destination.getParentFile();
        if (directory == null || (!directory.exists() && !directory.mkdirs())) {
            document.recycle();
            return null;
        }
        try (FileOutputStream output = new FileOutputStream(destination, false)) {
            if (!document.compress(Bitmap.CompressFormat.JPEG, 91, output)) return null;
            return destination;
        } catch (IOException error) {
            destination.delete();
            return null;
        } finally {
            document.recycle();
        }
    }

    private Layout layout(List<EvidenceFrameRow> frames, int width) {
        List<Piece> pieces = new ArrayList<>();
        int y = HEADER_HEIGHT;
        Bitmap previous = null;
        int previousTop = 0;
        int previousBottom = 0;
        EvidenceFrameRow previousFrame = null;
        for (EvidenceFrameRow frame : frames) {
            Bitmap current = decodeScaled(frame.filePath, width);
            if (current == null) continue;
            int cropTop = cropTop(frame, current.getHeight());
            int cropBottom = cropBottom(frame, current.getHeight());
            boolean section = previousFrame == null || !sectionKey(previousFrame).equals(sectionKey(frame));
            if (section) y += SECTION_HEIGHT;
            int overlap = 0;
            if (!section && previous != null && "ARMY_INFO".equals(frame.screenType)) {
                overlap = findOverlap(previous, previousTop, previousBottom, current, cropTop, cropBottom);
            }
            int start = Math.max(HEADER_HEIGHT, y - overlap);
            pieces.add(new Piece(frame, cropTop, cropBottom, start, section));
            y = start + cropBottom - cropTop;
            if (previous != null) previous.recycle();
            previous = current;
            previousTop = cropTop;
            previousBottom = cropBottom;
            previousFrame = frame;
        }
        if (previous != null) previous.recycle();
        return new Layout(width, y, pieces);
    }

    private int findOverlap(Bitmap previous, int previousTop, int previousBottom,
                            Bitmap current, int currentTop, int currentBottom) {
        int maximum = Math.min(previousBottom - previousTop, currentBottom - currentTop) * 68 / 100;
        int minimum = Math.max(36, Math.min(maximum, current.getWidth() / 8));
        if (maximum <= minimum) return 0;
        double bestScore = Double.MAX_VALUE;
        double bestRaw = Double.MAX_VALUE;
        int best = 0;
        int step = Math.max(8, maximum / 38);
        for (int overlap = minimum; overlap <= maximum; overlap += step) {
            double raw = difference(previous, previousBottom - overlap,
                    current, currentTop, overlap);
            double adjusted = raw - overlap * .008;
            if (adjusted < bestScore) {
                bestScore = adjusted;
                bestRaw = raw;
                best = overlap;
            }
        }
        return bestRaw <= 30d ? best : 0;
    }

    private double difference(Bitmap first, int firstTop, Bitmap second, int secondTop, int height) {
        long total = 0;
        int samples = 0;
        int stepX = Math.max(12, first.getWidth() / 22);
        int stepY = Math.max(6, height / 24);
        for (int y = stepY / 2; y < height; y += stepY) {
            int firstY = Math.min(first.getHeight() - 1, firstTop + y);
            int secondY = Math.min(second.getHeight() - 1, secondTop + y);
            for (int x = first.getWidth() / 12; x < first.getWidth() * 11 / 12; x += stepX) {
                int a = first.getPixel(x, firstY);
                int b = second.getPixel(x, secondY);
                int la = (Color.red(a) * 3 + Color.green(a) * 6 + Color.blue(a)) / 10;
                int lb = (Color.red(b) * 3 + Color.green(b) * 6 + Color.blue(b)) / 10;
                total += Math.abs(la - lb);
                samples++;
            }
        }
        return samples == 0 ? 255d : total / (double) samples;
    }

    private Bitmap decodeScaled(String path, int width) {
        Bitmap source = BitmapFactory.decodeFile(path);
        if (source == null) return null;
        int height = Math.max(1, Math.round(source.getHeight() * width / (float) source.getWidth()));
        if (source.getWidth() == width) return source;
        Bitmap scaled = Bitmap.createScaledBitmap(source, width, height, true);
        source.recycle();
        return scaled;
    }

    private int cropTop(EvidenceFrameRow frame, int height) {
        return "ARMY_INFO".equals(frame.screenType) ? Math.round(height * .17f) : Math.round(height * .02f);
    }

    private int cropBottom(EvidenceFrameRow frame, int height) {
        return "ARMY_INFO".equals(frame.screenType) ? Math.round(height * .89f) : Math.round(height * .98f);
    }

    private void drawHeader(Canvas canvas, int width, ReviewSnapshot review) {
        Paint title = textPaint(21f, true, Color.WHITE);
        Paint body = textPaint(13f, true, Color.rgb(205, 218, 230));
        canvas.drawText("ZUSAMMENHÄNGENDES SCAN-DOKUMENT", 14, 31, title);
        canvas.drawText("Abgleich mit dem aktuell gespeicherten Bericht", 14, 53, body);
        drawLegend(canvas, 14, 72, ReviewState.EXACT, "sicher " + review.exactCount);
        drawLegend(canvas, width / 3f + 4, 72, ReviewState.LIKELY, "prüfen " + review.likelyCount);
        drawLegend(canvas, width * 2f / 3f + 4, 72, ReviewState.MISSING, "fehlt " + review.missingCount);
    }

    private void drawLegend(Canvas canvas, float x, float y, ReviewState state, String text) {
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(color(state));
        canvas.drawRoundRect(new RectF(x, y, x + 13, y + 13), 3, 3, fill);
        Paint label = textPaint(11f, false, Color.WHITE);
        canvas.drawText(text, x + 18, y + 12, label);
    }

    private void drawSection(Canvas canvas, int top, int width, String label) {
        Paint fill = new Paint();
        fill.setColor(Color.rgb(18, 51, 77));
        canvas.drawRect(0, top, width, top + SECTION_HEIGHT, fill);
        Paint text = textPaint(15f, true, Color.WHITE);
        canvas.drawText(label, 13, top + 23, text);
    }

    private void drawBox(Canvas canvas, DrawBox box, int width) {
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(2.5f, width / 150f));
        stroke.setColor(color(box.state));
        canvas.drawRoundRect(box.bounds, 5, 5, stroke);
        String marker = box.state == ReviewState.EXACT ? "OK" :
                box.state == ReviewState.LIKELY ? "?" : "FEHLT";
        Paint label = textPaint(10f, true, Color.WHITE);
        float labelWidth = label.measureText(marker) + 10;
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(color(box.state));
        float top = Math.max(0, box.bounds.top - 17);
        canvas.drawRoundRect(new RectF(box.bounds.left, top,
                Math.min(width, box.bounds.left + labelWidth), top + 17), 4, 4, fill);
        canvas.drawText(marker, box.bounds.left + 5, top + 12, label);
    }

    private void drawMissingFooter(Canvas canvas, ReviewSnapshot review, int top, int width, int bottom) {
        Paint background = new Paint();
        background.setColor(Color.rgb(20, 30, 42));
        canvas.drawRect(0, top, width, bottom, background);
        Paint title = textPaint(15f, true, color(ReviewState.MISSING));
        Paint row = textPaint(11f, false, Color.WHITE);
        canvas.drawText("FEHLENDE FELDER IM FINALEN BERICHT", 12, top + 23, title);
        int y = top + 43;
        int shown = 0;
        for (ReviewFieldRow field : review.fields) {
            if (field.state != ReviewState.MISSING || y + 17 > bottom || shown >= 40) continue;
            canvas.drawText("• " + shorten(field.label, 58), 14, y, row);
            y += 17;
            shown++;
        }
    }

    private int missingFooterHeight(ReviewSnapshot review) {
        return review.missingCount == 0 ? 0 : 46 + Math.min(40, review.missingCount) * 17;
    }

    private String sectionName(EvidenceFrameRow frame) {
        if ("BATTLE_SUMMARY".equals(frame.screenType)) return "BERICHTSÜBERSICHT";
        if ("ATTACKER".equals(frame.side)) return "ANGREIFER – DETAILS";
        if ("DEFENDER".equals(frame.side)) return "VERTEIDIGER – DETAILS";
        return "WEITERE ANSICHT";
    }

    private String sectionKey(EvidenceFrameRow frame) {
        return "BATTLE_SUMMARY".equals(frame.screenType) ? "SUMMARY" : frame.screenType + "|" + frame.side;
    }

    private static Paint textPaint(float size, boolean bold, int color) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setTextSize(size);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, bold ? Typeface.BOLD : Typeface.NORMAL));
        return paint;
    }

    private static int color(ReviewState state) {
        if (state == ReviewState.EXACT) return Color.rgb(36, 204, 113);
        if (state == ReviewState.LIKELY) return Color.rgb(246, 174, 45);
        return Color.rgb(235, 72, 86);
    }

    private static String shorten(String value, int max) {
        if (value == null) return "Unbekanntes Feld";
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private static final class Piece {
        final EvidenceFrameRow frame;
        final int cropTop, cropBottom, startY;
        final boolean sectionHeader;
        Piece(EvidenceFrameRow frame, int cropTop, int cropBottom, int startY, boolean sectionHeader) {
            this.frame = frame; this.cropTop = cropTop; this.cropBottom = cropBottom;
            this.startY = startY; this.sectionHeader = sectionHeader;
        }
    }

    private static final class Layout {
        final int width, height;
        final List<Piece> pieces;
        Layout(int width, int height, List<Piece> pieces) {
            this.width = width; this.height = height; this.pieces = pieces;
        }
    }

    private static final class DrawBox {
        final RectF bounds;
        final ReviewState state;
        DrawBox(RectF bounds, ReviewState state) { this.bounds = bounds; this.state = state; }
    }
}
