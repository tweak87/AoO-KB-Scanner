package com.tweak87.aookbscanner.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tweak87.aookbscanner.db.ScannerDatabase;
import com.tweak87.aookbscanner.db.ScannerDatabase.StatusTypeRow;
import com.tweak87.aookbscanner.util.Ui;

public final class StatusConfigActivity extends Activity {
    private ScannerDatabase database;
    private LinearLayout content;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
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
        content.addView(Ui.backHeader(this, "Statuswerte konfigurieren"));
        content.addView(Ui.text(this,
                "Der feste Erkennungsschlüssel bleibt erhalten. Du kannst den Namen für Tabelle/PDF " +
                        "und zusätzliche OCR-Schreibweisen eingeben. Einen laufenden Scanner danach neu starten.",
                15, Ui.MUTED));
        content.addView(Ui.button(this, "+ Eigenes Statusfeld", Ui.GREEN));
        content.getChildAt(content.getChildCount() - 1).setOnClickListener(view -> addField());
        content.addView(Ui.spacer(this, 8));
        for (StatusTypeRow row : database.listStatusTypes()) content.addView(statusRow(row));
    }

    private TextView statusRow(StatusTypeRow row) {
        String aliases = row.aliases.isEmpty() ? "keine zusätzlichen OCR-Aliase" : row.aliases;
        TextView view = Ui.text(this, row.displayName + "\nErkennung: " + row.canonicalName +
                "\nAliase: " + aliases, 14, Ui.WHITE);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        view.setBackground(Ui.rounded(Ui.PANEL, Ui.dp(this, 10), 0, 0));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, Ui.dp(this, 8));
        view.setLayoutParams(params);
        view.setOnClickListener(clicked -> edit(row));
        return view;
    }

    private void edit(StatusTypeRow row) {
        LinearLayout form = form();
        EditText name = field("Anzeigename", row.displayName);
        EditText aliases = field("OCR-Aliase, durch Komma getrennt", row.aliases);
        form.addView(name); form.addView(aliases);
        new AlertDialog.Builder(this)
                .setTitle("Statusfeld bearbeiten")
                .setMessage("Fester Erkennungsname: " + row.canonicalName)
                .setView(form)
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Speichern", (dialog, which) -> {
                    database.updateStatusType(row.canonicalKey, name.getText().toString(),
                            aliases.getText().toString());
                    reload();
                }).show();
    }

    private void addField() {
        LinearLayout form = form();
        EditText name = field("Name des Statuswerts", "");
        EditText aliases = field("OCR-Aliase, durch Komma getrennt", "");
        form.addView(name); form.addView(aliases);
        new AlertDialog.Builder(this)
                .setTitle("Eigenes Statusfeld")
                .setView(form)
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Hinzufügen", (dialog, which) -> {
                    database.addStatusType(name.getText().toString(), aliases.getText().toString());
                    reload();
                }).show();
    }

    private LinearLayout form() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        return form;
    }

    private EditText field(String hint, String value) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(value);
        return field;
    }
}
