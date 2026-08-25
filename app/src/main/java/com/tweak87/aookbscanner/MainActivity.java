package com.tweak87.aookbscanner;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.tweak87.aookbscanner.capture.CaptureService;
import com.tweak87.aookbscanner.db.ScannerDatabase;
import com.tweak87.aookbscanner.ui.ReportsActivity;
import com.tweak87.aookbscanner.ui.StatusConfigActivity;
import com.tweak87.aookbscanner.ui.UnitConfigActivity;
import com.tweak87.aookbscanner.util.Ui;

import java.util.ArrayList;

public final class MainActivity extends Activity {
    public static final String EXTRA_MANUAL_REPORT_ID = "manual_report_id";
    public static final String EXTRA_MANUAL_FIELD_KEYS = "manual_field_keys";
    public static final String EXTRA_MANUAL_FIELD_LABELS = "manual_field_labels";
    private static final int REQUEST_OVERLAY = 101;
    private static final int REQUEST_PROJECTION = 102;
    private static final int REQUEST_NOTIFICATIONS = 103;
    private static final String AOO_PACKAGE = "com.camelgames.aoz";

    private TextView status;
    private Button startButton;
    private Button stopButton;
    private Switch eventModeSwitch;
    private Switch resourceFieldSwitch;
    private TextView manualNotice;
    private boolean pendingStart;
    private boolean waitingForOverlay;
    private String manualReportId;
    private ArrayList<String> manualFieldKeys = new ArrayList<>();
    private ArrayList<String> manualFieldLabels = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("AoO KB Scanner");

        LinearLayout page = Ui.verticalPage(this);
        page.addView(Ui.title(this, "AoO KB Scanner"));
        TextView intro = Ui.text(this,
                "Kampfberichte beim Scrollen erfassen. OCR und Speicherung laufen vollständig lokal auf diesem Gerät.",
                16, Ui.MUTED);
        page.addView(intro);
        page.addView(Ui.spacer(this, 18));

        manualNotice = Ui.text(this, "", 14, Ui.WHITE);
        manualNotice.setTypeface(Typeface.MONOSPACE);
        manualNotice.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        manualNotice.setBackground(Ui.rounded(Ui.PANEL, Ui.dp(this, 10), Ui.AMBER, Ui.dp(this, 2)));
        manualNotice.setVisibility(android.view.View.GONE);
        page.addView(manualNotice);
        page.addView(Ui.spacer(this, 8));

        status = Ui.text(this, "", 18, Ui.WHITE);
        status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        status.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        status.setBackground(Ui.rounded(Ui.PANEL, Ui.dp(this, 12), Ui.AMBER, Ui.dp(this, 1)));
        page.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(Ui.spacer(this, 12));

        eventModeSwitch = new Switch(this);
        eventModeSwitch.setText("Battle-Frenzy-Eventmodus");
        eventModeSwitch.setTextColor(Ui.WHITE);
        eventModeSwitch.setTextSize(16);
        eventModeSwitch.setChecked(getPreferences(MODE_PRIVATE).getBoolean("event_mode", false));
        page.addView(eventModeSwitch);
        resourceFieldSwitch = new Switch(this);
        resourceFieldSwitch.setText("Kampf auf Ressourcenfeld (50 % Punkte)");
        resourceFieldSwitch.setTextColor(Ui.WHITE);
        resourceFieldSwitch.setTextSize(15);
        resourceFieldSwitch.setChecked(getPreferences(MODE_PRIVATE).getBoolean("resource_field", false));
        resourceFieldSwitch.setEnabled(eventModeSwitch.isChecked());
        resourceFieldSwitch.setAlpha(eventModeSwitch.isChecked() ? 1f : 0.45f);
        page.addView(resourceFieldSwitch);
        eventModeSwitch.setOnCheckedChangeListener((button, checked) -> {
            resourceFieldSwitch.setEnabled(checked);
            resourceFieldSwitch.setAlpha(checked ? 1f : 0.45f);
            getPreferences(MODE_PRIVATE).edit().putBoolean("event_mode", checked).apply();
        });
        resourceFieldSwitch.setOnCheckedChangeListener((button, checked) ->
                getPreferences(MODE_PRIVATE).edit().putBoolean("resource_field", checked).apply());
        page.addView(Ui.spacer(this, 8));

