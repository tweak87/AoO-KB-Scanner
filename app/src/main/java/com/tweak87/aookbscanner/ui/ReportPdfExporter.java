package com.tweak87.aookbscanner.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;

import com.tweak87.aookbscanner.db.ScannerDatabase.EvidenceFrameRow;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Dependency-free A4 renderer for the tables plus the annotated scan document. */
public final class ReportPdfExporter {
    private static final int PAGE_WIDTH = 595;
    private static final int PAGE_HEIGHT = 842;
    private static final float LEFT = 42f;
    private static final float RIGHT = 42f;
    private static final float TOP = 54f;
    private static final float BOTTOM = 42f;

    private ReportPdfExporter() {}

    public static void write(String report, String displayId, List<EvidenceFrameRow> evidence,
                             OutputStream output) throws IOException {
        PdfDocument document = new PdfDocument();
        Paint title = paint(16f, true, Color.rgb(16, 53, 88));
        Paint body = paint(7.3f, false, Color.BLACK);
        Paint footer = paint(8f, false, Color.DKGRAY);
        Paint caption = paint(9f, true, Color.rgb(16, 53, 88));
        body.setTypeface(Typeface.MONOSPACE);
        float lineHeight = 10.5f;

        PdfDocument.Page page = null;
        Canvas canvas = null;
        int pageNumber = 0;
        float y = PAGE_HEIGHT;
        try {
            for (String sourceLine : (report == null ? "" : report).split("\\n", -1)) {
                List<String> lines = wrap(sourceLine, body, PAGE_WIDTH - LEFT - RIGHT);
                for (String line : lines) {
                    if (page == null || y + lineHeight > PAGE_HEIGHT - BOTTOM) {
                        if (page != null) {
                            drawFooter(canvas, footer, pageNumber);
                            document.finishPage(page);
                        }
                        pageNumber++;
                        page = document.startPage(new PdfDocument.PageInfo.Builder(
                                PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create());
                        canvas = page.getCanvas();
                        canvas.drawColor(Color.WHITE);
                        canvas.drawText("AoO KB Scanner", LEFT, 29f, title);
                        canvas.drawText(displayId == null ? "Kampfbericht" : displayId,
                                LEFT, 45f, footer);
                        y = TOP + lineHeight;
                    }
                    canvas.drawText(line, LEFT, y, body);
                    y += lineHeight;
                }
            }
            if (page == null) {
                pageNumber = 1;
                page = document.startPage(new PdfDocument.PageInfo.Builder(
                        PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create());
                canvas = page.getCanvas();
                canvas.drawColor(Color.WHITE);
            }
            drawFooter(canvas, footer, pageNumber);
            document.finishPage(page);

            if (evidence != null) {
                for (EvidenceFrameRow frame : evidence) {
                    Bitmap bitmap = BitmapFactory.decodeFile(frame.filePath);
                    if (bitmap == null) continue;
                    pageNumber++;
                    PdfDocument.Page evidencePage = document.startPage(new PdfDocument.PageInfo.Builder(
                            PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create());
                    Canvas evidenceCanvas = evidencePage.getCanvas();
                    evidenceCanvas.drawColor(Color.WHITE);
                    evidenceCanvas.drawText("Scan-Dokument · " +
                            (displayId == null ? "Kampfbericht" : displayId), LEFT, 29f, title);
                    String[] captionLines = frame.label().split("\\n", -1);
                    for (int i = 0; i < captionLines.length; i++) {
                        evidenceCanvas.drawText(captionLines[i], LEFT, 45f + i * 12f, caption);
                    }
                    float availableWidth = PAGE_WIDTH - LEFT - RIGHT;
                    float imageTop = 54f + captionLines.length * 12f;
                    float availableHeight = PAGE_HEIGHT - imageTop - BOTTOM;
                    float scale = Math.min(availableWidth / bitmap.getWidth(),
                            availableHeight / bitmap.getHeight());
                    float width = bitmap.getWidth() * scale;
                    float height = bitmap.getHeight() * scale;
                    float left = (PAGE_WIDTH - width) / 2f;
                    RectF destination = new RectF(left, imageTop, left + width, imageTop + height);
                    evidenceCanvas.drawBitmap(bitmap, null, destination, null);
                    bitmap.recycle();
                    drawFooter(evidenceCanvas, footer, pageNumber);
                    document.finishPage(evidencePage);
                }
            }
            document.writeTo(output);
        } finally {
            document.close();
        }
    }

    private static Paint paint(float size, boolean bold, int color) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setTextSize(size);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, bold ? Typeface.BOLD : Typeface.NORMAL));
        return paint;
    }

    private static List<String> wrap(String source, Paint paint, float width) {
        List<String> result = new ArrayList<>();
        if (source == null || source.isEmpty()) {
            result.add("");
            return result;
        }
        String remaining = source;
        while (!remaining.isEmpty()) {
            int count = paint.breakText(remaining, true, width, null);
            if (count <= 0) count = 1;
            if (count < remaining.length()) {
                int breakAt = remaining.lastIndexOf(' ', count);
                if (breakAt > 0) count = breakAt;
            }
            result.add(remaining.substring(0, count).replaceFirst("\\s+$", ""));
            remaining = remaining.substring(count).replaceFirst("^\\s+", "");
        }
        return result;
    }

    private static void drawFooter(Canvas canvas, Paint paint, int pageNumber) {
        if (canvas == null) return;
        canvas.drawText("Seite " + pageNumber, PAGE_WIDTH - RIGHT - 36f,
                PAGE_HEIGHT - 20f, paint);
    }
}
