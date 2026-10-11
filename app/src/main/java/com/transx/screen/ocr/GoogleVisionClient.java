package com.transx.screen.ocr;

import android.graphics.Bitmap;
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

public class GoogleVisionClient {
    private static final String TAG = "GoogleVisionClient";
    private static final String VISION_ENDPOINT = "https://vision.googleapis.com/v1/images:annotate";

    public static String recognizeText(Bitmap bitmap, String apiKey) throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new Exception("Chưa cấu hình Google Cloud API Key");
        }
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            return "";
        }

        // Tối ưu kích thước ảnh (giới hạn cạnh tối đa 1600px để tải nhanh & tiết kiệm băng thông)
        Bitmap processed = bitmap;
        int maxDim = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (maxDim > 1600) {
            float scale = 1600.0f / maxDim;
            int targetW = Math.round(bitmap.getWidth() * scale);
            int targetH = Math.round(bitmap.getHeight() * scale);
            processed = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true);
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        processed.compress(Bitmap.CompressFormat.JPEG, 85, bos);
        if (processed != bitmap) {
            processed.recycle();
        }

        byte[] jpegBytes = bos.toByteArray();
        String base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP);

        JSONObject imageObj = new JSONObject();
        imageObj.put("content", base64Image);

        JSONObject feature = new JSONObject();
        feature.put("type", "TEXT_DETECTION");

        JSONArray features = new JSONArray();
        features.put(feature);

        JSONObject requestObj = new JSONObject();
        requestObj.put("image", imageObj);
        requestObj.put("features", features);

        JSONArray requests = new JSONArray();
        requests.put(requestObj);

        JSONObject payload = new JSONObject();
        payload.put("requests", requests);

        String url = VISION_ENDPOINT + "?key=" + apiKey.trim();
        String responseStr = postJson(url, payload.toString());

        JSONObject json = new JSONObject(responseStr);
        JSONArray responses = json.optJSONArray("responses");
        if (responses == null || responses.length() == 0) return "";

        JSONObject firstResp = responses.getJSONObject(0);
        if (firstResp.has("error")) {
            throw new Exception(firstResp.getJSONObject("error").getString("message"));
        }

        JSONArray textAnnotations = firstResp.optJSONArray("textAnnotations");
        if (textAnnotations == null || textAnnotations.length() == 0) return "";

        // Annotation đầu tiên chứa toàn bộ văn bản được nhận diện
        return textAnnotations.getJSONObject(0).optString("description", "").trim();
    }

    private static String postJson(String urlStr, String jsonBody) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(12000);
        c.setReadTimeout(18000);
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
