package com.transx.screen.speech;

import android.util.Log;
import android.util.LruCache;

/**
 * Trình quản lý dịch thông minh hợp nhất:
 * 1. Bộ nhớ đệm LRU Cache (trả về trong 0ms nếu câu lặp lại)
 * 2. Bộ dịch On-Device Offline (Google ML Kit)
 * 3. Fallback sang Microsoft Edge Cloud API (Online)
 */
public class TranslationManager {
    private static final String TAG = "TranslationManager";

    // Cache 200 câu dịch gần nhất
    private final LruCache<String, String> cache = new LruCache<>(200);
    private final EdgeTranslator edgeTranslator = new EdgeTranslator();

    public String translate(String text, String srcLang, String tgtLang, boolean preferOffline) {
        if (text == null || text.trim().isEmpty()) return "";
        String clean = text.trim();
        String cacheKey = (srcLang != null ? srcLang : "") + "->" + (tgtLang != null ? tgtLang : "") + ":" + clean;

        // 1. Kiểm tra LRU Cache
        synchronized (cache) {
            String cached = cache.get(cacheKey);
            if (cached != null) {
                Log.d(TAG, "Cache hit (0ms): " + clean);
                return cached;
            }
        }

        String result = null;

        // 2. Thử dịch Offline bằng ML Kit nếu ưu tiên offline hoặc thử trước
        if (preferOffline) {
            result = OfflineTranslationEngine.translateOffline(clean, srcLang, tgtLang);
        }

        // 3. Fallback sang Edge API Online nếu offline chưa có kết quả
        if (result == null || result.trim().isEmpty()) {
            result = edgeTranslator.translateSingle(clean, srcLang, tgtLang);
        }

        // Nếu vẫn không dịch được thì thử lại offline lần cuối
        if ((result == null || result.trim().isEmpty()) && !preferOffline) {
            result = OfflineTranslationEngine.translateOffline(clean, srcLang, tgtLang);
        }

        if (result == null || result.trim().isEmpty()) {
            result = clean;
        }

        // Lưu vào cache
        synchronized (cache) {
            cache.put(cacheKey, result);
        }

        return result;
    }

    public void clearCache() {
        synchronized (cache) {
            cache.evictAll();
        }
    }
}
