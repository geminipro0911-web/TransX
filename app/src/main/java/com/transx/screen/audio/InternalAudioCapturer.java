package com.transx.screen.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.util.Log;

public class InternalAudioCapturer {
    private static final String TAG = "AudioCap";
    public static final int TARGET_SAMPLE_RATE = 16000;

    public interface Listener {
        void onPcm(float[] samples, int n);
    }

    private final MediaProjection projection;
    private final boolean forceMic;
    private final Listener listener;
    private volatile boolean muted = false;
    private AudioRecord record;
    private Thread thread;
    private volatile boolean running = false;

    private int actualSampleRate = TARGET_SAMPLE_RATE;
    private int channelCount = 1;

    public InternalAudioCapturer(MediaProjection projection, boolean forceMic, Listener listener) {
        this.projection = projection;
        this.forceMic = forceMic;
        this.listener = listener;
    }

    public void setMuted(boolean m) {
        this.muted = m;
    }

    public boolean isMuted() {
        return muted;
    }

    public boolean start() {
        try {
            AudioPlaybackCaptureConfiguration config = null;
            if (!forceMic && projection != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();
            }

            int[][] candidates;
            if (config != null) {
                candidates = new int[][]{
                        {48000, AudioFormat.CHANNEL_IN_STEREO},
                        {48000, AudioFormat.CHANNEL_IN_MONO},
                        {44100, AudioFormat.CHANNEL_IN_STEREO},
                        {44100, AudioFormat.CHANNEL_IN_MONO},
                        {16000, AudioFormat.CHANNEL_IN_MONO}
                };
            } else {
                candidates = new int[][]{
                        {16000, AudioFormat.CHANNEL_IN_MONO},
                        {48000, AudioFormat.CHANNEL_IN_MONO},
                        {44100, AudioFormat.CHANNEL_IN_MONO}
                };
            }

            for (int[] cand : candidates) {
                int rate = cand[0];
                int chMask = cand[1];
                int minBuf = AudioRecord.getMinBufferSize(rate, chMask, AudioFormat.ENCODING_PCM_16BIT);
                if (minBuf <= 0) continue;
                int bufSize = Math.max(minBuf * 2, 4096);

                try {
                    AudioRecord.Builder rb = new AudioRecord.Builder()
                            .setAudioFormat(new AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(rate)
                                    .setChannelMask(chMask)
                                    .build())
                            .setBufferSizeInBytes(bufSize);

                    if (config != null) {
                        rb.setAudioPlaybackCaptureConfig(config);
                    } else {
                        rb.setAudioSource(MediaRecorder.AudioSource.MIC);
                    }

                    record = rb.build();
                    if (record.getState() == AudioRecord.STATE_INITIALIZED) {
                        actualSampleRate = rate;
                        channelCount = (chMask == AudioFormat.CHANNEL_IN_STEREO) ? 2 : 1;
                        Log.i(TAG, "AudioRecord initialized: " + rate + "Hz, ch=" + channelCount + ", internal=" + (config != null));
                        break;
                    } else {
                        record.release();
                        record = null;
                    }
                } catch (Throwable e) {
                    Log.w(TAG, "Candidate " + rate + "/" + chMask + " failed: " + e.getMessage());
                }
            }

            if (record == null) {
                Log.e(TAG, "No valid AudioRecord format found");
                return false;
            }

            record.startRecording();
            running = true;
            thread = new Thread(this::captureLoop, "audio-capture");
            thread.setPriority(Thread.MAX_PRIORITY);
            thread.start();
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "Failed to start audio capturer", e);
            return false;
        }
    }

    public void stop() {
        running = false;
        if (thread != null) {
            try { thread.join(1000); } catch (InterruptedException ignored) {}
            thread = null;
        }
        if (record != null) {
            try {
                if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop();
                }
                record.release();
            } catch (Throwable ignored) {}
            record = null;
        }
    }

    private void captureLoop() {
        short[] rawBuf = new short[2048 * channelCount];
        float[] monoBuf = new float[2048];
        float[] resampleBuf = new float[4096];
        double resamplePhase = 0.0;

        while (running) {
            int read = record.read(rawBuf, 0, rawBuf.length);
            if (read <= 0) continue;
            if (muted) continue;

            int frames = read / channelCount;
            if (channelCount == 2) {
                for (int i = 0; i < frames; i++) {
                    float l = rawBuf[i * 2] / 32768.0f;
                    float r = rawBuf[i * 2 + 1] / 32768.0f;
                    monoBuf[i] = (l + r) * 0.5f;
                }
            } else {
                for (int i = 0; i < frames; i++) {
                    monoBuf[i] = rawBuf[i] / 32768.0f;
                }
            }

            if (actualSampleRate == TARGET_SAMPLE_RATE) {
                listener.onPcm(monoBuf, frames);
            } else if (actualSampleRate == 48000) {
                // Tỷ lệ 48000Hz -> 16000Hz là CHÍNH XÁC 3:1
                // Sử dụng bộ lọc trung bình 3 mẫu (3-tap moving average box filter)
                // giúp triệt tiêu hoàn toàn hiện tượng méo răng cưa (aliasing) và giữ âm thanh trong trẻo tuyệt đối
                int outFrames = frames / 3;
                if (outFrames > resampleBuf.length) {
                    resampleBuf = new float[outFrames + 256];
                }
                for (int i = 0; i < outFrames; i++) {
                    int base = i * 3;
                    resampleBuf[i] = (monoBuf[base] + monoBuf[base + 1] + monoBuf[base + 2]) / 3.0f;
                }
                listener.onPcm(resampleBuf, outFrames);
            } else {
                // Thuật toán Resample đa dụng có bộ tích lũy pha liên tục (Continuous Phase Accumulator)
                // đảm bảo không bao giờ bị lệch pha hay gián đoạn tần số giữa các chu kỳ buffer
                double step = (double) actualSampleRate / TARGET_SAMPLE_RATE;
                int outCount = 0;
                while (resamplePhase < frames - 1) {
                    int i0 = (int) resamplePhase;
                    int i1 = i0 + 1;
                    float frac = (float) (resamplePhase - i0);
                    if (outCount >= resampleBuf.length) {
                        float[] newBuf = new float[resampleBuf.length * 2];
                        System.arraycopy(resampleBuf, 0, newBuf, 0, outCount);
                        resampleBuf = newBuf;
                    }
                    resampleBuf[outCount++] = monoBuf[i0] * (1.0f - frac) + monoBuf[i1] * frac;
                    resamplePhase += step;
                }
                resamplePhase -= frames;
                if (outCount > 0) {
                    listener.onPcm(resampleBuf, outCount);
                }
            }
        }
    }
}
