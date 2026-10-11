package com.transx.screen.audio;

public class VadSegmenter {
    public interface Listener {
        void onSegment(float[] samples);
    }

    private static final int SR = 16000;
    private static final int FRAME = 320;              // 20 ms
    private static final float THRESH = 0.008f;        // RMS speech threshold
    private static final int SILENCE_END_MS = 650;
    private static final int MIN_SPEECH_MS = 500;
    private static final int MAX_SEGMENT_MS = 15000;
    private static final int PREROLL_MS = 400;

    private final Listener listener;
    private final float[] preroll = new float[SR * PREROLL_MS / 1000];
    private int prerollPos = 0;

    private final float[] seg;
    private int segLen = 0;
    private int silenceMs = 0;
    private int speechMs = 0;
    private boolean inSpeech = false;
    private float noiseFloor = 0.002f;

    public VadSegmenter(Listener listener) {
        this.listener = listener;
        this.seg = new float[SR * MAX_SEGMENT_MS / 1000];
    }

    public synchronized void feed(float[] pcm, int n) {
        for (int off = 0; off < n; off += FRAME) {
            int len = Math.min(FRAME, n - off);
            float sumSq = 0;
            for (int i = 0; i < len; i++) {
                float s = pcm[off + i];
                sumSq += s * s;
            }
            float rms = (float) Math.sqrt(sumSq / len);

            if (rms < THRESH) noiseFloor = 0.995f * noiseFloor + 0.005f * rms;
            float thr = Math.max(THRESH, noiseFloor * 3.5f);
            boolean speech = rms > thr;

            for (int i = 0; i < len; i++) {
                preroll[prerollPos] = pcm[off + i];
                prerollPos = (prerollPos + 1) % preroll.length;
            }

            if (speech) {
                if (!inSpeech) {
                    inSpeech = true;
                    segLen = 0;
                    speechMs = 0;
                    silenceMs = 0;
                    int k = preroll.length;
                    for (int i = 0; i < k && segLen < seg.length; i++) {
                        int idx = (prerollPos + i) % k;
                        seg[segLen++] = preroll[idx];
                    }
                }
                silenceMs = 0;
                speechMs += 20;
                append(pcm, off, len);
                if (speechMs >= MAX_SEGMENT_MS) emit();
            } else if (inSpeech) {
                silenceMs += 20;
                append(pcm, off, len);
                if (silenceMs >= SILENCE_END_MS && speechMs >= MIN_SPEECH_MS) {
                    emit();
                } else if (speechMs + silenceMs >= MAX_SEGMENT_MS) {
                    emit();
                }
            }
        }
    }

    private void append(float[] pcm, int off, int len) {
        int room = seg.length - segLen;
        int k = Math.min(room, len);
        System.arraycopy(pcm, off, seg, segLen, k);
        segLen += k;
    }

    private void emit() {
        int minSamples = SR * MIN_SPEECH_MS / 1000;
        if (segLen >= minSamples) {
            float[] out = new float[segLen];
            System.arraycopy(seg, 0, out, 0, segLen);
            listener.onSegment(out);
        }
        inSpeech = false;
        segLen = 0;
        speechMs = 0;
        silenceMs = 0;
    }
}
