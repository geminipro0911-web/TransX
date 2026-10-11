package com.transx.screen;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.transx.screen.audio.InternalAudioCapturer;
import com.transx.screen.audio.VadSegmenter;
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

    public static Intent pendingProjectionData = null;
    public static int pendingProjectionCode = 0;

    private Handler mainHandler;
    private Prefs prefs;
    private MediaProjection projection;
    private InternalAudioCapturer capturer;
    private VadSegmenter vad;
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
            chan.setDescription("Dịch giọng nói thời gian thực trên màn hình");
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

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("TransX Screen đang chạy")
                .setContentText("Đang dịch giọng nói thời gian thực")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Start foreground immediately
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                if ("internal".equals(prefs.getAudioSource()) && pendingProjectionData != null) {
                    type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
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
            floatingManager = new FloatingManager(this, new FloatingManager.FloatingListener() {
                @Override
                public void onToggleListening() {
                    togglePipeline();
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
            });
            floatingManager.showAll();
        }

        initPipeline();
        return START_STICKY;
    }

    private void initPipeline() {
        boolean useInternal = "internal".equals(prefs.getAudioSource());
        if (useInternal && pendingProjectionData != null) {
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

        ttsPlayer = new TtsPlayer(this, mute -> {
            if (capturer != null) {
                capturer.setMuted(mute);
            }
        });

        vad = new VadSegmenter(this::onSpeechSegmentDetected);
        capturer = new InternalAudioCapturer(projection, !useInternal, (samples, n) -> {
            if (vad != null) {
                vad.feed(samples, n);
            }
        });

        if (capturer.start()) {
            isListening = true;
            floatingManager.updateStatus("Đang lắng nghe âm thanh...", Color.parseColor("#10B981"));
        } else {
            floatingManager.updateStatus("Lỗi khởi động thu âm", Color.parseColor("#EF4444"));
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
                    mainHandler.post(() -> floatingManager.updateStatus("Đang lắng nghe...", Color.parseColor("#10B981")));
                    return;
                }

                mainHandler.post(() -> floatingManager.updateStatus("Đang dịch: " + recognizedText, Color.parseColor("#38BDF8")));

                String targetLangCode = prefs.getTargetLang();
                String translatedText = translator.translateSingle(recognizedText, srcLang.edgeCode, targetLangCode);

                mainHandler.post(() -> {
                    floatingManager.addTranslation(recognizedText, translatedText);
                    floatingManager.updateStatus("Đang đọc...", Color.parseColor("#818CF8"));
                });

                Lang targetLang = Lang.findByEdgeCode(targetLangCode);
                ttsPlayer.speak(translatedText, targetLang.googleSttCode);

                mainHandler.postDelayed(() -> {
                    floatingManager.updateStatus("Đang lắng nghe...", Color.parseColor("#10B981"));
                }, 1000);

            } catch (Throwable e) {
                Log.e(TAG, "Pipeline error: " + e.getMessage());
                mainHandler.post(() -> floatingManager.updateStatus("Lỗi: " + e.getMessage(), Color.parseColor("#EF4444")));
            }
        });
    }

    private void togglePipeline() {
        if (isListening) {
            stopPipeline();
            floatingManager.updateStatus("Tạm dừng nghe", Color.parseColor("#64748B"));
        } else {
            initPipeline();
        }
    }

    private void stopPipeline() {
        isListening = false;
        if (capturer != null) {
            capturer.stop();
            capturer = null;
        }
        if (ttsPlayer != null) {
            ttsPlayer.stop();
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
        pendingProjectionData = null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
