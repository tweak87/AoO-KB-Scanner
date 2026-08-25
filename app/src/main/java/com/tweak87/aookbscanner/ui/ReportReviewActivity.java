package com.tweak87.aookbscanner.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.tweak87.aookbscanner.MainActivity;
import com.tweak87.aookbscanner.db.ScannerDatabase;
import com.tweak87.aookbscanner.db.ScannerDatabase.EditableBonus;
import com.tweak87.aookbscanner.db.ScannerDatabase.EditableParticipant;
import com.tweak87.aookbscanner.db.ScannerDatabase.EditableUnit;
import com.tweak87.aookbscanner.db.ScannerDatabase.ReviewFieldRow;
import com.tweak87.aookbscanner.db.ScannerDatabase.ReviewSnapshot;
import com.tweak87.aookbscanner.db.ScannerDatabase.ReviewState;
import com.tweak87.aookbscanner.db.ScannerDatabase.StatusTypeRow;
import com.tweak87.aookbscanner.db.ScannerDatabase.UnitTypeRow;
import com.tweak87.aookbscanner.event.EventScoring;
import com.tweak87.aookbscanner.ocr.NumberParser;
import com.tweak87.aookbscanner.review.ReportCollageBuilder;
import com.tweak87.aookbscanner.review.LongImageView;
import com.tweak87.aookbscanner.util.Ui;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Continuous evidence document, final-value comparison, rescan selection and editor. */
public final class ReportReviewActivity extends Activity {
    public static final String EXTRA_REPORT_ID = "report_id";
    private static final String[] TIERS = {
            "?", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII", "XIII"
    };

    private ScannerDatabase database;
    private String reportId;
    private ScrollView scroll;
    private LinearLayout issues;
    private LinearLayout editor;
    private TextView editorTitle;
    private TextView coverage;
    private TextView collageStatus;
    private LongImageView collageView;
    private boolean building;
    private boolean destroyed;
    private boolean selectionInitialized;
    private final Set<String> selectedKeys = new HashSet<>();
    private final List<CheckBox> issueChecks = new ArrayList<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        database = new ScannerDatabase(this);
        reportId = getIntent().getStringExtra(EXTRA_REPORT_ID);
        if (reportId == null) { finish(); return; }

        LinearLayout page = Ui.verticalPage(this);
        page.addView(Ui.backHeader(this, "Bericht prüfen"));
        page.addView(Ui.text(this,
                "Diese Ansicht vergleicht die gespeicherten OCR-Kandidaten mit dem aktuell bearbeiteten " +
                        "Bericht. Grün = identisch, Gelb = wahrscheinlich/prüfen, Rot = fehlt.",
                14, Ui.MUTED));
        page.addView(Ui.spacer(this, 10));

        coverage = tableText("");
        page.addView(coverage, panelParams());
        page.addView(section("FELDABGLEICH / NACHSCAN-AUSWAHL"));

        LinearLayout quickActions = new LinearLayout(this);
        quickActions.setOrientation(LinearLayout.HORIZONTAL);
        Button selectOpen = Ui.button(this, "Offene markieren", Ui.PANEL);
        selectOpen.setOnClickListener(view -> selectOpenFields());
        Button jumpEditor = Ui.button(this, "Korrekturmaske", Ui.AMBER);
        jumpEditor.setTextColor(Ui.NAVY);
        jumpEditor.setOnClickListener(view -> jumpToEditor());
        quickActions.addView(selectOpen, halfLeft());
        quickActions.addView(jumpEditor, halfRight());
        page.addView(quickActions);

        issues = new LinearLayout(this);
        issues.setOrientation(LinearLayout.VERTICAL);
        page.addView(issues);
        Button rescan = Ui.button(this, "Markierte Werte manuell nachscannen", Ui.AMBER);
        rescan.setTextColor(Ui.NAVY);
        rescan.setOnClickListener(view -> startManualRescan());
        page.addView(rescan);

