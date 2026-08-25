package com.tweak87.aookbscanner.capture;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.tweak87.aookbscanner.MainActivity;
import com.tweak87.aookbscanner.R;
import com.tweak87.aookbscanner.db.ScannerDatabase;
import com.tweak87.aookbscanner.model.Models.AnalysisResult;
import com.tweak87.aookbscanner.model.Models.BoxState;
import com.tweak87.aookbscanner.model.Models.ParsedFrame;
import com.tweak87.aookbscanner.model.Models.OverlayBox;
import com.tweak87.aookbscanner.model.Models.ScreenType;
import com.tweak87.aookbscanner.ocr.OcrParser;
import com.tweak87.aookbscanner.ocr.ReportAssembler;
import com.tweak87.aookbscanner.overlay.OverlayController;
import com.tweak87.aookbscanner.review.ReportCollageBuilder;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground service that samples MediaProjection frames and runs bundled on-device OCR. */
public final class CaptureService extends Service {
    public static final String ACTION_START = "com.tweak87.aookbscanner.START";
    public static final String ACTION_BEGIN_MANUAL = "com.tweak87.aookbscanner.BEGIN_MANUAL";
    public static final String ACTION_STOP = "com.tweak87.aookbscanner.STOP";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_EVENT_MODE = "event_mode";
    public static final String EXTRA_RESOURCE_FIELD = "resource_field";
    public static final String EXTRA_MANUAL_REPORT_ID = "manual_report_id";
    public static final String EXTRA_MANUAL_FIELD_KEYS = "manual_field_keys";
    public static final String EXTRA_MANUAL_FIELD_LABELS = "manual_field_labels";
    public static volatile boolean isRunning;

    private static final String CHANNEL_ID = "aoo_scanner_capture";
    private static final int NOTIFICATION_ID = 8701;
    private static final long FRAME_INTERVAL_MS = 900;

    private final AtomicBoolean processing = new AtomicBoolean(false);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private TextRecognizer recognizer;
    private OverlayController overlay;
    private OcrParser parser;
    private ReportAssembler assembler;
    private EvidenceStore evidenceStore;
    private ScannerDatabase database;
    private int captureWidth;
    private int captureHeight;
    private long lastFrameAt;
    private boolean shuttingDown;
    private volatile ScanState scanState = ScanState.WAITING;
    private ParsedFrame pendingHeader;
    private boolean eventMode;
    private boolean resourceField;
    private int outsideReportFrames;
    private String finishedStatus = "Bericht gespeichert";
    private boolean manualMode;
    private String pendingManualReportId;
    private ArrayList<String> manualFieldKeys = new ArrayList<>();
    private ArrayList<String> manualFieldLabels = new ArrayList<>();
    private final Set<String> manualRemaining = new HashSet<>();
    private int manualTargetTotal;

