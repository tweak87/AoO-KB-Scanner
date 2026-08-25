package com.tweak87.aookbscanner.ocr;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.io.ByteArrayOutputStream;

public final class ImageHash {
    private ImageHash() {}

    public static long differenceHash(Bitmap source) {
        int usefulHeight = Math.max(1, Math.round(source.getHeight() * 0.76f));
        Bitmap body = Bitmap.createBitmap(source, 0, 0, source.getWidth(), usefulHeight);
        long hash = differenceHashFull(body);
        if (body != source) body.recycle();
        return hash;
    }

    /** Hashes the complete crop, used for the small Roman numeral badge. */
    public static long differenceHashFull(Bitmap source) {
        Bitmap scaled = Bitmap.createScaledBitmap(source, 9, 8, true);
        long hash = 0L;
        int bit = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int left = luminance(scaled.getPixel(x, y));
                int right = luminance(scaled.getPixel(x + 1, y));
                if (left > right) hash |= (1L << bit);
                bit++;
            }
        }
        if (scaled != source) scaled.recycle();
        return hash;
    }

    public static byte[] png(Bitmap bitmap) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        return stream.toByteArray();
    }

    private static int luminance(int color) {
        return (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000;
    }
}
