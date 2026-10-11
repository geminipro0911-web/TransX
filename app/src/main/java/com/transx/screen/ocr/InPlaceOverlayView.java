package com.transx.screen.ocr;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/**
 * Lớp phủ trong suốt toàn màn hình vẽ chữ tiếng Việt dịch đè trực tiếp lên vị trí chữ gốc
 * (Phục vụ đọc truyện tranh manga/comic, đọc báo và chơi game)
 */
public class InPlaceOverlayView extends View {
    private final List<OfflineOcrEngine.OcrBlock> blocks = new ArrayList<>();
    private final Paint bgPaint;
    private final Paint borderPaint;
    private final TextPaint textPaint;

    public InPlaceOverlayView(Context context) {
        super(context);

        bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.parseColor("#E60F172A")); // Slate 900 bán trong suốt
        bgPaint.setStyle(Paint.Style.FILL);

        borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setColor(Color.parseColor("#38BDF8")); // Cyan border
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1.2f));

        textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setShadowLayer(dp(2), 0, dp(1), Color.BLACK);
    }

    public synchronized void setBlocks(List<OfflineOcrEngine.OcrBlock> newBlocks) {
        blocks.clear();
        if (newBlocks != null) {
            blocks.addAll(newBlocks);
        }
        postInvalidate();
    }

    public synchronized void clearBlocks() {
        blocks.clear();
        postInvalidate();
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (blocks.isEmpty()) return;

        for (OfflineOcrEngine.OcrBlock b : blocks) {
            if (b.translatedText == null || b.translatedText.trim().isEmpty() || b.boundingBox == null) {
                continue;
            }

            Rect r = b.boundingBox;
            int pad = dp(4);
            RectF rectF = new RectF(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad);

            // 1. Vẽ nền che chữ gốc
            canvas.drawRoundRect(rectF, dp(6), dp(6), bgPaint);
            canvas.drawRoundRect(rectF, dp(6), dp(6), borderPaint);

            // 2. Tính toán cỡ chữ phù hợp với khung
            int targetWidth = Math.max(dp(30), (int) rectF.width() - dp(8));
            int targetHeight = Math.max(dp(16), (int) rectF.height() - dp(6));

            float textSize = Math.min(dp(16), Math.max(dp(10), targetHeight * 0.7f));
            textPaint.setTextSize(textSize);

            // 3. Định dạng văn bản đa dòng nếu cần
            StaticLayout sl;
            try {
                StaticLayout.Builder builder = StaticLayout.Builder.obtain(
                        b.translatedText, 0, b.translatedText.length(), textPaint, targetWidth
                ).setAlignment(Layout.Alignment.ALIGN_CENTER)
                 .setLineSpacing(0, 1.0f)
                 .setIncludePad(false);
                sl = builder.build();
            } catch (Throwable t) {
                sl = new StaticLayout(b.translatedText, textPaint, targetWidth, Layout.Alignment.ALIGN_CENTER, 1.0f, 0, false);
            }

            canvas.save();
            float topY = rectF.centerY() - (sl.getHeight() / 2.0f);
            canvas.translate(rectF.left + dp(4), Math.max(rectF.top, topY));
            sl.draw(canvas);
            canvas.restore();
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
