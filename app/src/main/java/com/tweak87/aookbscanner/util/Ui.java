package com.tweak87.aookbscanner.util;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class Ui {
    public static final int NAVY = Color.rgb(16, 26, 40);
    public static final int PANEL = Color.rgb(28, 43, 62);
    public static final int GREEN = Color.rgb(53, 208, 127);
    public static final int AMBER = Color.rgb(255, 200, 87);
    public static final int RED = Color.rgb(255, 93, 104);
    public static final int WHITE = Color.rgb(244, 247, 251);
    public static final int MUTED = Color.rgb(185, 198, 214);

    private Ui() {}

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static TextView text(Context context, String value, float sp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setLineSpacing(0f, 1.12f);
        return view;
    }

    public static TextView title(Context context, String value) {
        TextView view = text(context, value, 26, WHITE);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, dp(context, 8), 0, dp(context, 8));
        return view;
    }

    public static Button button(Context context, String label, int color) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setBackground(rounded(color, dp(context, 10), 0, 0));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 52));
        params.setMargins(0, dp(context, 6), 0, dp(context, 6));
        button.setLayoutParams(params);
        return button;
    }

    public static GradientDrawable rounded(int fill, float radius, int strokeColor, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(strokeWidth, strokeColor);
        return drawable;
    }

    public static LinearLayout verticalPage(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(context, 20), dp(context, 18), dp(context, 20), dp(context, 24));
        layout.setBackgroundColor(NAVY);
        return layout;
    }

    public static void setEnabled(Button button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.45f);
    }

    public static View spacer(Context context, int heightDp) {
        View view = new View(context);
        view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(context, heightDp)));
        return view;
    }
}
