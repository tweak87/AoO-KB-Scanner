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
import android.widget.TextView;

import com.tweak87.aookbscanner.db.ScannerDatabase;
import com.tweak87.aookbscanner.db.ScannerDatabase.UnitTypeRow;
import com.tweak87.aookbscanner.util.Ui;

import java.util.List;

public final class UnitConfigActivity extends Activity {
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
        content.addView(Ui.title(this, "Erkannte Einheitentypen"));
        content.addView(Ui.text(this,
                "Tippe auf eine Einheit, um den ausgeschriebenen Namen und die Kategorie zu korrigieren. " +
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

        TextView labels = Ui.text(this, row.displayName + "\n" + row.category + "\n" + row.signature, 15, Ui.WHITE);
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
        new AlertDialog.Builder(this)
                .setTitle("Einheit bearbeiten")
                .setView(form)
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Speichern", (dialog, which) -> {
                    database.updateUnitType(row.signature, name.getText().toString(), category.getText().toString());
                    reload();
                })
                .show();
    }
}
