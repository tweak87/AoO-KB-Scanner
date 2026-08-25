package com.tweak87.aookbscanner.capture;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;

import com.tweak87.aookbscanner.db.ScannerDatabase;
import com.tweak87.aookbscanner.model.Models.AnalysisResult;
import com.tweak87.aookbscanner.model.Models.BoxState;
import com.tweak87.aookbscanner.model.Models.OverlayBox;
import com.tweak87.aookbscanner.model.Models.ParsedFrame;
import com.tweak87.aookbscanner.ocr.ImageHash;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;

/** Persists a compact, annotated and de-duplicated visual audit trail for one scan. */
public final class EvidenceStore {
    private static final int MAX_WIDTH = 720;
    private static final int MAX_FRAMES = 36;
    private static final long MIN_INTERVAL_MS = 1150;
    private static final int MAX_DUPLICATE_DISTANCE = 3;

    private final Context context;
    private final ScannerDatabase database;
    private String reportId;
    private int sequence;
    private long lastSavedAt;
    private long lastHash;
    private boolean hasLastHash;

    public EvidenceStore(Context context, ScannerDatabase database) {
        this.context = context.getApplicationContext();
        this.database = database;
    }

    public synchronized void begin(String newReportId) {
        reportId = newReportId;
        sequence = database.evidenceCount(newReportId);
        lastSavedAt = 0;
        hasLastHash = false;
    }

    public synchronized void finish() {
        reportId = null;
        hasLastHash = false;
    }

    public synchronized void capture(Bitmap source, ParsedFrame parsed, AnalysisResult analysis) {
        if (reportId == null || source == null || source.isRecycled() || sequence >= MAX_FRAMES) return;
        long now = SystemClock.elapsedRealtime();
        if (lastSavedAt > 0 && now - lastSavedAt < MIN_INTERVAL_MS) return;
        long hash = ImageHash.differenceHash(source);
        if (hasLastHash && Long.bitCount(hash ^ lastHash) <= MAX_DUPLICATE_DISTANCE) return;

        float scale = Math.min(1f, MAX_WIDTH / (float) source.getWidth());
        int width = Math.max(1, Math.round(source.getWidth() * scale));
        int height = Math.max(1, Math.round(source.getHeight() * scale));
        Bitmap annotated = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(annotated);
        canvas.drawBitmap(source, null, new Rect(0, 0, width, height), null);

        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(3f, 4f * scale));
        int recognized = 0;
        int pending = 0;
        int invalid = 0;
        for (OverlayBox box : analysis.boxes) {
            if (box.state == BoxState.VALID) recognized++;
            else if (box.state == BoxState.INVALID) invalid++;
            else pending++;
            stroke.setColor(color(box.state));
            Rect bounds = box.bounds;
            RectF mapped = new RectF(bounds.left * scale, bounds.top * scale,
                    bounds.right * scale, bounds.bottom * scale);
            canvas.drawRoundRect(mapped, 5f, 5f, stroke);
        }

        File directory = new File(context.getFilesDir(), "scan-evidence/" + reportId);
        if (!directory.exists() && !directory.mkdirs()) {
            annotated.recycle();
            return;
        }
        int next = sequence + 1;
        File destination = new File(directory, String.format(Locale.ROOT, "%03d.jpg", next));
        try (FileOutputStream output = new FileOutputStream(destination)) {
            if (!annotated.compress(Bitmap.CompressFormat.JPEG, 84, output)) {
                destination.delete();
                return;
            }
            sequence = next;
            database.insertEvidenceFrame(reportId, sequence, destination.getAbsolutePath(),
                    parsed.screenType.name(), parsed.side.name(), recognized, pending, invalid);
            lastHash = hash;
            hasLastHash = true;
            lastSavedAt = now;
        } catch (IOException ignored) {
            destination.delete();
        } finally {
            annotated.recycle();
        }
    }

    private static int color(BoxState state) {
        if (state == BoxState.VALID) return Color.rgb(38, 225, 118);
        if (state == BoxState.INVALID) return Color.rgb(255, 58, 73);
        return Color.rgb(255, 194, 55);
    }
}
