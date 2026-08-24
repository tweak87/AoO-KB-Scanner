package com.tweak87.aookbscanner.overlay;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;

import com.tweak87.aookbscanner.model.Models.AnalysisResult;

public final class OverlayController {
    private final Context context;
    private final WindowManager windowManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ScannerOverlayView view;

    public OverlayController(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
    }

    public void show() {
        main.post(() -> {
            if (view != null || !Settings.canDrawOverlays(context)) return;
            view = new ScannerOverlayView(context);
            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    flags,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            windowManager.addView(view, params);
        });
    }

    public void update(AnalysisResult result, int sourceWidth, int sourceHeight) {
        main.post(() -> {
            if (view != null) view.update(result, sourceWidth, sourceHeight);
        });
    }

    public void hide() {
        main.post(() -> {
            if (view == null) return;
            try {
                windowManager.removeView(view);
            } catch (IllegalArgumentException ignored) {
                // Already detached by Android.
            }
            view = null;
        });
    }
}