        page.addView(section("ZUSAMMENHÄNGENDES SCAN-DOKUMENT"));
        collageStatus = Ui.text(this, "Dokument wird erstellt …", 14, Ui.MUTED);
        page.addView(collageStatus);
        collageView = new LongImageView(this);
        collageView.setBackground(Ui.rounded(Ui.NAVY, Ui.dp(this, 8), Ui.GREEN, Ui.dp(this, 1)));
        collageView.setOnClickListener(view -> jumpToEditor());
        page.addView(collageView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        editorTitle = section("BEARBEITUNGSMASKE");
        page.addView(editorTitle);
        editor = new LinearLayout(this);
        editor.setOrientation(LinearLayout.VERTICAL);
        page.addView(editor);
        Button update = Ui.button(this, "Bericht aktualisieren", Ui.GREEN);
        update.setOnClickListener(view -> updateReport());
        page.addView(update);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(page);
        setContentView(scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        reload(true);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (collageView != null) collageView.close();
        database.close();
        super.onDestroy();
    }

    private void reload(boolean rebuildDocument) {
        ReviewSnapshot snapshot = database.reviewSnapshot(reportId);
        coverage.setText(database.coverageTable(reportId) + "\n\n" + snapshot.table());
        loadIssues(snapshot);
        loadEditor();
        if (rebuildDocument) buildAndLoadCollage();
    }

    private void loadIssues(ReviewSnapshot snapshot) {
        issues.removeAllViews();
        issueChecks.clear();
        for (ReviewFieldRow field : snapshot.fields) {
            CheckBox check = new CheckBox(this);
            check.setTag(field);
            check.setText(field.tableLine());
            check.setTextColor(Ui.WHITE);
            check.setTextSize(12);
            check.setTypeface(Typeface.MONOSPACE);
            check.setPadding(Ui.dp(this, 9), Ui.dp(this, 8), Ui.dp(this, 9), Ui.dp(this, 8));
            int color = field.state == ReviewState.EXACT ? Ui.GREEN :
                    field.state == ReviewState.LIKELY ? Ui.AMBER : Ui.RED;
            check.setBackground(Ui.rounded(Ui.PANEL, Ui.dp(this, 8), color, Ui.dp(this, 2)));
            boolean checked = selectionInitialized ? selectedKeys.contains(field.key) : field.state != ReviewState.EXACT;
            check.setChecked(checked);
            if (checked) selectedKeys.add(field.key);
            check.setOnCheckedChangeListener((button, value) -> {
                if (value) selectedKeys.add(field.key); else selectedKeys.remove(field.key);
            });
            check.setOnLongClickListener(view -> { jumpToEditor(); return true; });
            LinearLayout.LayoutParams params = panelParams();
            params.setMargins(0, 0, 0, Ui.dp(this, 7));
            issues.addView(check, params);
            issueChecks.add(check);
        }
        selectionInitialized = true;
        if (snapshot.fields.isEmpty()) issues.addView(Ui.text(this,
                "Noch keine vergleichbaren Werte gespeichert.", 14, Ui.AMBER));
    }

    private void loadEditor() {
        editor.removeAllViews();
        List<EditableParticipant> participants = database.listEditableParticipants(reportId);
        if (participants.isEmpty()) editor.addView(Ui.text(this,
                "Noch keine Spieler erkannt. Nutze einen Nachscan und zeige zuerst die Spielerkarte.", 15, Ui.AMBER));
        for (EditableParticipant participant : participants) addParticipant(participant);
        Button reviewed = Ui.button(this, "Alle aktuellen Werte als manuell geprüft markieren", Ui.PANEL);
        reviewed.setOnClickListener(view -> {
            database.markReportReviewed(reportId);
            Toast.makeText(this, "Aktuelle Werte als geprüft gespeichert. Jetzt Bericht aktualisieren.",
                    Toast.LENGTH_LONG).show();
            reload(false);
        });
        editor.addView(reviewed);
    }

    private void updateReport() {
        database.refreshReport(reportId);
        Toast.makeText(this, "Finaler Bericht, Punkte und Prüfdokument werden aktualisiert.",
                Toast.LENGTH_LONG).show();
        reload(false);
        buildAndLoadCollage();
    }

    private void startManualRescan() {
        ArrayList<String> keys = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        for (CheckBox check : issueChecks) {
            if (!check.isChecked()) continue;
            ReviewFieldRow field = (ReviewFieldRow) check.getTag();
            keys.add(field.key);
            labels.add(field.label);
        }
        if (keys.isEmpty()) {
            Toast.makeText(this, "Bitte mindestens einen Wert markieren.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_MANUAL_REPORT_ID, reportId)
                .putStringArrayListExtra(MainActivity.EXTRA_MANUAL_FIELD_KEYS, keys)
                .putStringArrayListExtra(MainActivity.EXTRA_MANUAL_FIELD_LABELS, labels)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    private void selectOpenFields() {
        selectedKeys.clear();
        for (CheckBox check : issueChecks) {
            ReviewFieldRow field = (ReviewFieldRow) check.getTag();
            boolean open = field.state != ReviewState.EXACT;
            check.setChecked(open);
            if (open) selectedKeys.add(field.key);
        }
    }

    private void jumpToEditor() {
        scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, editorTitle.getTop() - Ui.dp(this, 12))));
    }

    private void buildAndLoadCollage() {
        if (building) return;
        building = true;
        collageStatus.setText("Ein zusammenhängendes Bild wird aus überlappenden Scanbereichen erstellt …");
        new Thread(() -> {
            File file = new ReportCollageBuilder(this, database).build(reportId);
            runOnUiThread(() -> {
                building = false;
                if (destroyed) return;
                if (file == null || !file.isFile()) {
                    collageStatus.setText("Noch kein Scan-Dokument vorhanden. Starte einen Nachscan.");
                    collageView.setImage(null);
                    return;
                }
                collageView.setImage(file);
                ReviewSnapshot refreshed = database.reviewSnapshot(reportId);
                collageStatus.setText("Ein Bild · Grün " + refreshed.exactCount + " · Gelb " +
                        refreshed.likelyCount + " · Rot " + refreshed.missingCount +
                        " · Antippen: zur Korrekturmaske");
            });
        }, "AoO-collage").start();
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
                    "Gefall. | " + number(unit.fallen) + "   Kills | " + number(unit.kills));
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
        additions.addView(addUnit, halfLeft());
        additions.addView(addStatus, halfRight());
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
            reload(false);
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
            reload(false);
        });
    }

    private void editBonus(EditableBonus row) {
        EditText value = field("Wert, z. B. 422.9%", row.valueRaw, false);
        LinearLayout form = form();
        form.addView(value);
        showForm(row.displayName, form, () -> {
            database.updateBonus(row.id, value.getText().toString());
            reload(false);
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
        form.addView(label("Statuswert")); form.addView(status); form.addView(value);
        showForm("Statuswert hinzufügen", form, () -> {
            StatusTypeRow selected = rows.get(status.getSelectedItemPosition());
            database.addBonus(participant.id, selected.canonicalKey, value.getText().toString());
            reload(false);
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
        form.addView(label("Einheit")); form.addView(type); add(form, name, category);
        form.addView(label("Stufe")); form.addView(tier); add(form, survivors, wounded, fallen, kills);
        showForm("Einheit hinzufügen", form, () -> {
            String selectedTier = TIERS[tier.getSelectedItemPosition()];
            String signature = type.getSelectedItemPosition() == 0
                    ? database.createManualUnitType(name.getText().toString(), category.getText().toString(), selectedTier)
                    : rows.get(type.getSelectedItemPosition() - 1).signature;
            database.addUnitRow(participant.id, signature, selectedTier, number(survivors),
                    number(wounded), number(fallen), number(kills));
            reload(false);
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

    private LinearLayout.LayoutParams halfLeft() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1f);
        params.setMargins(0, 0, Ui.dp(this, 4), Ui.dp(this, 9));
        return params;
    }

    private LinearLayout.LayoutParams halfRight() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1f);
        params.setMargins(Ui.dp(this, 4), 0, 0, Ui.dp(this, 9));
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
        ScrollView formScroll = new ScrollView(this);
        formScroll.addView(form);
        new AlertDialog.Builder(this).setTitle(title).setView(formScroll)
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Speichern", (dialog, which) -> save.run()).show();
    }

    private static void add(LinearLayout form, View... views) { for (View view : views) form.addView(view); }
    private static String dash(String value) { return value == null || value.trim().isEmpty() ? "—" : value.trim(); }
    private static String value(Number value) { return value == null ? "" : value.toString(); }
    private static String number(Number value) {
        return value == null ? "—" : String.format(Locale.GERMANY, "%,d", value.longValue());
    }
    private static Long number(EditText field) { return NumberParser.parseLong(field.getText().toString()); }
    private static Integer integer(EditText field) {
        Long value = number(field);
        return value == null || value > Integer.MAX_VALUE ? null : value.intValue();
    }
}
