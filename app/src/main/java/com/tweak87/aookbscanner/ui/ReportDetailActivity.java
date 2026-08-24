package com.tweak87.aookbscanner.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.tweak87.aookbscanner.db.ScannerDatabase;
import com.tweak87.aookbscanner.util.Ui;

import java.io.IOException;
import java.io.OutputStream;

public final class ReportDetailActivity extends Activity {
    public static final String EXTRA_REPORT_ID = "report_id";
    private static final int REQUEST_CREATE_PDF = 401;

    private String reportText;
    private String displayId;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Berichtsdetails");
        String id = getIntent().getStringExtra(EXTRA_REPORT_ID);
        ScannerDatabase database = new ScannerDatabase(this);
        reportText = id == null ? "Bericht nicht gefunden." : database.reportDetails(id);
        displayId = id == null ? "unbekannt" : database.reportDisplayId(id);

        LinearLayout page = Ui.verticalPage(this);
        page.addView(Ui.backHeader(this, "Kampfbericht"));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button copy = Ui.button(this, "In Zwischenablage", Ui.GREEN);
        copy.setOnClickListener(view -> copyReport());
        Button pdf = Ui.button(this, "Als PDF speichern", Ui.PANEL);
        pdf.setOnClickListener(view -> choosePdfDestination());
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1f);
        left.setMargins(0, Ui.dp(this, 6), Ui.dp(this, 5), Ui.dp(this, 8));
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1f);
        right.setMargins(Ui.dp(this, 5), Ui.dp(this, 6), 0, Ui.dp(this, 8));
        actions.addView(copy, left);
        actions.addView(pdf, right);
        page.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView text = Ui.text(this, reportText, 14, Ui.WHITE);
        text.setTextIsSelectable(true);
        text.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 22));
        text.setBackground(Ui.rounded(Ui.PANEL, Ui.dp(this, 12), 0, 0));
        page.addView(text, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(page);
        setContentView(scroll);
    }

    private void copyReport() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(displayId, reportText));
        Toast.makeText(this, "Bericht wurde kopiert.", Toast.LENGTH_SHORT).show();
    }

    private void choosePdfDestination() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        intent.putExtra(Intent.EXTRA_TITLE, "AoO-Kampfbericht-" + safeFileName(displayId) + ".pdf");
        startActivityForResult(intent, REQUEST_CREATE_PDF);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CREATE_PDF || resultCode != RESULT_OK || data == null) return;
        Uri destination = data.getData();
        if (destination == null) return;
        try (OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
            if (output == null) throw new IOException("Zieldatei konnte nicht geöffnet werden");
            ReportPdfExporter.write(reportText, displayId, output);
            Toast.makeText(this, "PDF wurde gespeichert.", Toast.LENGTH_LONG).show();
        } catch (IOException error) {
            Toast.makeText(this, "PDF konnte nicht gespeichert werden: " + error.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private String safeFileName(String value) {
        String cleaned = value == null ? "Bericht" : value.replaceAll("[^A-Za-z0-9._-]", "-");
        return cleaned.isEmpty() ? "Bericht" : cleaned;
    }
}
