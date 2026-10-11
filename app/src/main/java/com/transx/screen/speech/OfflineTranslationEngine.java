package com.transx.screen.speech;

import android.util.Log;
import android.util.LruCache;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Bộ dịch Offline hoàn toàn trên thiết bị (On-Device ML Kit Translate)
 * Hỗ trợ dịch ngoại tuyến không cần internet khi mô hình đã được tải.
 */
public class OfflineTranslationEngine {
    private static final String TAG = "OfflineTranslate";
    private static final Map<String, Translator> translators = new HashMap<>();

    public interface DownloadCallback {
        void onSuccess();
        void onFailure(Exception e);
    }

    private static String normalizeLang(String code) {
        if (code == null) return TranslateLanguage.ENGLISH;
        String c = code.toLowerCase().trim();
        if (c.startsWith("zh")) return TranslateLanguage.CHINESE;
        if (c.startsWith("ja")) return TranslateLanguage.JAPANESE;
        if (c.startsWith("ko")) return TranslateLanguage.KOREAN;
        if (c.startsWith("vi")) return TranslateLanguage.VIETNAMESE;
        if (c.startsWith("fr")) return TranslateLanguage.FRENCH;
        if (c.startsWith("de")) return TranslateLanguage.GERMAN;
        if (c.startsWith("es")) return TranslateLanguage.SPANISH;
        if (c.startsWith("ru")) return TranslateLanguage.RUSSIAN;
        if (c.startsWith("en")) return TranslateLanguage.ENGLISH;
        String fromTag = TranslateLanguage.fromLanguageTag(c);
        return fromTag != null ? fromTag : TranslateLanguage.ENGLISH;
    }

    private static synchronized Translator getTranslator(String srcCode, String tgtCode) {
        String src = normalizeLang(srcCode);
        String tgt = normalizeLang(tgtCode);
        String key = src + "_" + tgt;

        Translator t = translators.get(key);
        if (t == null) {
            TranslatorOptions options = new TranslatorOptions.Builder()
                    .setSourceLanguage(src)
                    .setTargetLanguage(tgt)
                    .build();
            t = Translation.getClient(options);
            translators.put(key, t);
        }
        return t;
    }

    /**
     * Dịch thử Offline. Nếu mô hình chưa tải hoặc lỗi, trả về null để fallback sang Online Edge API.
     */
    public static String translateOffline(String text, String srcLang, String tgtLang) {
        if (text == null || text.trim().isEmpty()) return "";
        try {
            Translator translator = getTranslator(srcLang, tgtLang);
            // Thử dịch với timeout 2.5s (nếu model chưa có trong máy Tasks.await sẽ ném Exception)
            return Tasks.await(translator.translate(text), 2500, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            // Model chưa tải hoặc dịch offline chưa sẵn sàng
            Log.d(TAG, "Offline translation unavailable, falling back: " + e.getMessage());
            return null;
        }
    }

    /**
     * Tải trước mô hình dịch Offline khi có WiFi hoặc mạng
     */
    public static void ensureModelDownloaded(String srcLang, String tgtLang, DownloadCallback callback) {
        try {
            Translator translator = getTranslator(srcLang, tgtLang);
            DownloadConditions conditions = new DownloadConditions.Builder().build();
            translator.downloadModelIfNeeded(conditions)
                    .addOnSuccessListener(unused -> {
                        Log.i(TAG, "Offline model ready: " + srcLang + " -> " + tgtLang);
                        if (callback != null) callback.onSuccess();
                    })
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "Failed downloading offline model: " + e.getMessage());
                        if (callback != null) callback.onFailure(e);
                    });
        } catch (Throwable e) {
            if (callback != null) callback.onFailure(new Exception(e));
        }
    }

    public static synchronized void release() {
        for (Translator t : translators.values()) {
            try { t.close(); } catch (Exception ignored) {}
        }
        translators.clear();
    }
}