        startButton = Ui.button(this, "Scanner starten & Spiel öffnen", Ui.GREEN);
        startButton.setOnClickListener(view -> beginStartFlow());
        page.addView(startButton);
        stopButton = Ui.button(this, "Scanner stoppen", Ui.RED);
        stopButton.setOnClickListener(view -> stopScanner());
        page.addView(stopButton);

        Button reports = Ui.button(this, "Erfasste Berichte", Ui.PANEL);
        reports.setOnClickListener(view -> startActivity(new Intent(this, ReportsActivity.class)));
        page.addView(reports);
        Button units = Ui.button(this, "Einheitennamen konfigurieren", Ui.PANEL);
        units.setOnClickListener(view -> startActivity(new Intent(this, UnitConfigActivity.class)));
        page.addView(units);
        Button statuses = Ui.button(this, "Statuswerte konfigurieren", Ui.PANEL);
        statuses.setOnClickListener(view -> startActivity(new Intent(this, StatusConfigActivity.class)));
        page.addView(statuses);

        page.addView(Ui.spacer(this, 18));
        TextView help = Ui.text(this,
                "Ablauf\n1. Start drücken und Overlay erlauben.\n2. Bildschirmfreigabe bestätigen.\n" +
                        "3. Die Übersicht eines Schlachtberichts öffnen und im Overlay „Scan starten“ drücken.\n" +
                        "4. Danach die Details jedes Angreifers und Verteidigers langsam bis zum Ende scrollen.\n" +
                        "5. Nach dem letzten gezeigten Feld im Overlay „Scan beenden“ drücken.\n\n" +
                        "Grün = erkannt · Gelb = noch offen · Rot = unplausibel. " +
                        "Im Eventmodus werden die Battle-Frenzy-Punkte live angezeigt. Titan und Kampfflugzeug " +
                        "müssen beim ersten Auftreten in der Einheitenkonfiguration zugeordnet werden. " +
                        "Während eines bestätigten Scans werden veränderte Ansichten lokal gespeichert und in der " +
                        "Prüfung zu einem zusammenhängenden Bild verbunden; eine Videodatei entsteht nicht. Es werden keine Daten " +
                        "ins Internet gesendet.",
                15, Ui.MUTED);
        help.setLineSpacing(Ui.dp(this, 2), 1.12f);
        page.addView(help);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(page);
        setContentView(scroll);
        readManualRequest(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readManualRequest(intent);
        refreshState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
        if (waitingForOverlay) {
            waitingForOverlay = false;
            if (Settings.canDrawOverlays(this)) continueStartFlow();
            else {
                pendingStart = false;
                Toast.makeText(this, "Overlay-Berechtigung wird für die Markierungen benötigt.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void beginStartFlow() {
        if (CaptureService.isRunning && manualReportId != null) {
            Intent service = new Intent(this, CaptureService.class)
                    .setAction(CaptureService.ACTION_BEGIN_MANUAL)
                    .putExtra(CaptureService.EXTRA_MANUAL_REPORT_ID, manualReportId)
                    .putStringArrayListExtra(CaptureService.EXTRA_MANUAL_FIELD_KEYS, manualFieldKeys)
                    .putStringArrayListExtra(CaptureService.EXTRA_MANUAL_FIELD_LABELS, manualFieldLabels);
            startService(service);
            launchGame();
            Toast.makeText(this, "Nachscan im laufenden Overlay gestartet.", Toast.LENGTH_LONG).show();
            return;
        }
        pendingStart = true;
        if (!Settings.canDrawOverlays(this)) {
            waitingForOverlay = true;
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_OVERLAY);
            return;
        }
        continueStartFlow();
    }

    private void continueStartFlow() {
        if (!pendingStart) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        requestScreenCapture();
    }

    private void requestScreenCapture() {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_NOTIFICATIONS && pendingStart) requestScreenCapture();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PROJECTION) {
            pendingStart = false;
            if (resultCode != RESULT_OK || data == null) {
                Toast.makeText(this, "Bildschirmfreigabe wurde nicht gestartet.", Toast.LENGTH_LONG).show();
                return;
            }
            Intent service = new Intent(this, CaptureService.class)
                    .setAction(CaptureService.ACTION_START)
                    .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                    .putExtra(CaptureService.EXTRA_EVENT_MODE, eventModeSwitch.isChecked())
                    .putExtra(CaptureService.EXTRA_RESOURCE_FIELD,
                            eventModeSwitch.isChecked() && resourceFieldSwitch.isChecked());
            if (manualReportId != null) {
                ScannerDatabase manualDatabase = new ScannerDatabase(this);
                ScannerDatabase.ReportMode mode = manualDatabase.reportMode(manualReportId);
                manualDatabase.close();
                service.putExtra(CaptureService.EXTRA_MANUAL_REPORT_ID, manualReportId)
                        .putStringArrayListExtra(CaptureService.EXTRA_MANUAL_FIELD_KEYS, manualFieldKeys)
                        .putStringArrayListExtra(CaptureService.EXTRA_MANUAL_FIELD_LABELS, manualFieldLabels)
                        .putExtra(CaptureService.EXTRA_EVENT_MODE, mode.eventMode)
                        .putExtra(CaptureService.EXTRA_RESOURCE_FIELD, mode.resourceField);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
            else startService(service);
            launchGame();
        }
    }

    private void launchGame() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(AOO_PACKAGE);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
        } else {
            Toast.makeText(this, "Age of Origins wurde nicht gefunden. Bitte das Spiel manuell öffnen.", Toast.LENGTH_LONG).show();
        }
    }

    private void stopScanner() {
        if (CaptureService.isRunning) {
            startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP));
        }
        refreshState();
    }

    private void refreshState() {
        boolean running = CaptureService.isRunning;
        status.setText(running ? "● Scanner läuft" : "○ Scanner gestoppt");
        status.setTextColor(running ? Ui.GREEN : Ui.AMBER);
        startButton.setText(manualReportId == null ? "Scanner starten & Spiel öffnen" :
                (running ? "Nachscan jetzt starten & Spiel öffnen" : "Scanner für Nachscan starten & Spiel öffnen"));
        Ui.setEnabled(startButton, !running || manualReportId != null);
        Ui.setEnabled(stopButton, running);
        eventModeSwitch.setEnabled(!running);
        eventModeSwitch.setAlpha(running ? 0.45f : 1f);
        resourceFieldSwitch.setEnabled(!running && eventModeSwitch.isChecked());
        resourceFieldSwitch.setAlpha(!running && eventModeSwitch.isChecked() ? 1f : 0.45f);
    }

    private void readManualRequest(Intent intent) {
        if (intent == null) return;
        manualReportId = intent.getStringExtra(EXTRA_MANUAL_REPORT_ID);
        ArrayList<String> keys = intent.getStringArrayListExtra(EXTRA_MANUAL_FIELD_KEYS);
        ArrayList<String> labels = intent.getStringArrayListExtra(EXTRA_MANUAL_FIELD_LABELS);
        manualFieldKeys = keys == null ? new ArrayList<>() : keys;
        manualFieldLabels = labels == null ? new ArrayList<>() : labels;
        if (manualReportId == null) {
            manualNotice.setVisibility(android.view.View.GONE);
            return;
        }
        ScannerDatabase manualDatabase = new ScannerDatabase(this);
        String manualDisplayId = manualDatabase.reportDisplayId(manualReportId);
        manualDatabase.close();
        StringBuilder text = new StringBuilder("MANUELLER NACHSCAN\nBERICHT | ")
                .append(manualDisplayId)
                .append("\nFELDER   | ").append(manualFieldLabels.size());
        for (int i = 0; i < Math.min(3, manualFieldLabels.size()); i++) {
            text.append("\n• ").append(manualFieldLabels.get(i));
        }
        if (manualFieldLabels.size() > 3) text.append("\n• … und ").append(manualFieldLabels.size() - 3).append(" weitere");
        manualNotice.setText(text);
        manualNotice.setVisibility(android.view.View.VISIBLE);
    }
}
