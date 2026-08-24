package com.tweak87.aookbscanner.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Small dependency-free A4 PDF renderer for the plain-text report. */
public final class ReportPdfExporter {
    private static final int PAGE_WIDTH = 595;
    private static final int PAGE_HEIGHT = 842;
    private static final float LEFT = 42f;
    private static final float RIGHT = 42f;
    private static final float TOP = 54f;
    private static final float BOTTOM = 42f;

    private ReportPdfExporter() {}

    public static void write(String report, String displayId, OutputStream output) throws IOException {
        PdfDocument document = new PdfDocument();
        Paint title = paint(16f, true, Color.rgb(16, 53, 88));
        Paint body = paint(9.5f, false, Color.BLACK);
        Paint footer = paint(8f, false, Color.DKGRAY);
        float lineHeight = 13.5f;

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
