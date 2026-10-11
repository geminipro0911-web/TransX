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
            title = "TransX - Dịch Màn Hình (OCR Offline)";
            desc = "Sẵn sàng quét chữ màn hình không cần mạng";
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

        // 1. Chức năng OCR Màn hình (Offline)
        if (needOcr) {
            if (projection != null && screenCapturer == null) {
                DisplayMetrics dm = getResources().getDisplayMetrics();
                screenCapturer = new ScreenCapturer(projection, dm.widthPixels, dm.heightPixels, dm.densityDpi);
                screenCapturer.start();
            }
            if (!needAudio) {
                floatingManager.updateStatus("Chế độ OCR Offline: Chạm 📷 hoặc 🔲 để dịch chữ", Color.parseColor("#38BDF8"));
            }
        }

        // 2. Chức năng Dịch Giọng Nói STT
        if (needAudio) {
            boolean useInternal = "internal".equals(prefs.getAudioSource());
            if (ttsPlayer == null) {
                ttsPlayer = new TtsPlayer(this, mute -> {
                    if (capturer != null) {
                        capturer.setMuted(mute);
                    }
                });
            }

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
            processOcrBitmap(bmp, "Toàn màn hình");
        });
    }

    private void captureAndTranslateRegion(Rect region) {
        if (screenCapturer == null) {
            Toast.makeText(this, "Chưa khởi tạo chụp màn hình", Toast.LENGTH_SHORT).show();
            return;
        }

        Bitmap bmp = screenCapturer.captureRegion(region);
        processOcrBitmap(bmp, "Vùng chọn");
    }

    private void processOcrBitmap(Bitmap bitmap, String sourceLabel) {
        if (bitmap == null) {
            floatingManager.updateStatus("Không lấy được hình ảnh màn hình", Color.parseColor("#EF4444"));
            return;
        }

        floatingManager.updateStatus("Đang quét OCR chữ (Offline ML Kit)...", Color.parseColor("#F59E0B"));

        processPool.execute(() -> {
            try {
                String srcLang = prefs.getSourceLang();
                // Nhận dạng hoàn toàn Offline trên máy
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
