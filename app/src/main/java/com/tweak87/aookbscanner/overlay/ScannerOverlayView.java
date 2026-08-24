package com.tweak87.aookbscanner.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import com.tweak87.aookbscanner.model.Models.AnalysisResult;
import com.tweak87.aookbscanner.model.Models.BoxState;
import com.tweak87.aookbscanner.model.Models.OverlayBox;

import java.util.ArrayList;
import java.util.List;

/** Touch-through HUD drawn above Age of Origins. */
public final class ScannerOverlayView extends View {
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<OverlayBox> boxes = new ArrayList<>();
    private String status = "Scanner aktiv";
    private BoxState statusState = BoxState.PENDING;
    private int sourceWidth = 1;
    private int sourceHeight = 1;

    public ScannerOverlayView(Context context) {
        super(context);
        setBackgroundColor(Color.TRANSPARENT);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(2.4f));
        label.setColor(Color.WHITE);
        label.setTextSize(dp(13));
        label.setFakeBoldText(true);
    }

    public void update(AnalysisResult result, int width, int height) {
        boxes.clear();
        if (result != null) {
            boxes.addAll(result.boxes);
            status = result.status;
            statusState = result.statusState;
        }
        sourceWidth = Math.max(width, 1);
        sourceHeight = Math.max(height, 1);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scaleX = getWidth() / (float) sourceWidth;
        float scaleY = getHeight() / (float) sourceHeight;
        for (OverlayBox box : boxes) {
            stroke.setColor(color(box.state));
            Rect r = box.bounds;
            RectF mapped = new RectF(r.left * scaleX, r.top * scaleY, r.right * scaleX, r.bottom * scaleY);
            canvas.drawRoundRect(mapped, dp(3), dp(3), stroke);
        }
        drawStatus(canvas);
    }

    private void drawStatus(Canvas canvas) {
        float padding = dp(10);
        float textWidth = label.measureText(status);
        float height = dp(34);
        float right = getWidth() - dp(8);
        float left = Math.max(dp(8), right - textWidth - padding * 2);
        float top = dp(8);
        chip.setColor(Color.argb(225, 12, 22, 33));
        chip.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(new RectF(left, top, right, top + height), dp(10), dp(10), chip);
        chip.setStyle(Paint.Style.STROKE);
        chip.setStrokeWidth(dp(2));
        chip.setColor(color(statusState));
        canvas.drawRoundRect(new RectF(left, top, right, top + height), dp(10), dp(10), chip);
        canvas.drawText(status, left + padding, top + dp(22), label);
    }

    private int color(BoxState state) {
        if (state == BoxState.VALID) return Color.rgb(60, 227, 132);
        if (state == BoxState.INVALID) return Color.rgb(255, 82, 94);
        return Color.rgb(255, 199, 84);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
