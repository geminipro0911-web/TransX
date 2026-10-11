package com.transx.screen.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SubtitleExporter {

    public static class SubtitleItem {
        public final long timestamp;
        public final String original;
        public final String translated;

        public SubtitleItem(long timestamp, String original, String translated) {
            this.timestamp = timestamp;
            this.original = original;
            this.translated = translated;
        }
    }

    private static String formatSrtTime(long ms) {
        long hours = ms / 3600000;
        long rem = ms % 3600000;
        long mins = rem / 60000;
        rem = rem % 60000;
        long secs = rem / 1000;
        long millis = rem % 1000;
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, mins, secs, millis);
    }

    public static void exportAsSrt(Context context, List<SubtitleItem> items) {
        if (items == null || items.isEmpty()) {
            Toast.makeText(context, "Chưa có bản dịch nào để xuất!", Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder sb = new StringBuilder();
        long baseTime = items.get(0).timestamp;

        for (int i = 0; i < items.size(); i++) {
            SubtitleItem item = items.get(i);
            long startMs = Math.max(0, item.timestamp - baseTime);
            long endMs = startMs + 3500; // Ước lượng 3.5s mỗi câu phụ đề

            sb.append(i + 1).append("\n");
            sb.append(formatSrtTime(startMs)).append(" --> ").append(formatSrtTime(endMs)).append("\n");
            sb.append(item.translated).append("\n");
            if (item.original != null && !item.original.isEmpty()) {
                sb.append("(").append(item.original).append(")\n");
            }
            sb.append("\n");
        }

        String fileName = "TransX_Subtitle_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".srt";
        saveAndShare(context, fileName, sb.toString(), "text/plain");
    }

    public static void exportAsTxt(Context context, List<SubtitleItem> items) {
        if (items == null || items.isEmpty()) {
            Toast.makeText(context, "Chưa có bản dịch nào để xuất!", Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== LỊCH SỬ DỊCH TRANSX SCREEN ===\n");
        sb.append("Ngày tạo: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date())).append("\n\n");

        for (SubtitleItem item : items) {
            String timeStr = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(item.timestamp));
            sb.append("[").append(timeStr).append("]\n");
            sb.append("Gốc: ").append(item.original).append("\n");
            sb.append("Dịch: ").append(item.translated).append("\n");
            sb.append("----------------------------------------\n");
        }

        String fileName = "TransX_History_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt";
        saveAndShare(context, fileName, sb.toString(), "text/plain");
    }

    private static void saveAndShare(Context context, String fileName, String content, String mimeType) {
        try {
            File dir = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "TransX");
            if (!dir.exists()) dir.mkdirs();

            File file = new File(dir, fileName);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            }

            Toast.makeText(context, "Đã lưu: " + file.getName(), Toast.LENGTH_SHORT).show();

            Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType(mimeType);
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, "Bản dịch TransX: " + fileName);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);

            Intent chooser = Intent.createChooser(share, "Chia sẻ hoặc lưu file " + fileName);
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);

        } catch (Throwable e) {
            Toast.makeText(context, "Lỗi khi lưu file: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
