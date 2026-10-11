package com.transx.screen;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import com.transx.screen.speech.GoogleSpeechClient;
import com.transx.screen.speech.TranslationManager;
import com.transx.screen.speech.TtsPlayer;
import com.transx.screen.util.Lang;
import com.transx.screen.util.Prefs;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

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
    private TranslationManager translationManager;
    private FloatingManager floatingManager;
    private ExecutorService processPool;
    private boolean isListening = false;
    private volatile boolean isScreenOn = true;

    // Vòng lặp quét OCR Live liên tục & Generation Epochs
    private volatile boolean isLiveOcrRunning = false;
    private final AtomicLong liveOcrEpoch = new AtomicLong(0);
    private final AtomicLong audioEpoch = new AtomicLong(0);

    private Thread liveOcrThread;
    private Rect liveScanRegion = null;
    private long lastFrameHash = 0;
    private String lastRecognizedText = "";
    private long lastOcrTime = 0;

    private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                isScreenOn = false;
                if (ttsPlayer != null) ttsPlayer.stop();
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                isScreenOn = true;
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        prefs = new Prefs(this);
        translationManager = new TranslationManager();
        processPool = Executors.newSingleThreadExecutor();

        createNotificationChannel();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenStateReceiver, filter);
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
                    if (!prefs.isTtsEnabled() && ttsPlayer != null) {
                        ttsPlayer.stop();
                    }
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
                    captureAndTranslateRegion(region);
                }

                @Override
                public void onClearHistory() {
                    if (ttsPlayer != null) {
                        ttsPlayer.stop();
                    }
                }

                @Override
                public void onCloseSubtitle() {
                    if (ttsPlayer != null) {
                        ttsPlayer.stop();
                    }
                }

                @Override
                public void onToggleInPlace(boolean enabled) {
                    if (!enabled && floatingManager != null) {
                        floatingManager.updateInPlaceBlocks(null);
                    }
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

    // ---------- LÀM SẠCH VĂN BẢN (LOẠI BỎ THẺ NGÔN NGỮ NGUỒN VÀ WATERMARK) ----------

    public static String cleanOcrText(String text, String srcLang) {
        if (text == null) return "";
        String s = text.trim();

        // 1. Loại bỏ các thẻ trong ngoặc vuông
        s = s.replaceAll("(?i)\\[\\s*(vùng quét|màn hình|chụp nhanh|vùng chọn|ngôn ngữ.*|source.*|lang.*|en|vi|ja|ko|zh|fr|de|es|ru|english|vietnamese|japanese|korean|chinese|french|german|spanish|russian|tiếng anh|tiếng việt|tiếng trung|tiếng nhật|tiếng hàn)\\s*\\]", "");

        // 2. Loại bỏ nhãn ngôn ngữ kèm dấu hai chấm ở cuối chuỗi
        s = s.replaceAll("(?i)[:\\-–—]\\s*\\[?\\s*(english|vietnamese|japanese|korean|chinese|french|german|spanish|russian|tiếng anh|tiếng việt|tiếng trung|tiếng nhật|tiếng hàn|tiếng pháp|tiếng đức|tiếng nga|en|vi|ja|ko|zh|fr|de|es|ru|auto|ngôn ngữ nguồn)\\s*\\]?\\s*$", "");

        // 3. Loại bỏ nhãn ngôn ngữ ở đầu chuỗi
        s = s.replaceAll("(?i)^\\s*\\[?\\s*(english|vietnamese|japanese|korean|chinese|french|german|spanish|russian|tiếng anh|tiếng việt|tiếng trung|tiếng nhật|tiếng hàn|en|vi|ja|ko|zh|fr|de|es|ru|ngôn ngữ nguồn)\\s*\\]?\\s*[:\\-–—]\\s*", "");

        // 4. Loại bỏ watermark logo ở cuối dòng
        s = s.replaceAll("(?i)\\b(ENGLISH|VIETNAMESE|JAPANESE|KOREAN|CHINESE|FRENCH|GERMAN|SPANISH|RUSSIAN|TIẾNG ANH|TIẾNG VIỆT)\\b\\s*$", "");
        s = s.replaceAll("(?i)\\b(LEAP PODCAST|ENGLISH PODCAST|PODCAST|LEAP)\\b\\s*$", "");

        // 5. Loại bỏ dấu hai chấm/gạch nối cuối chuỗi
        s = s.replaceAll("[:\\-–—]\\s*$", "");

        // 6. Chuẩn hóa khoảng trắng
        s = s.replaceAll("\\s+", " ").trim();

        return s;
    }

    public static String cleanSpokenText(String text) {
        if (text == null) return "";
        String s = text.trim();

        s = s.replaceAll("(?i)\\[.*?\\]", "");
        s = s.replaceAll("(?i)[:\\-–—]\\s*\\[?\\s*(english|vietnamese|japanese|korean|chinese|french|german|spanish|russian|tiếng anh|tiếng việt|tiếng trung|tiếng nhật|tiếng hàn|tiếng pháp|tiếng đức|tiếng nga|en|vi|ja|ko|zh|fr|de|es|ru|ngôn ngữ nguồn)\\s*\\]?\\s*$", "");
        s = s.replaceAll("(?i)^\\s*(tiếng anh|tiếng việt|tiếng trung|tiếng nhật|tiếng hàn|tiếng pháp|tiếng đức|tiếng nga|english|vietnamese|en|vi|ja|ko|zh|fr|de|es|ru)\\s*[:\\-–—]\\s*", "");
        s = s.replaceAll("[:\\-–—]\\s*$", "");
        s = s.replaceAll("\\s+", " ").trim();

        return s;
    }

    // ---------- VÒNG LẶP LIVE OCR LIÊN TỤC TRÊN MÀN HÌNH ----------

    private synchronized void startLiveOcr() {
        if (isLiveOcrRunning) return;
        isLiveOcrRunning = true;
        final long epoch = liveOcrEpoch.incrementAndGet();

        if (floatingManager != null) {
            floatingManager.setLiveOcrState(true);
            floatingManager.updateStatus("Live OCR: Đang tự động quét...", Color.parseColor("#10B981"));
        }

        liveOcrThread = new Thread(() -> {
            Log.i(TAG, "Live OCR background loop started (epoch=" + epoch + ")");
            while (isLiveOcrRunning && epoch == liveOcrEpoch.get()) {
                try {
                    // Nếu tắt màn hình điện thoại -> tạm nghỉ để tiết kiệm pin
                    if (!isScreenOn) {
                        Thread.sleep(400);
                        continue;
                    }

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

                    if (!isLiveOcrRunning || epoch != liveOcrEpoch.get()) {
                        bmp.recycle();
                        break;
                    }

                    // 1. Tính hash thông minh (nếu quét toàn màn hình thì ưu tiên hash 35% vùng phụ đề phía dưới)
                    long currentHash = (liveScanRegion != null)
                            ? ScreenCapturer.averageHash(bmp)
                            : ScreenCapturer.subtitleRegionHash(bmp);
                    int diff = ScreenCapturer.hamming(lastFrameHash, currentHash);
                    lastFrameHash = currentHash;

                    // Nếu màn hình không đổi (diff < 3) và đã có text cũ -> bỏ qua
                    if (diff < 3 && !lastRecognizedText.isEmpty()) {
                        bmp.recycle();
                        Thread.sleep(250);
                        continue;
                    }

                    lastOcrTime = System.currentTimeMillis();

                    String srcLang = prefs.getSourceLang();
                    String tgtLang = prefs.getTargetLang();

                    // 2. Chế độ Dịch đè trực tiếp (In-Place Mode): Vẽ chữ đè lên vị trí gốc trên màn hình
                    if (prefs.isInPlaceMode()) {
                        try {
                            List<OfflineOcrEngine.OcrBlock> blocks = OfflineOcrEngine.recognizeBlocks(bmp, srcLang, liveScanRegion);
                            if (blocks != null && !blocks.isEmpty()) {
                                for (OfflineOcrEngine.OcrBlock b : blocks) {
                                    String cleanB = cleanOcrText(b.originalText, srcLang);
                                    if (!cleanB.isEmpty()) {
                                        String t = translationManager.translate(cleanB, srcLang, tgtLang, prefs.isOfflineTranslatePreferred());
                                        b.translatedText = cleanSpokenText(t);
                                    }
                                }
                                mainHandler.post(() -> floatingManager.updateInPlaceBlocks(blocks));
                            }
                        } catch (Throwable ignored) {}
                    }

                    // 3. Chạy nhận dạng chữ toàn khối cho HUD và TTS
                    String recognized = OfflineOcrEngine.recognizeText(bmp, srcLang);
                    bmp.recycle();

                    if (!isLiveOcrRunning || epoch != liveOcrEpoch.get()) break;

                    if (recognized == null || recognized.trim().isEmpty()) {
                        Thread.sleep(200);
                        continue;
                    }

                    String cleanText = cleanOcrText(recognized, srcLang);
                    if (cleanText.isEmpty() || cleanText.equalsIgnoreCase(lastRecognizedText)) {
                        Thread.sleep(200);
                        continue;
                    }

                    // 4. Khử giật câu cuốn (Karaoke Debounce):
                    // Nếu câu mới đang hiển thị dần từng từ từ câu cũ, chờ 350ms để câu hoàn chỉnh trước khi đọc
                    if (!lastRecognizedText.isEmpty() && cleanText.startsWith(lastRecognizedText) && cleanText.length() > lastRecognizedText.length()) {
                        try {
                            Thread.sleep(350);
                            if (!isLiveOcrRunning || epoch != liveOcrEpoch.get()) break;
                            Bitmap nextBmp = screenCapturer.captureLatestFrame(liveScanRegion);
                            if (nextBmp != null) {
                                String nextRec = OfflineOcrEngine.recognizeText(nextBmp, srcLang);
                                nextBmp.recycle();
                                String nextClean = cleanOcrText(nextRec, srcLang);
                                if (!nextClean.isEmpty()) {
                                    cleanText = nextClean;
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                    lastRecognizedText = cleanText;

                    if (!isLiveOcrRunning || epoch != liveOcrEpoch.get()) break;
                    mainHandler.post(() -> {
                        if (isLiveOcrRunning && epoch == liveOcrEpoch.get()) {
                            floatingManager.updateStatus("Live OCR: Đang dịch...", Color.parseColor("#38BDF8"));
                        }
                    });

                    // 5. Dịch thông minh (LRU Cache + Offline ML Kit + Edge API fallback)
                    String translated = translationManager.translate(cleanText, srcLang, tgtLang, prefs.isOfflineTranslatePreferred());

                    if (!isLiveOcrRunning || epoch != liveOcrEpoch.get()) break;

                    String cleanTrans = cleanSpokenText(translated);
                    if (cleanTrans.isEmpty()) continue;

                    final String textToDisplay = cleanText;
                    mainHandler.post(() -> {
                        if (!isLiveOcrRunning || epoch != liveOcrEpoch.get()) return;
                        floatingManager.addTranslation(textToDisplay, cleanTrans);
                        floatingManager.updateStatus("Live OCR: Đang theo dõi...", Color.parseColor("#10B981"));
                    });

                    // 6. Đọc phát âm nếu bật TTS và user chưa ấn dừng
                    if (isLiveOcrRunning && epoch == liveOcrEpoch.get() && prefs.isTtsEnabled() && ttsPlayer != null) {
                        Lang targetLang = Lang.findByEdgeCode(tgtLang);
                        ttsPlayer.speak(cleanTrans, targetLang.googleSttCode);
                    }

                    Thread.sleep(250);

                } catch (InterruptedException e) {
                    break;
                } catch (Throwable e) {
                    Log.e(TAG, "Live OCR loop error: " + e.getMessage());
                    try { Thread.sleep(300); } catch (Exception ignored) {}
                }
            }
            Log.i(TAG, "Live OCR background loop stopped (epoch=" + epoch + ")");
        }, "live-ocr-loop");

        liveOcrThread.setPriority(Thread.NORM_PRIORITY);
        liveOcrThread.start();
    }

    private synchronized void stopLiveOcr() {
        isLiveOcrRunning = false;
        liveOcrEpoch.incrementAndGet();

        if (ttsPlayer != null) {
            ttsPlayer.stop();
        }

        if (floatingManager != null) {
            floatingManager.setLiveOcrState(false);
            floatingManager.updateStatus("Live OCR: Đã tạm dừng", Color.parseColor("#64748B"));
            floatingManager.updateInPlaceBlocks(null);
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
        final long epoch = audioEpoch.get();
        if (!isListening) return;

        floatingManager.updateStatus("Đang nhận dạng...", Color.parseColor("#F59E0B"));

        processPool.execute(() -> {
            try {
                if (!isListening || epoch != audioEpoch.get()) return;

                String apiKey = prefs.getGoogleApiKey();
                String srcLangCode = prefs.getSourceLang();
                Lang srcLang = Lang.findByEdgeCode(srcLangCode);

                String recognizedText = GoogleSpeechClient.recognize(pcmSamples, srcLang.googleSttCode, apiKey);
                if (!isListening || epoch != audioEpoch.get()) return;

                if (recognizedText == null || recognizedText.trim().isEmpty()) {
                    mainHandler.post(() -> {
                        if (isListening && epoch == audioEpoch.get()) {
                            floatingManager.updateStatus("Đang lắng nghe trực tiếp...", Color.parseColor("#10B981"));
                        }
                    });
                    return;
                }

                String cleanRec = cleanOcrText(recognizedText, srcLangCode);
                if (cleanRec.isEmpty()) return;

                mainHandler.post(() -> {
                    if (isListening && epoch == audioEpoch.get()) {
                        floatingManager.updateStatus("Đang dịch: " + cleanRec, Color.parseColor("#38BDF8"));
                    }
                });

                String targetLangCode = prefs.getTargetLang();
                String translatedText = translationManager.translate(cleanRec, srcLang.edgeCode, targetLangCode, prefs.isOfflineTranslatePreferred());

                if (!isListening || epoch != audioEpoch.get()) return;
                String cleanTrans = cleanSpokenText(translatedText);

                mainHandler.post(() -> {
                    if (!isListening || epoch != audioEpoch.get()) return;
                    floatingManager.addTranslation(cleanRec, cleanTrans);
                    floatingManager.updateStatus("Đang đọc...", Color.parseColor("#818CF8"));
                });

                if (isListening && epoch == audioEpoch.get() && prefs.isTtsEnabled() && ttsPlayer != null) {
                    Lang targetLang = Lang.findByEdgeCode(targetLangCode);
                    ttsPlayer.speak(cleanTrans, targetLang.googleSttCode);
                }

                mainHandler.postDelayed(() -> {
                    if (isListening && epoch == audioEpoch.get()) {
                        floatingManager.updateStatus("Đang lắng nghe trực tiếp...", Color.parseColor("#10B981"));
                    }
                }, 800);

            } catch (Throwable e) {
                Log.e(TAG, "Audio Pipeline error: " + e.getMessage());
                mainHandler.post(() -> {
                    if (isListening && epoch == audioEpoch.get()) {
                        floatingManager.updateStatus("Lỗi STT: " + e.getMessage(), Color.parseColor("#EF4444"));
                    }
                });
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

        final long epoch = liveOcrEpoch.get();
        processPool.execute(() -> {
            try {
                String srcLang = prefs.getSourceLang();
                String tgtLang = prefs.getTargetLang();

                if (prefs.isInPlaceMode()) {
                    try {
                        List<OfflineOcrEngine.OcrBlock> blocks = OfflineOcrEngine.recognizeBlocks(bitmap, srcLang, liveScanRegion);
                        if (blocks != null && !blocks.isEmpty()) {
                            for (OfflineOcrEngine.OcrBlock b : blocks) {
                                String cleanB = cleanOcrText(b.originalText, srcLang);
                                if (!cleanB.isEmpty()) {
                                    String t = translationManager.translate(cleanB, srcLang, tgtLang, prefs.isOfflineTranslatePreferred());
                                    b.translatedText = cleanSpokenText(t);
                                }
                            }
                            mainHandler.post(() -> floatingManager.updateInPlaceBlocks(blocks));
                        }
                    } catch (Throwable ignored) {}
                }

                String recognized = OfflineOcrEngine.recognizeText(bitmap, srcLang);
                bitmap.recycle();

                if (epoch != liveOcrEpoch.get()) return;

                if (recognized == null || recognized.trim().isEmpty()) {
                    mainHandler.post(() -> {
                        floatingManager.updateStatus("Không tìm thấy chữ trên màn hình", Color.parseColor("#94A3B8"));
                        Toast.makeText(this, "Không tìm thấy chữ trên màn hình", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                String cleanRec = cleanOcrText(recognized, srcLang);
                if (cleanRec.isEmpty()) return;

                mainHandler.post(() -> floatingManager.updateStatus("Đang dịch chữ OCR...", Color.parseColor("#38BDF8")));

                String translated = translationManager.translate(cleanRec, srcLang, tgtLang, prefs.isOfflineTranslatePreferred());

                if (epoch != liveOcrEpoch.get()) return;
                String cleanTrans = cleanSpokenText(translated);

                mainHandler.post(() -> {
                    floatingManager.addTranslation(cleanRec, cleanTrans);
                    floatingManager.updateStatus("Đã dịch xong chữ màn hình", Color.parseColor("#10B981"));
                });

                if (epoch == liveOcrEpoch.get() && prefs.isTtsEnabled() && ttsPlayer != null) {
                    Lang targetLang = Lang.findByEdgeCode(tgtLang);
                    ttsPlayer.speak(cleanTrans, targetLang.googleSttCode);
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
        audioEpoch.incrementAndGet();
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
        if (ttsPlayer != null) {
            ttsPlayer.stop();
        }
        if (screenCapturer != null) {
            screenCapturer.stop();
            screenCapturer = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(screenStateReceiver);
        } catch (Exception ignored) {}

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
