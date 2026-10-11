package com.transx.screen;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.transx.screen.audio.InternalAudioCapturer;
import com.transx.screen.audio.VadSegmenter;
import com.transx.screen.ocr.OfflineOcrEngine;
import com.transx.screen.ocr.ScreenCapturer;
import com.transx.screen.speech.EdgeTranslator;
import com.transx.screen.speech.GoogleSpeechClient;
import com.transx.screen.speech.TtsPlayer;
import com.transx.screen.util.Lang;
import com.transx.screen.util.Prefs;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TransXService extends Service {
    private static final String TAG = "TransXService";
    private static final String CHANNEL_ID = "transx_screen_channel";
    private static final int NOTIF_ID = 2026;

    public static final String EXTRA_MODE = "service_mode";
    public static final String MODE_OCR = "mode_ocr";
    public static final String MODE_STT = "mode_stt";
    public static final String MODE_BOTH = "mode_both";

    public static Intent pendingProjectionData = null;
    public static int pendingProjectionCode = 0;

    private String currentMode = MODE_BOTH;

    private Handler mainHandler;
    private Prefs prefs;
    private MediaProjection projection;
    private InternalAudioCapturer capturer;
    private VadSegmenter vad;
    private ScreenCapturer screenCapturer;
    private TtsPlayer ttsPlayer;
    private EdgeTranslator translator;
    private FloatingManager floatingManager;
    private ExecutorService processPool;
    private boolean isListening = false;

    // Vòng lặp quét OCR Live liên tục
    private volatile boolean isLiveOcrRunning = false;
    private Thread liveOcrThread;
    private Rect liveScanRegion = null;
    private long lastFrameHash = 0;
    private String lastRecognizedText = "";
    private long lastOcrTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        prefs = new Prefs(this);
        translator = new EdgeTranslator();
        processPool = Executors.newSingleThreadExecutor();

        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(
                    CHANNEL_ID,
                    "TransX Screen Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            chan.setDescription("Dịch giọng nói và màn hình thời gian thực");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(chan);
        }
    }

    private Notification buildNotification() {
        Intent notifIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, notifIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        String title = "TransX Screen đang chạy";
        String desc = "Dịch thời gian thực trên màn hình";
        if (MODE_OCR.equals(currentMode)) {
            title = "TransX - Dịch Màn Hình (Live OCR Offline)";
            desc = "Đang tự động quét và dịch chữ trên màn hình";
        } else if (MODE_STT.equals(currentMode)) {
            title = "TransX - Dịch Giọng Nói (Live STT)";
            desc = "Đang nhận dạng âm thanh thời gian thực";
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(desc)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra(EXTRA_MODE)) {
            currentMode = intent.getStringExtra(EXTRA_MODE);
        }

        // Start foreground immediately
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
                if (!MODE_OCR.equals(currentMode) && "mic".equals(prefs.getAudioSource())) {
                    type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                }
                startForeground(NOTIF_ID, buildNotification(), type);
            } else {
                startForeground(NOTIF_ID, buildNotification());
            }
        } catch (Throwable t) {
            Log.e(TAG, "startForeground failed: " + t.getMessage());
        }

        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // Setup Floating HUD
        if (floatingManager == null) {
            floatingManager = new FloatingManager(this, currentMode, new FloatingManager.FloatingListener() {
                @Override
                public void onToggleListening() {
                    toggleSpeechPipeline();
                }

                @Override
                public void onToggleLiveOcr() {
                    toggleLiveOcr();
                }

                @Override
                public void onStopService() {
                    stopSelf();
                }

                @Override
                public void onToggleTts() {
                    prefs.setTtsEnabled(!prefs.isTtsEnabled());
                    String msg = prefs.isTtsEnabled() ? "Đã bật âm thanh đọc" : "Đã tắt âm thanh đọc";
                    Toast.makeText(TransXService.this, msg, Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onCaptureScreenRequested() {
                    captureAndTranslateFullScreen();
                }

                @Override
                public void onRegionCropRequested(Rect region) {
                    liveScanRegion = region;
                    Toast.makeText(TransXService.this, "Đã khóa vùng quét Live!", Toast.LENGTH_SHORT).show();
                    if (!isLiveOcrRunning) {
                        startLiveOcr();
                    }
                    // Chạy quét ngay lập tức vùng vừa chọn
                    captureAndTranslateRegion(region);
                }
            });
            floatingManager.showAll();
        } else {
            floatingManager.setMode(currentMode);
        }

        initPipeline();
        return START_STICKY;
    }

    private void initPipeline() {
        boolean needOcr = MODE_OCR.equals(currentMode) || MODE_BOTH.equals(currentMode);
        boolean needAudio = MODE_STT.equals(currentMode) || MODE_BOTH.equals(currentMode);

        if (pendingProjectionData != null && projection == null) {
            try {
                MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                if (mpm != null) {
                    projection = mpm.getMediaProjection(pendingProjectionCode, pendingProjectionData);
                    projection.registerCallback(new MediaProjection.Callback() {
                        @Override public void onStop() {
                            stopPipeline();
                        }
                    }, mainHandler);
                }
            } catch (Throwable e) {
                Log.e(TAG, "MediaProjection error: " + e.getMessage());
            }
        }

        if (ttsPlayer == null) {
            ttsPlayer = new TtsPlayer(this, mute -> {
                if (capturer != null) {
                    capturer.setMuted(mute);
                }
            });
        }

        // 1. Khởi động Screen Capturer & Tự động quét Live OCR
        if (needOcr) {
            if (projection != null && screenCapturer == null) {
                DisplayMetrics dm = getResources().getDisplayMetrics();
                screenCapturer = new ScreenCapturer(projection, dm.widthPixels, dm.heightPixels, dm.densityDpi);
                screenCapturer.start();
            }
            startLiveOcr();
        }

        // 2. Chức năng Dịch Giọng Nói STT
        if (needAudio) {
            boolean useInternal = "internal".equals(prefs.getAudioSource());

            if (vad == null) {
                vad = new VadSegmenter(this::onSpeechSegmentDetected);
            }

            if (capturer == null) {
                capturer = new InternalAudioCapturer(projection, !useInternal, (samples, n) -> {
                    if (vad != null) {
                        vad.feed(samples, n);
                    }
                });
            }

            if (capturer.start()) {
                isListening = true;
                floatingManager.updateStatus("Đang lắng nghe âm thanh (Live STT)...", Color.parseColor("#10B981"));
            } else {
                floatingManager.updateStatus("Lỗi khởi động thu âm", Color.parseColor("#EF4444"));
            }
        }
    }

    // ---------- VÒNG LẶP LIVE OCR LIÊN TỤC TRÊN MÀN HÌNH ----------

    private synchronized void startLiveOcr() {
        if (isLiveOcrRunning) return;
        isLiveOcrRunning = true;
        if (floatingManager != null) {
            floatingManager.setLiveOcrState(true);
            floatingManager.updateStatus("Live OCR: Đang tự động quét...", Color.parseColor("#10B981"));
        }

        liveOcrThread = new Thread(() -> {
            Log.i(TAG, "Live OCR background loop started");
            while (isLiveOcrRunning) {
                try {
                    long now = System.currentTimeMillis();
                    // Giãn cách tối thiểu giữa 2 lần quét: 300ms
                    if (now - lastOcrTime < 300) {
                        Thread.sleep(80);
                        continue;
                    }

                    if (screenCapturer == null) {
                        Thread.sleep(200);
                        continue;
                    }

                    // Chụp frame mới nhất (toàn màn hình hoặc theo vùng đã khóa)
                    Bitmap bmp = screenCapturer.captureLatestFrame(liveScanRegion);
                    if (bmp == null) {
                        Thread.sleep(120);
                        continue;
                    }

                    // 1. Tính hash 8x8 cực nhẹ (<0.5ms) để kiểm tra màn hình có thay đổi không
                    long currentHash = ScreenCapturer.averageHash(bmp);
                    int diff = ScreenCapturer.hamming(lastFrameHash, currentHash);
                    lastFrameHash = currentHash;

                    // Nếu màn hình không có chuyển động chữ mới (diff < 3) và đã có text cũ -> bỏ qua
                    if (diff < 3 && !lastRecognizedText.isEmpty()) {
                        bmp.recycle();
                        Thread.sleep(250);
                        continue;
                    }

                    lastOcrTime = System.currentTimeMillis();

                    // 2. Chạy nhận dạng chữ hoàn toàn Offline qua Google ML Kit (~30-50ms)
                    String srcLang = prefs.getSourceLang();
                    String recognized = OfflineOcrEngine.recognizeText(bmp, srcLang);
                    bmp.recycle();

                    if (recognized == null || recognized.trim().isEmpty()) {
                        Thread.sleep(200);
                        continue;
                    }

                    // Làm sạch chuỗi để so sánh
                    String cleanText = recognized.trim().replaceAll("\\s+", " ");
                    if (cleanText.equalsIgnoreCase(lastRecognizedText)) {
                        Thread.sleep(200);
                        continue;
                    }
                    lastRecognizedText = cleanText;

                    // 3. Có chữ mới xuất hiện trên màn hình -> Dịch ngay qua Edge API
                    mainHandler.post(() -> floatingManager.updateStatus("Live OCR: Đang dịch...", Color.parseColor("#38BDF8")));

                    String tgtLang = prefs.getTargetLang();
                    String translated = translator.translateSingle(cleanText, srcLang, tgtLang);

                    mainHandler.post(() -> {
                        String label = (liveScanRegion != null) ? "Vùng quét" : "Màn hình";
                        floatingManager.addTranslation("[" + label + "] " + cleanText, translated);
                        floatingManager.updateStatus("Live OCR: Đang theo dõi...", Color.parseColor("#10B981"));
                    });

                    // Đọc phát âm nếu bật TTS
                    if (prefs.isTtsEnabled() && ttsPlayer != null) {
                        Lang targetLang = Lang.findByEdgeCode(tgtLang);
                        ttsPlayer.speak(translated, targetLang.googleSttCode);
                    }

                    Thread.sleep(250);

                } catch (InterruptedException e) {
                    break;
                } catch (Throwable e) {
                    Log.e(TAG, "Live OCR loop error: " + e.getMessage());
                    try { Thread.sleep(300); } catch (Exception ignored) {}
                }
            }
            Log.i(TAG, "Live OCR background loop stopped");
        }, "live-ocr-loop");

        liveOcrThread.setPriority(Thread.NORM_PRIORITY);
        liveOcrThread.start();
    }

    private synchronized void stopLiveOcr() {
        isLiveOcrRunning = false;
        if (floatingManager != null) {
            floatingManager.setLiveOcrState(false);
            floatingManager.updateStatus("Live OCR: Đã tạm dừng", Color.parseColor("#64748B"));
        }
        if (liveOcrThread != null) {
            liveOcrThread.interrupt();
            liveOcrThread = null;
        }
    }

    private void toggleLiveOcr() {
        if (isLiveOcrRunning) {
            stopLiveOcr();
        } else {
            startLiveOcr();
        }
    }

    // ---------- VÒNG LẶP DỊCH GIỌNG NÓI STT ----------

    private void onSpeechSegmentDetected(float[] pcmSamples) {
        floatingManager.updateStatus("Đang nhận dạng...", Color.parseColor("#F59E0B"));

        processPool.execute(() -> {
            try {
                String apiKey = prefs.getGoogleApiKey();
                String srcLangCode = prefs.getSourceLang();
                Lang srcLang = Lang.findByEdgeCode(srcLangCode);

                String recognizedText = GoogleSpeechClient.recognize(pcmSamples, srcLang.googleSttCode, apiKey);
                if (recognizedText == null || recognizedText.trim().isEmpty()) {
                    mainHandler.post(() -> floatingManager.updateStatus("Đang lắng nghe trực tiếp...", Color.parseColor("#10B981")));
                    return;
                }

                mainHandler.post(() -> floatingManager.updateStatus("Đang dịch: " + recognizedText, Color.parseColor("#38BDF8")));

                String targetLangCode = prefs.getTargetLang();
                String translatedText = translator.translateSingle(recognizedText, srcLang.edgeCode, targetLangCode);

                mainHandler.post(() -> {
                    floatingManager.addTranslation(recognizedText, translatedText);
                    floatingManager.updateStatus("Đang đọc...", Color.parseColor("#818CF8"));
                });

                if (prefs.isTtsEnabled() && ttsPlayer != null) {
                    Lang targetLang = Lang.findByEdgeCode(targetLangCode);
                    ttsPlayer.speak(translatedText, targetLang.googleSttCode);
                }

                mainHandler.postDelayed(() -> {
                    floatingManager.updateStatus("Đang lắng nghe trực tiếp...", Color.parseColor("#10B981"));
                }, 800);

            } catch (Throwable e) {
                Log.e(TAG, "Audio Pipeline error: " + e.getMessage());
                mainHandler.post(() -> floatingManager.updateStatus("Lỗi STT: " + e.getMessage(), Color.parseColor("#EF4444")));
            }
        });
    }

    private void captureAndTranslateFullScreen() {
        if (screenCapturer == null) {
            Toast.makeText(this, "Chưa khởi tạo chụp màn hình. Hãy bật lại quyền quay màn hình", Toast.LENGTH_LONG).show();
            return;
        }

        floatingManager.hideTemporarily(() -> {
            Bitmap bmp = screenCapturer.captureScreen();
            processSingleOcrBitmap(bmp, "Chụp nhanh");
        });
    }

    private void captureAndTranslateRegion(Rect region) {
        if (screenCapturer == null) {
            Toast.makeText(this, "Chưa khởi tạo chụp màn hình", Toast.LENGTH_SHORT).show();
            return;
        }

        Bitmap bmp = screenCapturer.captureRegion(region);
        processSingleOcrBitmap(bmp, "Vùng chọn");
    }

    private void processSingleOcrBitmap(Bitmap bitmap, String sourceLabel) {
        if (bitmap == null) {
            floatingManager.updateStatus("Không lấy được hình ảnh màn hình", Color.parseColor("#EF4444"));
            return;
        }

        floatingManager.updateStatus("Đang quét OCR chữ (Offline ML Kit)...", Color.parseColor("#F59E0B"));

        processPool.execute(() -> {
            try {
                String srcLang = prefs.getSourceLang();
                String recognized = OfflineOcrEngine.recognizeText(bitmap, srcLang);
                bitmap.recycle();

                if (recognized == null || recognized.trim().isEmpty()) {
                    mainHandler.post(() -> {
                        floatingManager.updateStatus("Không tìm thấy chữ trên màn hình", Color.parseColor("#94A3B8"));
                        Toast.makeText(this, "Không tìm thấy chữ trên màn hình", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                mainHandler.post(() -> floatingManager.updateStatus("Đang dịch chữ OCR...", Color.parseColor("#38BDF8")));

                String tgtLang = prefs.getTargetLang();
                String translated = translator.translateSingle(recognized, srcLang, tgtLang);

                mainHandler.post(() -> {
                    floatingManager.addTranslation("[" + sourceLabel + "] " + recognized, translated);
                    floatingManager.updateStatus("Đã dịch xong chữ màn hình", Color.parseColor("#10B981"));
                });

                if (prefs.isTtsEnabled() && ttsPlayer != null) {
                    Lang targetLang = Lang.findByEdgeCode(tgtLang);
                    ttsPlayer.speak(translated, targetLang.googleSttCode);
                }

            } catch (Throwable e) {
                Log.e(TAG, "OCR error: " + e.getMessage());
                mainHandler.post(() -> {
                    floatingManager.updateStatus("Lỗi OCR: " + e.getMessage(), Color.parseColor("#EF4444"));
                    Toast.makeText(this, "Lỗi OCR: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void toggleSpeechPipeline() {
        if (isListening) {
            stopAudioOnly();
            floatingManager.updateStatus("Tạm dừng nghe STT", Color.parseColor("#64748B"));
        } else {
            currentMode = MODE_STT;
            initPipeline();
        }
    }

    private void stopAudioOnly() {
        isListening = false;
        if (capturer != null) {
            capturer.stop();
            capturer = null;
        }
        if (ttsPlayer != null) {
            ttsPlayer.stop();
        }
    }

    private void stopPipeline() {
        stopAudioOnly();
        stopLiveOcr();
        if (screenCapturer != null) {
            screenCapturer.stop();
            screenCapturer = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopPipeline();
        if (ttsPlayer != null) {
            ttsPlayer.release();
            ttsPlayer = null;
        }
        if (projection != null) {
            try { projection.stop(); } catch (Exception ignored) {}
            projection = null;
        }
        if (floatingManager != null) {
            floatingManager.removeAll();
            floatingManager = null;
        }
        if (processPool != null) {
            processPool.shutdownNow();
        }
        OfflineOcrEngine.release();
        pendingProjectionData = null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
