package com.transx.screen.ocr;

import android.graphics.Bitmap;
import android.util.Log;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

/**
 * Model OCR Offline hoàn toàn trên thiết bị (On-Device ML Kit).
 * Không cần API key, không gửi ảnh lên Cloud Vision, không gặp lỗi 403.
 */
public class OfflineOcrEngine {
    private static final String TAG = "OfflineOcrEngine";

    private static TextRecognizer latinRecognizer;
    private static TextRecognizer chineseRecognizer;
    private static TextRecognizer japaneseRecognizer;
    private static TextRecognizer koreanRecognizer;

    private static synchronized TextRecognizer getRecognizer(String srcLang) {
        if (srcLang == null) srcLang = "";
        String lang = srcLang.toLowerCase().trim();

        if (lang.contains("zh") || lang.contains("chi")) {
            if (chineseRecognizer == null) {
                chineseRecognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            }
            return chineseRecognizer;
        } else if (lang.contains("ja") || lang.contains("jp")) {
            if (japaneseRecognizer == null) {
                japaneseRecognizer = TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
            }
            return japaneseRecognizer;
        } else if (lang.contains("ko") || lang.contains("kr")) {
            if (koreanRecognizer == null) {
                koreanRecognizer = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
            }
            return koreanRecognizer;
        } else {
            // Mặc định Latin: nhận dạng tiếng Anh, tiếng Việt, tiếng Pháp, Tây Ban Nha, Đức...
            if (latinRecognizer == null) {
                latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            }
            return latinRecognizer;
        }
    }

    public static String recognizeText(Bitmap bitmap, String srcLang) throws Exception {
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            return "";
        }

        InputImage image = InputImage.fromBitmap(bitmap, 0);
        TextRecognizer recognizer = getRecognizer(srcLang);

        Text result = Tasks.await(recognizer.process(image));
        String text = (result != null) ? result.getText() : "";

        // Nếu ngôn ngữ để tự động mà chữ Latin không ra kết quả, thử quét tiếng Trung hoặc Nhật
        if ((text == null || text.trim().isEmpty()) && (srcLang == null || srcLang.isEmpty() || "auto".equalsIgnoreCase(srcLang))) {
            try {
                if (chineseRecognizer == null) {
                    chineseRecognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
                }
                Text zhResult = Tasks.await(chineseRecognizer.process(image));
                if (zhResult != null && !zhResult.getText().trim().isEmpty()) {
                    return zhResult.getText().trim();
                }
            } catch (Exception ignored) {}

            try {
                if (japaneseRecognizer == null) {
                    japaneseRecognizer = TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
                }
                Text jaResult = Tasks.await(japaneseRecognizer.process(image));
                if (jaResult != null && !jaResult.getText().trim().isEmpty()) {
                    return jaResult.getText().trim();
                }
            } catch (Exception ignored) {}
        }

        return (text != null) ? text.trim() : "";
    }

    public static synchronized void release() {
        if (latinRecognizer != null) {
            try { latinRecognizer.close(); } catch (Exception ignored) {}
            latinRecognizer = null;
        }
        if (chineseRecognizer != null) {
            try { chineseRecognizer.close(); } catch (Exception ignored) {}
            chineseRecognizer = null;
        }
        if (japaneseRecognizer != null) {
            try { japaneseRecognizer.close(); } catch (Exception ignored) {}
            japaneseRecognizer = null;
        }
        if (koreanRecognizer != null) {
            try { koreanRecognizer.close(); } catch (Exception ignored) {}
            koreanRecognizer = null;
        }
    }
}
