package com.transx.screen.speech;

import android.util.Base64;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class GoogleSpeechClient {
    private static final String TAG = "GoogleSpeechClient";
    private static final String STT_ENDPOINT = "https://speech.googleapis.com/v1/speech:recognize";
    private static final String TTS_ENDPOINT = "https://texttospeech.googleapis.com/v1/text:synthesize";

    public static String recognize(float[] pcmSamples, String langCode, String apiKey) throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new Exception("Chưa cấu hình Google Cloud API Key");
        }
        if (pcmSamples == null || pcmSamples.length == 0) return "";

        byte[] pcmBytes = new byte[pcmSamples.length * 2];
        for (int i = 0; i < pcmSamples.length; i++) {
            short s = (short) Math.max(-32768, Math.min(32767, Math.round(pcmSamples[i] * 32767.0f)));
            pcmBytes[i * 2] = (byte) (s & 0xff);
            pcmBytes[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
        }
        String base64Audio = Base64.encodeToString(pcmBytes, Base64.NO_WRAP);

        JSONObject config = new JSONObject();
        config.put("encoding", "LINEAR16");
        config.put("sampleRateHertz", 16000);
        config.put("languageCode", (langCode == null || langCode.isEmpty() || "auto".equals(langCode)) ? "en-US" : langCode);
        config.put("enableAutomaticPunctuation", true);

        JSONObject audio = new JSONObject();
        audio.put("content", base64Audio);

        JSONObject payload = new JSONObject();
        payload.put("config", config);
        payload.put("audio", audio);

        String url = STT_ENDPOINT + "?key=" + apiKey.trim();
        String resp = postJson(url, payload.toString());

        JSONObject json = new JSONObject(resp);
        if (json.has("error")) {
            throw new Exception(json.getJSONObject("error").getString("message"));
        }

        JSONArray results = json.optJSONArray("results");
        if (results == null || results.length() == 0) return "";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.length(); i++) {
            JSONArray alts = results.getJSONObject(i).optJSONArray("alternatives");
            if (alts != null && alts.length() > 0) {
                String transcript = alts.getJSONObject(0).optString("transcript", "");
                if (!transcript.isEmpty()) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(transcript.trim());
                }
            }
        }
        return sb.toString();
    }

    public static byte[] synthesize(String text, String voiceName, String langCode, float speed, String apiKey) throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new Exception("Chưa cấu hình Google Cloud API Key");
        }
        if (text == null || text.trim().isEmpty()) return null;

        JSONObject input = new JSONObject();
        input.put("text", text);

        JSONObject voice = new JSONObject();
        voice.put("languageCode", (langCode == null || langCode.isEmpty()) ? "vi-VN" : langCode);
        if (voiceName != null && !voiceName.isEmpty()) {
            voice.put("name", voiceName);
        }

        JSONObject audioConfig = new JSONObject();
        audioConfig.put("audioEncoding", "MP3");
        audioConfig.put("speakingRate", Math.max(0.25f, Math.min(4.0f, speed)));

        JSONObject payload = new JSONObject();
        payload.put("input", input);
        payload.put("voice", voice);
        payload.put("audioConfig", audioConfig);

        String url = TTS_ENDPOINT + "?key=" + apiKey.trim();
        String resp = postJson(url, payload.toString());

        JSONObject json = new JSONObject(resp);
        if (json.has("error")) {
            throw new Exception(json.getJSONObject("error").getString("message"));
        }

        String audioContent = json.optString("audioContent", "");
        if (audioContent.isEmpty()) return null;
        return Base64.decode(audioContent, Base64.DEFAULT);
    }

    private static String postJson(String urlStr, String jsonBody) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");

        byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        OutputStream os = c.getOutputStream();
        os.write(bytes);
        os.flush();
        os.close();

        int code = c.getResponseCode();
        InputStream in = (code == 200) ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();

        String res = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        if (code != 200) {
            throw new Exception("HTTP " + code + ": " + res);
        }
        return res;
    }
}
