package com.tweak87.aookbscanner.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.tweak87.aookbscanner.db.ScannerDatabase;
import com.tweak87.aookbscanner.db.ScannerDatabase.EditableBonus;
import com.tweak87.aookbscanner.db.ScannerDatabase.EditableParticipant;
import com.tweak87.aookbscanner.db.ScannerDatabase.EditableUnit;
import com.tweak87.aookbscanner.db.ScannerDatabase.EvidenceFrameRow;
import com.tweak87.aookbscanner.db.ScannerDatabase.StatusTypeRow;
import com.tweak87.aookbscanner.db.ScannerDatabase.UnitTypeRow;
import com.tweak87.aookbscanner.event.EventScoring;
import com.tweak87.aookbscanner.ocr.NumberParser;
import com.tweak87.aookbscanner.util.Ui;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One review surface for editable OCR values and the vertically connected evidence document. */
public final class ReportReviewActivity extends Activity {
    public static final String EXTRA_REPORT_ID = "report_id";
    private static final String[] TIERS = {
            "?", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII", "XIII"
    };

    private ScannerDatabase database;
    private String reportId;
    private LinearLayout editor;
    private TextView coverage;
    private EvidenceAdapter evidenceAdapter;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        database = new ScannerDatabase(this);
        reportId = getIntent().getStringExtra(EXTRA_REPORT_ID);
        if (reportId == null) {
            finish();
            return;
        }

        ListView document = new ListView(this);
        document.setBackgroundColor(Ui.NAVY);
        document.setDividerHeight(0);
        document.setClipToPadding(false);
        document.setPadding(0, 0, 0, Ui.dp(this, 24));

        LinearLayout header = Ui.verticalPage(this);
        header.addView(Ui.backHeader(this, "Scan-Prüfung"));
        TextView intro = Ui.text(this,
                "Grün = erkannt · Gelb = offen · Rot = unplausibel. Tippe auf eine Tabellenzeile, " +
                        "um den Wert zu korrigieren. Die Bilder darunter bilden das zusammenhängende Scan-Dokument.",
                14, Ui.MUTED);
        header.addView(intro);
        header.addView(Ui.spacer(this, 10));
        coverage = tableText("");
        header.addView(coverage, panelParams());
        editor = new LinearLayout(this);
        editor.setOrientation(LinearLayout.VERTICAL);
        header.addView(editor);
        document.addHeaderView(header, null, false);

