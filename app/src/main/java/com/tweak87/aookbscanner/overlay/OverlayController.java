package com.tweak87.aookbscanner.overlay;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;

import com.tweak87.aookbscanner.model.Models.AnalysisResult;

public final class OverlayController {
    private final Context context;
    private final WindowManager windowManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ScannerOverlayView view;
    private Button controlButton;

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

    /** Shows a small touchable control without blocking the rest of the game. */
    public void showControl(String label, Runnable action) {
        main.post(() -> {
            if (!Settings.canDrawOverlays(context)) return;
            if (controlButton == null) {
                controlButton = new Button(context);
                controlButton.setAllCaps(false);
                controlButton.setTextColor(Color.WHITE);
                controlButton.setTextSize(14);
                int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;
                int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        dp(48), type, flags, PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.TOP | Gravity.END;
                params.x = dp(8);
                params.y = dp(70);
                windowManager.addView(controlButton, params);
            }
            controlButton.setText(label);
            controlButton.setEnabled(true);
            int color = label.toLowerCase().contains("beenden")
                    ? Color.rgb(215, 67, 78) : Color.rgb(34, 171, 101);
            GradientDrawable background = new GradientDrawable();
            background.setColor(color);
            background.setCornerRadius(dp(12));
            background.setStroke(dp(2), Color.WHITE);
            controlButton.setBackground(background);
            controlButton.setOnClickListener(button -> {
                controlButton.setEnabled(false);
                action.run();
            });
        });
    }

    public void hideControl() {
        main.post(() -> {
            if (controlButton == null) return;
            try {
                windowManager.removeView(controlButton);
            } catch (IllegalArgumentException ignored) {
                // Already detached.
            }
            controlButton = null;
        });
    }

    public void hide() {
        main.post(() -> {
            if (controlButton != null) {
                try {
                    windowManager.removeView(controlButton);
                } catch (IllegalArgumentException ignored) {
                    // Already detached by Android.
                }
                controlButton = null;
            }
            if (view == null) return;
            try {
                windowManager.removeView(view);
            } catch (IllegalArgumentException ignored) {
                // Already detached by Android.
            }
            view = null;
        });
    }

    private int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
