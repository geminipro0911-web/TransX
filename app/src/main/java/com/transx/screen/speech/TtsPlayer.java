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
import java.util.concurrent.atomic.AtomicLong;

public class TtsPlayer {
    private static final String TAG = "TtsPlayer";

    public interface OnMuteListener {
        void onMuteRequested(boolean mute);
    }

    private final Context context;
    private final Prefs prefs;
    private final Handler mainHandler;
    private final OnMuteListener muteListener;

    private MediaPlayer mediaPlayer;
    private TextToSpeech systemTts;
    private boolean systemTtsReady = false;
    private volatile boolean isSpeaking = false;

    // Sequence ID and stop flag for instantaneous cancellation
    private final AtomicLong currentUtteranceId = new AtomicLong(0);
    private volatile boolean isStopped = false;

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

        isStopped = false;
        final long utteranceId = currentUtteranceId.incrementAndGet();
        stopPlaybackOnly();

        final String apiKey = prefs.getGoogleApiKey();
        final String voiceName = prefs.getTtsVoice();
        final float speed = prefs.getTtsSpeed();

        if (apiKey != null && !apiKey.isEmpty()) {
            new Thread(() -> {
                try {
                    if (isStopped || utteranceId != currentUtteranceId.get()) return;
                    byte[] mp3 = GoogleSpeechClient.synthesize(text, voiceName, langCode, speed, apiKey);
                    if (isStopped || utteranceId != currentUtteranceId.get()) return;

                    if (mp3 != null && mp3.length > 0) {
                        mainHandler.post(() -> {
                            if (isStopped || utteranceId != currentUtteranceId.get()) return;
                            playMp3(mp3, utteranceId);
                        });
                        return;
                    }
                } catch (Throwable e) {
                    Log.w(TAG, "Google Cloud TTS failed, falling back to system TTS: " + e.getMessage());
                }

                if (isStopped || utteranceId != currentUtteranceId.get()) return;
                mainHandler.post(() -> {
                    if (isStopped || utteranceId != currentUtteranceId.get()) return;
                    playSystemTts(text, speed, utteranceId);
                });
            }, "tts-synthesizer").start();
        } else {
            playSystemTts(text, speed, utteranceId);
        }
    }

    private synchronized void playMp3(byte[] mp3Data, long utteranceId) {
        if (isStopped || utteranceId != currentUtteranceId.get()) return;
        stopPlaybackOnly();

        try {
            File tempFile = new File(context.getCacheDir(), "tts_cache_" + utteranceId + ".mp3");
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(mp3Data);
            }

            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(tempFile.getAbsolutePath());
            mediaPlayer.setOnPreparedListener(mp -> {
                if (isStopped || utteranceId != currentUtteranceId.get()) {
                    try { mp.stop(); mp.release(); } catch (Throwable ignored) {}
                    if (mediaPlayer == mp) mediaPlayer = null;
                    setSpeaking(false);
                    return;
                }
                setSpeaking(true);
                mp.start();
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                setSpeaking(false);
                try { mp.release(); } catch (Throwable ignored) {}
                if (mediaPlayer == mp) mediaPlayer = null;
                try { tempFile.delete(); } catch (Throwable ignored) {}
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                setSpeaking(false);
                try { mp.release(); } catch (Throwable ignored) {}
                if (mediaPlayer == mp) mediaPlayer = null;
                try { tempFile.delete(); } catch (Throwable ignored) {}
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (Throwable e) {
            Log.e(TAG, "MediaPlayer error: " + e.getMessage());
            setSpeaking(false);
        }
    }

    private synchronized void playSystemTts(String text, float speed, long utteranceId) {
        if (isStopped || utteranceId != currentUtteranceId.get()) return;
        if (!systemTtsReady || systemTts == null) return;
        stopPlaybackOnly();

        try {
            systemTts.setSpeechRate(speed);
            setSpeaking(true);
            systemTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "transx_tts_" + utteranceId);
        } catch (Throwable e) {
            Log.e(TAG, "System TTS error: " + e.getMessage());
            setSpeaking(false);
        }
    }

    private void setSpeaking(boolean speaking) {
        this.isSpeaking = speaking;
        if (muteListener != null) {
            muteListener.onMuteRequested(speaking);
        }
    }

    private void stopPlaybackOnly() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.reset();
                mediaPlayer.release();
            } catch (Throwable ignored) {}
            mediaPlayer = null;
        }
        if (systemTts != null && systemTtsReady) {
            try {
                systemTts.stop();
            } catch (Throwable ignored) {}
        }
        setSpeaking(false);
    }

    public synchronized void stop() {
        isStopped = true;
        currentUtteranceId.incrementAndGet();
        mainHandler.removeCallbacksAndMessages(null);
        stopPlaybackOnly();
    }

    public void release() {
        stop();
        if (systemTts != null) {
            try { systemTts.shutdown(); } catch (Throwable ignored) {}
            systemTts = null;
        }
    }
}