    private enum ScanState { WAITING, READY, SCANNING, WAIT_FOR_EXIT }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        overlay = new OverlayController(this);
        database = new ScannerDatabase(this);
        parser = new OcrParser(database.statusAliasMap());
        assembler = new ReportAssembler(database);
        evidenceStore = new EvidenceStore(this, database);
        captureThread = new HandlerThread("AoO-screen-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            stopCapture();
            return START_NOT_STICKY;
        }
        if (ACTION_BEGIN_MANUAL.equals(intent.getAction())) {
            if (isRunning) {
                String reportId = intent.getStringExtra(EXTRA_MANUAL_REPORT_ID);
                ArrayList<String> keys = intent.getStringArrayListExtra(EXTRA_MANUAL_FIELD_KEYS);
                ArrayList<String> labels = intent.getStringArrayListExtra(EXTRA_MANUAL_FIELD_LABELS);
                analysisExecutor.execute(() -> beginManualSession(reportId, keys, labels));
            }
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction()) || isRunning) return START_NOT_STICKY;

        startForegroundCompat(buildNotification("Scanner wird gestartet …"));
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent resultData = parcelableIntent(intent, EXTRA_RESULT_DATA);
        eventMode = intent.getBooleanExtra(EXTRA_EVENT_MODE, false);
        resourceField = eventMode && intent.getBooleanExtra(EXTRA_RESOURCE_FIELD, false);
        pendingManualReportId = intent.getStringExtra(EXTRA_MANUAL_REPORT_ID);
        ArrayList<String> keys = intent.getStringArrayListExtra(EXTRA_MANUAL_FIELD_KEYS);
        ArrayList<String> labels = intent.getStringArrayListExtra(EXTRA_MANUAL_FIELD_LABELS);
        manualFieldKeys = keys == null ? new ArrayList<>() : keys;
        manualFieldLabels = labels == null ? new ArrayList<>() : labels;
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopCapture();
            return START_NOT_STICKY;
        }
        startProjection(resultCode, resultData);
        return START_NOT_STICKY;
    }

    private void startProjection(int resultCode, Intent resultData) {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(resultCode, resultData);
        if (projection == null) {
            stopCapture();
            return;
        }

        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        getSystemService(android.view.WindowManager.class).getDefaultDisplay().getRealMetrics(metrics);
        captureWidth = metrics.widthPixels;
        captureHeight = metrics.heightPixels;
        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopCapture(); }
        }, main);
        virtualDisplay = projection.createVirtualDisplay(
                "AoO KB Scanner",
                captureWidth,
                captureHeight,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                captureHandler);
        imageReader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);
        isRunning = true;
        overlay.show();
        notifyStatus("STATUS: Scanner aktiv");
        if (pendingManualReportId != null) {
            String reportId = pendingManualReportId;
            pendingManualReportId = null;
            analysisExecutor.execute(() -> beginManualSession(reportId, manualFieldKeys, manualFieldLabels));
        }
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastFrameAt < FRAME_INTERVAL_MS || !processing.compareAndSet(false, true)) {
            image.close();
            return;
        }
        lastFrameAt = now;
        Bitmap bitmap;
        try {
            bitmap = imageToBitmap(image, captureWidth, captureHeight);
        } catch (RuntimeException error) {
            image.close();
            processing.set(false);
            return;
        }
        image.close();

        recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(analysisExecutor, text -> {
                    ParsedFrame parsed = parser.parse(text, bitmap);
                    AnalysisResult result = handleParsedFrame(parsed);
                    if (scanState == ScanState.SCANNING && assembler.currentReportId() != null &&
                            (parsed.screenType == ScreenType.BATTLE_SUMMARY ||
                                    parsed.screenType == ScreenType.ARMY_INFO)) {
                        evidenceStore.capture(bitmap, parsed, result);
                    }
                    overlay.update(result, bitmap.getWidth(), bitmap.getHeight());
                    notifyStatus(result.status.replace('\n', ' '));
                })
                .addOnFailureListener(analysisExecutor, error -> notifyStatus("OCR-Fehler · weiter scannen"))
                .addOnCompleteListener(analysisExecutor, task -> {
                    if (!bitmap.isRecycled()) bitmap.recycle();
                    processing.set(false);
                });
    }

    private synchronized AnalysisResult handleParsedFrame(ParsedFrame frame) {
        if (scanState == ScanState.WAITING) {
            if (frame.screenType == ScreenType.BATTLE_SUMMARY) {
                pendingHeader = frame;
                scanState = ScanState.READY;
                overlay.showControl("Scan starten", () -> analysisExecutor.execute(this::beginScanSession));
                String mode = eventMode ? (resourceField ? "BF / 50 %" : "Battle Frenzy") : "Standard";
                return new AnalysisResult(frame.boxes,
                        "STATUS | BERICHT ERKANNT\nAKTION | SCAN STARTEN\nMODUS  | " + mode,
                        BoxState.VALID);
            }
            if (frame.screenType == ScreenType.ARMY_INFO) {
                pendingHeader = new ParsedFrame();
                pendingHeader.screenType = ScreenType.BATTLE_SUMMARY;
                scanState = ScanState.READY;
                overlay.showControl("Scan starten", () -> analysisExecutor.execute(this::beginScanSession));
                return new AnalysisResult(frame.boxes,
                        "STATUS | BERICHTDETAIL ERKANNT\nAKTION | SCAN STARTEN",
                        BoxState.VALID);
            }
            if (frame.screenType == ScreenType.MESSAGE_LIST) {
                return new AnalysisResult(frame.boxes,
                        "STATUS | NACHRICHTEN\nAKTION | BERICHT ÖFFNEN", BoxState.PENDING);
            }
            return new AnalysisResult(frame.boxes,
                    "STATUS | SCANNER AKTIV\nAKTION | WARTE AUF BERICHT", BoxState.PENDING);
        }

        if (scanState == ScanState.READY) {
            if (frame.screenType == ScreenType.BATTLE_SUMMARY) pendingHeader = frame;
            if (frame.screenType == ScreenType.MESSAGE_LIST) {
                pendingHeader = null;
                scanState = ScanState.WAITING;
                overlay.hideControl();
                return new AnalysisResult(frame.boxes,
                        "STATUS | SCAN VERWORFEN\nAKTION | BERICHT ÖFFNEN", BoxState.PENDING);
            }
            return new AnalysisResult(frame.boxes,
                    "STATUS | BERICHT ERKANNT\nAKTION | SCAN STARTEN", BoxState.VALID);
        }

        if (scanState == ScanState.SCANNING) {
            AnalysisResult accepted = assembler.acceptFrame(frame);
            if (!manualMode) return accepted;
            for (OverlayBox box : accepted.boxes) {
                if (box.fieldKey != null && box.candidateValue != null && !box.candidateValue.isEmpty()) {
                    manualRemaining.remove(box.fieldKey);
                }
            }
            return new AnalysisResult(accepted.boxes, accepted.status + manualTargetStatus(), accepted.statusState);
        }

        boolean outside = frame.screenType == ScreenType.MESSAGE_LIST || frame.screenType == ScreenType.NONE;
        outsideReportFrames = outside ? outsideReportFrames + 1 : 0;
        if (outsideReportFrames >= 2) {
            scanState = ScanState.WAITING;
            outsideReportFrames = 0;
            return new AnalysisResult(frame.boxes,
                    "STATUS | SCANNER AKTIV\nAKTION | WARTE AUF BERICHT", BoxState.PENDING);
        }
        return new AnalysisResult(new ArrayList<>(), finishedStatus +
                "\nAKTION | ZUR NACHRICHTENLISTE", BoxState.VALID);
    }

    private synchronized void beginScanSession() {
        if (scanState != ScanState.READY || pendingHeader == null) return;
        AnalysisResult result = assembler.startSession(pendingHeader, eventMode, resourceField);
        evidenceStore.begin(assembler.currentReportId());
        manualMode = false;
        pendingHeader = null;
        scanState = ScanState.SCANNING;
        overlay.showControl("Scan beenden", () -> analysisExecutor.execute(this::finishScanSession));
        overlay.update(result, captureWidth, captureHeight);
        notifyStatus("STATUS: Berichtsscan läuft");
    }

    private synchronized void beginManualSession(String reportId, ArrayList<String> keys,
                                                 ArrayList<String> labels) {
        if (reportId == null || reportId.trim().isEmpty()) return;
        if (scanState == ScanState.SCANNING || assembler.isSessionActive()) {
            overlay.update(new AnalysisResult(new ArrayList<>(),
                    "STATUS | SCAN BEREITS AKTIV\nAKTION | ZUERST BEENDEN", BoxState.INVALID),
                    captureWidth, captureHeight);
            return;
        }
        manualFieldKeys = keys == null ? new ArrayList<>() : new ArrayList<>(keys);
        manualFieldLabels = labels == null ? new ArrayList<>() : new ArrayList<>(labels);
        manualRemaining.clear();
        manualRemaining.addAll(manualFieldKeys);
        manualTargetTotal = manualFieldKeys.size();
        ScannerDatabase.ReportMode mode = database.reportMode(reportId);
        eventMode = mode.eventMode;
        resourceField = mode.resourceField;
        AnalysisResult result = assembler.resumeSession(reportId);
        if (!assembler.isSessionActive()) {
            overlay.update(result, captureWidth, captureHeight);
            return;
        }
        evidenceStore.begin(reportId);
        pendingHeader = null;
        manualMode = true;
        scanState = ScanState.SCANNING;
        outsideReportFrames = 0;
        overlay.showControl("Nachscan beenden", () -> analysisExecutor.execute(this::finishScanSession));
        overlay.update(new AnalysisResult(result.boxes,
                "STATUS | MANUELLER NACHSCAN AKTIV" + manualTargetStatus(), BoxState.PENDING),
                captureWidth, captureHeight);
        notifyStatus("STATUS: Manueller Nachscan läuft");
    }

    private synchronized void finishScanSession() {
        if (scanState != ScanState.SCANNING) return;
        String completedReportId = assembler.currentReportId();
        AnalysisResult result = assembler.finishSession();
        evidenceStore.finish();
        finishedStatus = result.status;
        scanState = ScanState.WAIT_FOR_EXIT;
        outsideReportFrames = 0;
        overlay.hideControl();
        overlay.update(result, captureWidth, captureHeight);
        notifyStatus("STATUS: Bericht gespeichert");
        manualMode = false;
        manualFieldKeys.clear();
        manualFieldLabels.clear();
        manualRemaining.clear();
        manualTargetTotal = 0;
        if (completedReportId != null) new ReportCollageBuilder(this, database).build(completedReportId);
    }

    private String manualTargetStatus() {
        if (!manualMode && manualFieldLabels.isEmpty()) return "";
        int found = Math.max(0, manualTargetTotal - manualRemaining.size());
        StringBuilder value = new StringBuilder("\nNACHSCAN | ").append(found).append("/")
                .append(manualTargetTotal).append(" ERKANNT");
        String label = firstRemainingLabel();
        if (label != null) {
            value.append("\nZIEL    | ").append(label.length() > 34 ? label.substring(0, 33) + "…" : label);
        }
        return value.toString();
    }

    private String firstRemainingLabel() {
        for (int i = 0; i < manualFieldKeys.size(); i++) {
            if (manualRemaining.contains(manualFieldKeys.get(i))) {
                return i < manualFieldLabels.size() ? manualFieldLabels.get(i) : manualFieldKeys.get(i);
            }
        }
        return manualTargetTotal > 0 ? "Alle markierten Werte gesehen" : null;
    }

    private Bitmap imageToBitmap(Image image, int width, int height) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * width;
        int paddedWidth = width + rowPadding / pixelStride;
        Bitmap padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
        buffer.rewind();
        padded.copyPixelsFromBuffer(buffer);
        if (paddedWidth == width) return padded;
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
        padded.recycle();
        return cropped;
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification(String message) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, CaptureService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("AoO KB Scanner")
                .setContentText(message)
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "Stoppen", stopIntent).build())
                .build();
    }

    private void notifyStatus(String message) {
        main.post(() -> ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIFICATION_ID, buildNotification(message)));
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Bildschirm-Scanner",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Status der laufenden Kampfbericht-Erfassung");
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    private Intent parcelableIntent(Intent source, String key) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return source.getParcelableExtra(key, Intent.class);
        }
        //noinspection deprecation
        return source.getParcelableExtra(key);
    }

    private synchronized void stopCapture() {
        if (shuttingDown) return;
        shuttingDown = true;
        isRunning = false;
        if (assembler != null && assembler.isSessionActive()) assembler.finishSession();
        if (imageReader != null) {
            imageReader.setOnImageAvailableListener(null, null);
            imageReader.close();
            imageReader = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        MediaProjection current = projection;
        projection = null;
        if (current != null) current.stop();
        if (overlay != null) overlay.hide();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (!shuttingDown) stopCapture();
        if (recognizer != null) recognizer.close();
        if (database != null) database.close();
        analysisExecutor.shutdownNow();
        if (captureThread != null) captureThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
