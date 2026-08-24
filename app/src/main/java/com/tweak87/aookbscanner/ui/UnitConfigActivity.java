package com.tweak87.aookbscanner.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import com.tweak87.aookbscanner.db.ScannerDatabase;
import com.tweak87.aookbscanner.db.ScannerDatabase.UnitTypeRow;
import com.tweak87.aookbscanner.event.EventScoring;
import com.tweak87.aookbscanner.util.Ui;

import java.util.List;

public final class UnitConfigActivity extends Activity {
    private static final String[] EVENT_LABELS = {
            "Standard (Stufe T1–T13)", "Titan", "Kampfflugzeug", "Keine Eventpunkte"
    };
    private static final String[] EVENT_TYPES = {
            EventScoring.TYPE_STANDARD, EventScoring.TYPE_TITAN,
            EventScoring.TYPE_WARPLANE, EventScoring.TYPE_NONE
    };
    private ScannerDatabase database;
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Einheiten konfigurieren");
        database = new ScannerDatabase(this);
        content = Ui.verticalPage(this);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        content.removeAllViews();
        content.addView(Ui.backHeader(this, "Einheiten konfigurieren"));
        content.addView(Ui.text(this,
                "Tippe auf eine Einheit, um Namen, Kategorie und die Battle-Frenzy-Zuordnung zu korrigieren. " +
                        "Die Zuordnung gilt danach automatisch für gleiche Symbole.", 15, Ui.MUTED));
        content.addView(Ui.spacer(this, 12));
        List<UnitTypeRow> rows = database.listUnitTypes();
        if (rows.isEmpty()) {
            content.addView(Ui.text(this, "Noch keine Einheitensymbole erfasst.", 17, Ui.AMBER));
            return;
        }
        for (UnitTypeRow row : rows) content.addView(unitRow(row));
    }

    private LinearLayout unitRow(UnitTypeRow row) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        line.setBackground(Ui.rounded(Ui.PANEL, Ui.dp(this, 10), 0, 0));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, 0, 0, Ui.dp(this, 8));
        line.setLayoutParams(rowParams);

        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (row.representativePng != null) {
            Bitmap bitmap = BitmapFactory.decodeByteArray(row.representativePng, 0, row.representativePng.length);
            icon.setImageBitmap(bitmap);
        }
        line.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64)));

        TextView labels = Ui.text(this, row.displayName + "\n" + row.category +
                " · " + eventLabel(row.eventType) + "\n" + row.signature, 15, Ui.WHITE);
        labels.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.setMargins(Ui.dp(this, 12), 0, 0, 0);
        line.addView(labels, textParams);
        line.setOnClickListener(view -> edit(row));
        return line;
    }

    private void edit(UnitTypeRow row) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        EditText name = new EditText(this);
        name.setHint("Einheitenname, z. B. Raketenwerfer");
        name.setText(row.displayName);
        form.addView(name);
        EditText category = new EditText(this);
        category.setHint("Kategorie, z. B. Fernkampf");
        category.setText(row.category);
        form.addView(category);
        TextView eventTitle = Ui.text(this, "Battle-Frenzy-Zuordnung", 14, Ui.MUTED);
        eventTitle.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
        form.addView(eventTitle);
        Spinner eventType = new Spinner(this);
        ArrayAdapter<String> eventAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, EVENT_LABELS);
        eventType.setAdapter(eventAdapter);
        eventType.setSelection(eventTypeIndex(row.eventType));
        form.addView(eventType);
        new AlertDialog.Builder(this)
                .setTitle("Einheit bearbeiten")
                .setView(form)
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Speichern", (dialog, which) -> {
                    database.updateUnitType(row.signature, name.getText().toString(),
                            category.getText().toString(), EVENT_TYPES[eventType.getSelectedItemPosition()]);
                    reload();
                })
                .show();
    }

    private int eventTypeIndex(String type) {
        for (int i = 0; i < EVENT_TYPES.length; i++) if (EVENT_TYPES[i].equals(type)) return i;
        return 0;
    }

    private String eventLabel(String type) {
        return EVENT_LABELS[eventTypeIndex(type)];
    }
}
