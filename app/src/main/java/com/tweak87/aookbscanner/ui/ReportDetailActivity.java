package com.tweak87.aookbscanner.ui;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tweak87.aookbscanner.db.ScannerDatabase;
import com.tweak87.aookbscanner.util.Ui;

public final class ReportDetailActivity extends Activity {
    public static final String EXTRA_REPORT_ID = "report_id";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Berichtsdetails");
        String id = getIntent().getStringExtra(EXTRA_REPORT_ID);
        TextView text = Ui.text(this, id == null ? "Bericht nicht gefunden." :
                new ScannerDatabase(this).reportDetails(id), 15, Ui.WHITE);
        text.setTextIsSelectable(true);
        text.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 28));
        text.setBackgroundColor(Ui.NAVY);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        setContentView(scroll);
    }
}