        evidenceAdapter = new EvidenceAdapter();
        document.setAdapter(evidenceAdapter);
        setContentView(document);
    }

    @Override protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        coverage.setText(database.coverageTable(reportId));
        editor.removeAllViews();
        editor.addView(section("ERKANNTE WERTE / KORREKTUR"));
        List<EditableParticipant> participants = database.listEditableParticipants(reportId);
        if (participants.isEmpty()) {
            editor.addView(Ui.text(this, "Noch keine Spieler erkannt. Zeige im Scan zuerst eine Spielerkarte.",
                    15, Ui.AMBER));
        }
        for (EditableParticipant participant : participants) addParticipant(participant);
        Button reviewed = Ui.button(this, "Alle Werte als manuell geprüft markieren", Ui.GREEN);
        reviewed.setOnClickListener(view -> {
            database.markReportReviewed(reportId);
            Toast.makeText(this, "Bericht als manuell geprüft markiert.", Toast.LENGTH_SHORT).show();
            reload();
        });
        editor.addView(reviewed);
        editor.addView(section("SCAN-DOKUMENT"));
        evidenceAdapter.reload(database.listEvidence(reportId));
    }

    private void addParticipant(EditableParticipant participant) {
        TextView player = tableText(participantTable(participant));
        player.setOnClickListener(view -> editParticipant(participant));
        editor.addView(player, panelParams());

        for (EditableUnit unit : database.listEditableUnits(participant.id)) {
            TextView row = tableText("EINHEIT | " + unit.displayName + "\n" +
                    "Stufe   | " + dash(unit.tier) + "\n" +
                    "Art     | " + dash(unit.category) + "\n" +
                    "Überl.  | " + number(unit.survivors) + "   Verw. | " + number(unit.wounded) + "\n" +
                    "Gefall.  | " + number(unit.fallen) + "   Kills | " + number(unit.kills));
            row.setOnClickListener(view -> editUnit(unit));
            editor.addView(row, insetPanelParams());
        }
        for (EditableBonus bonus : database.listEditableBonuses(participant.id)) {
            TextView row = tableText("STATUSWERT | " + bonus.displayName + "\nWERT       | " + bonus.valueRaw);
            row.setOnClickListener(view -> editBonus(bonus));
            editor.addView(row, insetPanelParams());
        }

        LinearLayout additions = new LinearLayout(this);
        additions.setOrientation(LinearLayout.HORIZONTAL);
        Button addUnit = Ui.button(this, "+ Einheit", Ui.PANEL);
        addUnit.setOnClickListener(view -> addUnit(participant));
        Button addStatus = Ui.button(this, "+ Statuswert", Ui.PANEL);
        addStatus.setOnClickListener(view -> addStatus(participant));
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f);
        left.setMargins(Ui.dp(this, 12), 0, Ui.dp(this, 4), Ui.dp(this, 12));
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f);
        right.setMargins(Ui.dp(this, 4), 0, 0, Ui.dp(this, 12));
        additions.addView(addUnit, left);
        additions.addView(addStatus, right);
        editor.addView(additions);
    }

    private void editParticipant(EditableParticipant row) {
        LinearLayout form = form();
        EditText alliance = field("Allianz", row.alliance, false);
        EditText name = field("Spielername", row.name, false);
        EditText x = field("Position X", value(row.x), true);
        EditText y = field("Position Y", value(row.y), true);
        EditText total = field("Insgesamt", value(row.total), true);
        EditText loss = field("Kraftverlust", value(row.powerLoss), true);
        EditText kills = field("Getötete Feinde", value(row.kills), true);
        EditText fallen = field("Gefallene", value(row.fallen), true);
        EditText survivors = field("Überlebende", value(row.survivors), true);
        EditText wounded = field("Verwundete", value(row.wounded), true);
        add(form, alliance, name, x, y, total, loss, kills, fallen, survivors, wounded);
        showForm("Spielerwerte korrigieren", form, () -> {
            database.updateParticipant(row.id, alliance.getText().toString(), name.getText().toString(),
                    integer(x), integer(y), number(total), number(loss), number(kills), number(fallen),
                    number(survivors), number(wounded));
            reload();
        });
    }

    private void editUnit(EditableUnit row) {
        LinearLayout form = form();
        Spinner tier = tierSpinner(row.tier);
        EditText survivors = field("Überlebende", value(row.survivors), true);
        EditText wounded = field("Verwundete", value(row.wounded), true);
        EditText fallen = field("Gefallene", value(row.fallen), true);
        EditText kills = field("Getötete Feinde", value(row.kills), true);
        form.addView(label("Stufe (römische Zahl am Symbol)"));
        form.addView(tier);
        add(form, survivors, wounded, fallen, kills);
        showForm(row.displayName + " korrigieren", form, () -> {
            database.updateUnitRow(row.id, TIERS[tier.getSelectedItemPosition()],
                    number(survivors), number(wounded), number(fallen), number(kills));
            reload();
        });
    }

    private void editBonus(EditableBonus row) {
        EditText value = field("Wert, z. B. 422.9%", row.valueRaw, false);
        LinearLayout form = form();
        form.addView(value);
        showForm(row.displayName, form, () -> {
            database.updateBonus(row.id, value.getText().toString());
            reload();
        });
    }

    private void addStatus(EditableParticipant participant) {
        List<StatusTypeRow> rows = database.listStatusTypes();
        if (rows.isEmpty()) {
            Toast.makeText(this, "Bitte zuerst einen Statusnamen konfigurieren.", Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout form = form();
        Spinner status = new Spinner(this);
        status.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, rows));
        EditText value = field("Wert, z. B. 422.9%", "", false);
        form.addView(label("Statuswert"));
        form.addView(status);
        form.addView(value);
        showForm("Statuswert hinzufügen", form, () -> {
            StatusTypeRow selected = rows.get(status.getSelectedItemPosition());
            database.addBonus(participant.id, selected.canonicalKey, value.getText().toString());
            reload();
        });
    }

    private void addUnit(EditableParticipant participant) {
        List<UnitTypeRow> rows = database.listUnitTypes();
        List<String> choices = new ArrayList<>();
        choices.add("➕ Neue manuelle Einheit");
        for (UnitTypeRow row : rows) choices.add(row.displayName + " · " + row.defaultTier);
        LinearLayout form = form();
        Spinner type = new Spinner(this);
        type.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, choices));
        EditText name = field("Name (nur bei neuer Einheit)", "", false);
        EditText category = field("Einheitenart / Kategorie", "", false);
        Spinner tier = tierSpinner("?");
        EditText survivors = field("Überlebende", "", true);
        EditText wounded = field("Verwundete", "", true);
        EditText fallen = field("Gefallene", "", true);
        EditText kills = field("Getötete Feinde", "", true);
        form.addView(label("Einheit")); form.addView(type);
        add(form, name, category);
        form.addView(label("Stufe")); form.addView(tier);
        add(form, survivors, wounded, fallen, kills);
        showForm("Einheit hinzufügen", form, () -> {
            String selectedTier = TIERS[tier.getSelectedItemPosition()];
            String signature;
            if (type.getSelectedItemPosition() == 0) {
                signature = database.createManualUnitType(name.getText().toString(),
                        category.getText().toString(), selectedTier);
            } else {
                signature = rows.get(type.getSelectedItemPosition() - 1).signature;
            }
            database.addUnitRow(participant.id, signature, selectedTier, number(survivors),
                    number(wounded), number(fallen), number(kills));
            reload();
        });
    }

    private String participantTable(EditableParticipant row) {
        String side = "ATTACKER".equals(row.side) ? "ANGREIFER" : "VERTEIDIGER";
        return "SEITE       | " + side + "\n" +
                "SPIELER     | " + (row.alliance.isEmpty() ? "" : "(" + row.alliance + ") ") + dash(row.name) + "\n" +
                "POSITION    | X:" + number(row.x) + " Y:" + number(row.y) + "\n" +
                "INSGESAMT   | " + number(row.total) + "\n" +
                "KRAFTVERL.  | " + number(row.powerLoss) + "\n" +
                "GET. FEINDE | " + number(row.kills) + "\n" +
                "GEFALLENE   | " + number(row.fallen) + "\n" +
                "ÜBERLEBENDE | " + number(row.survivors) + "\n" +
                "VERWUNDETE  | " + number(row.wounded);
    }

    private TextView tableText(String value) {
        TextView view = Ui.text(this, value, 13, Ui.WHITE);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        view.setBackground(Ui.rounded(Ui.PANEL, Ui.dp(this, 9), Ui.GREEN, Ui.dp(this, 1)));
        return view;
    }

    private TextView section(String value) {
        TextView title = Ui.text(this, value, 17, Ui.AMBER);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 8));
        return title;
    }

    private LinearLayout.LayoutParams panelParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, Ui.dp(this, 10));
        return params;
    }

    private LinearLayout.LayoutParams insetPanelParams() {
        LinearLayout.LayoutParams params = panelParams();
        params.setMargins(Ui.dp(this, 12), 0, 0, Ui.dp(this, 7));
        return params;
    }

    private LinearLayout form() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 8));
        return form;
    }

    private TextView label(String value) {
        TextView label = Ui.text(this, value, 13, Ui.MUTED);
        label.setPadding(0, Ui.dp(this, 8), 0, 0);
        return label;
    }

    private EditText field(String hint, String value, boolean numeric) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(value);
        if (numeric) field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        return field;
    }

    private Spinner tierSpinner(String selected) {
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, TIERS));
        spinner.setSelection(tierIndex(selected));
        return spinner;
    }

    private int tierIndex(String value) {
        String normalized = EventScoring.normalizedTier(value);
        for (int i = 0; i < TIERS.length; i++) if (TIERS[i].equals(normalized)) return i;
        return 0;
    }

    private void showForm(String title, LinearLayout form, Runnable save) {
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        new AlertDialog.Builder(this).setTitle(title).setView(scroll)
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Speichern", (dialog, which) -> save.run()).show();
    }

    private static void add(LinearLayout form, View... views) {
        for (View view : views) form.addView(view);
    }

    private static String dash(String value) {
        return value == null || value.trim().isEmpty() ? "—" : value.trim();
    }

    private static String value(Number value) { return value == null ? "" : value.toString(); }
    private static String number(Number value) {
        return value == null ? "—" : String.format(Locale.GERMANY, "%,d", value.longValue());
    }
    private static Long number(EditText field) { return NumberParser.parseLong(field.getText().toString()); }
    private static Integer integer(EditText field) {
        Long value = number(field);
        return value == null || value > Integer.MAX_VALUE ? null : value.intValue();
    }

    private final class EvidenceAdapter extends BaseAdapter {
        private final List<EvidenceFrameRow> rows = new ArrayList<>();

        void reload(List<EvidenceFrameRow> values) {
            rows.clear();
            rows.addAll(values);
            notifyDataSetChanged();
        }

        @Override public int getCount() { return Math.max(1, rows.size()); }
        @Override public Object getItem(int position) { return rows.isEmpty() ? null : rows.get(position); }
        @Override public long getItemId(int position) { return rows.isEmpty() ? 0 : rows.get(position).id; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            if (rows.isEmpty()) {
                TextView empty = Ui.text(ReportReviewActivity.this,
                        "Für diesen älteren Bericht wurden noch keine Belegbilder gespeichert.", 15, Ui.AMBER);
                empty.setPadding(Ui.dp(ReportReviewActivity.this, 20), Ui.dp(ReportReviewActivity.this, 16),
                        Ui.dp(ReportReviewActivity.this, 20), Ui.dp(ReportReviewActivity.this, 24));
                return empty;
            }
            EvidenceRow holder;
            if (!(convertView instanceof LinearLayout) || convertView.getTag() == null) {
                LinearLayout root = new LinearLayout(ReportReviewActivity.this);
                root.setOrientation(LinearLayout.VERTICAL);
                TextView caption = Ui.text(ReportReviewActivity.this, "", 12, Ui.WHITE);
                caption.setTypeface(Typeface.MONOSPACE);
                caption.setPadding(Ui.dp(ReportReviewActivity.this, 12), Ui.dp(ReportReviewActivity.this, 7),
                        Ui.dp(ReportReviewActivity.this, 12), Ui.dp(ReportReviewActivity.this, 7));
                ImageView image = new ImageView(ReportReviewActivity.this);
                image.setAdjustViewBounds(true);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setPadding(Ui.dp(ReportReviewActivity.this, 2), Ui.dp(ReportReviewActivity.this, 2),
                        Ui.dp(ReportReviewActivity.this, 2), Ui.dp(ReportReviewActivity.this, 2));
                image.setBackground(Ui.rounded(Ui.NAVY, 0, Ui.GREEN, Ui.dp(ReportReviewActivity.this, 2)));
                root.addView(caption, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                root.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                holder = new EvidenceRow(caption, image);
                root.setTag(holder);
                convertView = root;
            } else {
                holder = (EvidenceRow) convertView.getTag();
            }
            EvidenceFrameRow row = rows.get(position);
            holder.caption.setText(row.label());
            Bitmap previous = (Bitmap) holder.image.getTag();
            if (previous != null && !previous.isRecycled()) previous.recycle();
            Bitmap bitmap = new File(row.filePath).isFile() ? BitmapFactory.decodeFile(row.filePath) : null;
            holder.image.setImageBitmap(bitmap);
            holder.image.setTag(bitmap);
            return convertView;
        }
    }

    private static final class EvidenceRow {
        final TextView caption;
        final ImageView image;
        EvidenceRow(TextView caption, ImageView image) { this.caption = caption; this.image = image; }
    }
}
