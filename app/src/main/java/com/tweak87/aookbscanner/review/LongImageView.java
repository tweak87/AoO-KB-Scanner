package com.tweak87.aookbscanner.review;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

import java.io.File;
import java.io.IOException;

/** Displays one very tall JPEG in visible tiles, avoiding GPU texture-size limits. */
public final class LongImageView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private BitmapRegionDecoder decoder;
    private Bitmap tile;
    private Rect tileSource;
    private int imageWidth;
    private int imageHeight;

    public LongImageView(Context context) {
        super(context);
        setBackgroundColor(Color.rgb(11, 21, 32));
    }

    public void setImage(File file) {
        closeDecoder();
        if (file != null && file.isFile()) {
            try {
                //noinspection deprecation -- required for Android 8 compatibility.
                decoder = BitmapRegionDecoder.newInstance(file.getAbsolutePath(), false);
                imageWidth = decoder.getWidth();
                imageHeight = decoder.getHeight();
            } catch (IOException | RuntimeException ignored) {
                decoder = null;
            }
        }
        requestLayout();
        invalidate();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = Math.max(1, MeasureSpec.getSize(widthMeasureSpec));
        int desiredHeight = imageWidth <= 0 ? 180 : Math.max(1,
                Math.round(width * imageHeight / (float) imageWidth));
        setMeasuredDimension(resolveSize(width, widthMeasureSpec),
                resolveSize(desiredHeight, heightMeasureSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (decoder == null || imageWidth <= 0 || imageHeight <= 0) return;
        float scale = getWidth() / (float) imageWidth;
        Rect clip = canvas.getClipBounds();
        int wantedTop = clamp((int) Math.floor(clip.top / scale) - 120, 0, imageHeight);
        int wantedBottom = clamp((int) Math.ceil(clip.bottom / scale) + 120, 0, imageHeight);
        if (wantedBottom <= wantedTop) return;
        if (tile == null || tileSource == null || wantedTop < tileSource.top || wantedBottom > tileSource.bottom) {
            recycleTile();
            tileSource = new Rect(0, wantedTop, imageWidth, wantedBottom);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            try {
                tile = decoder.decodeRegion(tileSource, options);
            } catch (RuntimeException ignored) {
                tile = null;
            }
        }
        if (tile == null || tileSource == null) return;
        Rect destination = new Rect(0, Math.round(tileSource.top * scale), getWidth(),
                Math.round(tileSource.bottom * scale));
        canvas.drawBitmap(tile, null, destination, paint);
    }

    @Override protected void onDetachedFromWindow() {
        recycleTile();
        super.onDetachedFromWindow();
    }

    public void close() { closeDecoder(); }

    private void closeDecoder() {
        recycleTile();
        if (decoder != null && !decoder.isRecycled()) decoder.recycle();
        decoder = null;
        imageWidth = 0;
        imageHeight = 0;
    }

    private void recycleTile() {
        if (tile != null && !tile.isRecycled()) tile.recycle();
        tile = null;
        tileSource = null;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
