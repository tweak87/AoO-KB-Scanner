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
import com.tweak87.aookbscanner.ocr.OcrParser;
import com.tweak87.aookbscanner.ocr.ReportAssembler;
import com.tweak87.aookbscanner.overlay.OverlayController;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground service that samples MediaProjection frames and runs bundled on-device OCR. */
public final class CaptureService extends Service {
    public static final String ACTION_START = "com.tweak87.aookbscanner.START";
    public static final String ACTION_STOP = "com.tweak87.aookbscanner.STOP";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
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
    private int captureWidth;
    private int captureHeight;
    private long lastFrameAt;
    private boolean shuttingDown;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        overlay = new OverlayController(this);
        parser = new OcrParser();
        assembler = new ReportAssembler(new ScannerDatabase(this));
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
        if (!ACTION_START.equals(intent.getAction()) || isRunning) return START_NOT_STICKY;

        startForegroundCompat(buildNotification("Scanner wird gestartet …"));
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent resultData = parcelableIntent(intent, EXTRA_RESULT_DATA);
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
        notifyStatus("Scanner aktiv · OCR lokal auf dem Gerät");
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
                    AnalysisResult result = assembler.consume(parser.parse(text, bitmap));
                    overlay.update(result, bitmap.getWidth(), bitmap.getHeight());
                    notifyStatus(result.status);
                })
                .addOnFailureListener(analysisExecutor, error -> notifyStatus("OCR-Fehler · weiter scannen"))
                .addOnCompleteListener(analysisExecutor, task -> {
                    if (!bitmap.isRecycled()) bitmap.recycle();
                    processing.set(false);
                });
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
        analysisExecutor.shutdownNow();
        if (captureThread != null) captureThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
