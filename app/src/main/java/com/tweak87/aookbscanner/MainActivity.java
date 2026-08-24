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
import android.widget.TextView;
import android.widget.Toast;

import com.tweak87.aookbscanner.capture.CaptureService;
import com.tweak87.aookbscanner.ui.ReportsActivity;
import com.tweak87.aookbscanner.ui.UnitConfigActivity;
import com.tweak87.aookbscanner.util.Ui;

public final class MainActivity extends Activity {
    private static final int REQUEST_OVERLAY = 101;
    private static final int REQUEST_PROJECTION = 102;
    private static final int REQUEST_NOTIFICATIONS = 103;
    private static final String AOO_PACKAGE = "com.camelgames.aoz";

    private TextView status;
    private Button startButton;
    private Button stopButton;
    private boolean pendingStart;
    private boolean waitingForOverlay;

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

        status = Ui.text(this, "", 18, Ui.WHITE);
        status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        status.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        status.setBackground(Ui.rounded(Ui.PANEL, Ui.dp(this, 12), Ui.AMBER, Ui.dp(this, 1)));
        page.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(Ui.spacer(this, 12));

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

        page.addView(Ui.spacer(this, 18));
        TextView help = Ui.text(this,
                "Ablauf\n1. Start drücken und Overlay erlauben.\n2. Bildschirmfreigabe bestätigen.\n" +
                        "3. Im Spiel einen Schlachtbericht öffnen.\n4. Angreifer- und Verteidigerdetails langsam bis zum Ende scrollen.\n\n" +
                        "Grün = erkannt · Gelb = noch offen · Rot = unplausibel. " +
                        "Die App speichert keine Videoaufnahme und sendet keine Daten ins Internet.",
                15, Ui.MUTED);
        help.setLineSpacing(Ui.dp(this, 2), 1.12f);
        page.addView(help);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(page);
        setContentView(scroll);
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
                    .putExtra(CaptureService.EXTRA_RESULT_DATA, data);
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
        Ui.setEnabled(startButton, !running);
        Ui.setEnabled(stopButton, running);
    }
}
