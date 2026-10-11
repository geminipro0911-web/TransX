package com.transx.screen.speech;

import android.util.Log;
import org.json.JSONArray;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class EdgeTranslator {
    private static final String TAG = "EdgeTranslator";
    private static final String ENDPOINT = "https://edge.microsoft.com/translate/translatetext";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0";

    public String translateSingle(String text, String src, String tgt) {
        if (text == null || text.trim().isEmpty()) return "";
        try {
            List<String> res = translateBatch(Collections.singletonList(text), src, tgt);
            return res.isEmpty() ? text : res.get(0);
        } catch (Exception e) {
            Log.e(TAG, "Translation error: " + e.getMessage());
            return text;
        }
    }

    public List<String> translateBatch(List<String> texts, String src, String tgt) throws Exception {
        if (texts == null || texts.isEmpty()) return Collections.emptyList();
        String url = ENDPOINT + "?from=" + URLEncoder.encode(src == null ? "" : src, "UTF-8")
                + "&to=" + URLEncoder.encode(tgt, "UTF-8")
                + "&isEnterpriseClient=true";

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Accept", "*/*");

        JSONArray body = new JSONArray();
        for (String t : texts) body.put(t);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);

        OutputStream os = c.getOutputStream();
        os.write(bytes);
        os.flush();
        os.close();

        int code = c.getResponseCode();
        InputStream in = (code == 200) ? c.getInputStream() : c.getErrorStream();
        String resp = readAll(in);

        if (code != 200) {
            throw new Exception("Edge API error " + code + ": " + resp);
        }

        JSONArray arr = new JSONArray(resp);
        List<String> out = new ArrayList<>();
        for (int k = 0; k < arr.length(); k++) {
            out.add(arr.getJSONObject(k).getJSONArray("translations").getJSONObject(0).getString("text"));
        }
        return out;
    }

    private String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
