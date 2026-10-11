package com.transx.screen.speech;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import com.transx.screen.util.Prefs;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

public class TtsPlayer {
    private static final String TAG = "TtsPlayer";

    public interface OnMuteListener {
        void onMuteRequested(boolean mute);
    }

    private final Context context;
    private final Prefs prefs;
    private final Handler mainHandler;
    private OnMuteListener muteListener;

    private MediaPlayer mediaPlayer;
    private TextToSpeech systemTts;
    private boolean systemTtsReady = false;
    private volatile boolean isSpeaking = false;

    public TtsPlayer(Context context, OnMuteListener muteListener) {
        this.context = context.getApplicationContext();
        this.prefs = new Prefs(context);
        this.muteListener = muteListener;
        this.mainHandler = new Handler(Looper.getMainLooper());

        initSystemTts();
    }

    private void initSystemTts() {
        systemTts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                systemTts.setLanguage(new Locale("vi", "VN"));
                systemTtsReady = true;
                systemTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) {
                        setSpeaking(true);
                    }
                    @Override public void onDone(String utteranceId) {
                        setSpeaking(false);
                    }
                    @Override public void onError(String utteranceId) {
                        setSpeaking(false);
                    }
                });
            }
        });
    }

    public synchronized void speak(String text, String langCode) {
        if (!prefs.isTtsEnabled() || text == null || text.trim().isEmpty()) return;

        stop();
        final String apiKey = prefs.getGoogleApiKey();
        final String voiceName = prefs.getTtsVoice();
        final float speed = prefs.getTtsSpeed();

        if (apiKey != null && !apiKey.isEmpty()) {
            new Thread(() -> {
                try {
                    byte[] mp3 = GoogleSpeechClient.synthesize(text, voiceName, langCode, speed, apiKey);
                    if (mp3 != null && mp3.length > 0) {
                        mainHandler.post(() -> playMp3(mp3));
                        return;
                    }
                } catch (Throwable e) {
                    Log.w(TAG, "Google Cloud TTS failed, falling back to system TTS: " + e.getMessage());
                }
                mainHandler.post(() -> playSystemTts(text, speed));
            }, "tts-synthesizer").start();
        } else {
            playSystemTts(text, speed);
        }
    }

    private void playMp3(byte[] mp3Data) {
        try {
            File tempFile = new File(context.getCacheDir(), "tts_cache.mp3");
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(mp3Data);
            }

            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(tempFile.getAbsolutePath());
            mediaPlayer.setOnPreparedListener(mp -> {
                setSpeaking(true);
                mp.start();
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                setSpeaking(false);
                mp.release();
                mediaPlayer = null;
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                setSpeaking(false);
                mp.release();
                mediaPlayer = null;
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (Throwable e) {
            Log.e(TAG, "MediaPlayer error: " + e.getMessage());
            setSpeaking(false);
        }
    }

    private void playSystemTts(String text, float speed) {
        if (!systemTtsReady || systemTts == null) return;
        systemTts.setSpeechRate(speed);
        setSpeaking(true);
        systemTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "transx_tts_" + System.currentTimeMillis());
    }

    private void setSpeaking(boolean speaking) {
        this.isSpeaking = speaking;
        if (muteListener != null) {
            muteListener.onMuteRequested(speaking);
        }
    }

    public synchronized void stop() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        if (systemTts != null && systemTtsReady) {
            systemTts.stop();
        }
        setSpeaking(false);
    }

    public void release() {
        stop();
        if (systemTts != null) {
            try { systemTts.shutdown(); } catch (Exception ignored) {}
            systemTts = null;
        }
    }
}
