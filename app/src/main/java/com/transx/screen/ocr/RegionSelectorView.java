package com.transx.screen.ocr;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public class RegionSelectorView extends FrameLayout {
    public interface Listener {
        void onRegionSelected(Rect region);
        void onCancel();
    }

    private final DrawView drawView;
    private final LinearLayout controlPanel;
    private final Rect selectedRect = new Rect();
    private boolean hasSelection = false;
    private Listener listener;

    public RegionSelectorView(Context context) {
        super(context);

        drawView = new DrawView(context);
        addView(drawView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Bottom Controls
        controlPanel = new LinearLayout(context);
        controlPanel.setOrientation(LinearLayout.HORIZONTAL);
        controlPanel.setGravity(Gravity.CENTER);
        controlPanel.setVisibility(GONE);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(16));
        bg.setColor(Color.parseColor("#E60F172A"));
        bg.setStroke(dp(1), Color.parseColor("#38BDF8"));
        controlPanel.setBackground(bg);
        controlPanel.setPadding(dp(16), dp(10), dp(16), dp(10));

        Button btnCancel = new Button(context);
        btnCancel.setText("✕ Hủy");
        btnCancel.setTextColor(Color.parseColor("#94A3B8"));
        btnCancel.setBackgroundColor(Color.TRANSPARENT);
        btnCancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btnCancel.setOnClickListener(v -> {
            hasSelection = false;
            controlPanel.setVisibility(GONE);
            drawView.invalidate();
            if (listener != null) listener.onCancel();
        });
        controlPanel.addView(btnCancel);

        Button btnTranslate = new Button(context);
        btnTranslate.setText("✓ Dịch Vùng Này");
        btnTranslate.setTextColor(Color.WHITE);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(Color.parseColor("#2563EB"));
        btnBg.setCornerRadius(dp(10));
        btnTranslate.setBackground(btnBg);
        btnTranslate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btnTranslate.setTypeface(btnTranslate.getTypeface(), Typeface.BOLD);
        btnTranslate.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams transLp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(40));
        transLp.leftMargin = dp(12);
        btnTranslate.setLayoutParams(transLp);
        btnTranslate.setOnClickListener(v -> {
            if (listener != null && hasSelection) {
                listener.onRegionSelected(new Rect(selectedRect));
            }
        });
        controlPanel.addView(btnTranslate);

        LayoutParams cpLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        cpLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        cpLp.bottomMargin = dp(60);
        addView(controlPanel, cpLp);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private class DrawView extends View {
        private final Paint dimPaint = new Paint();
        private final Paint borderPaint = new Paint();
        private final Paint clearPaint = new Paint();
        private final Paint textPaint = new Paint();

        private float startX, startY;
        private float curX, curY;
        private boolean isDragging = false;

        public DrawView(Context context) {
            super(context);
            setLayerType(LAYER_TYPE_SOFTWARE, null);

            dimPaint.setColor(Color.parseColor("#80000000"));

            borderPaint.setColor(Color.parseColor("#38BDF8"));
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(dp(2));
            borderPaint.setPathEffect(new DashPathEffect(new float[]{dp(6), dp(3)}, 0));

            clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16, getResources().getDisplayMetrics()));
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            textPaint.setShadowLayer(dp(3), 0, 0, Color.BLACK);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = event.getX();
                    startY = event.getY();
                    curX = startX;
                    curY = startY;
                    isDragging = true;
                    hasSelection = false;
                    controlPanel.setVisibility(GONE);
                    invalidate();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    curX = event.getX();
                    curY = event.getY();
                    invalidate();
                    return true;

                case MotionEvent.ACTION_UP:
                    isDragging = false;
                    curX = event.getX();
                    curY = event.getY();

                    int l = (int) Math.min(startX, curX);
                    int t = (int) Math.min(startY, curY);
                    int r = (int) Math.max(startX, curX);
                    int b = (int) Math.max(startY, curY);

                    if ((r - l) > dp(40) && (b - t) > dp(30)) {
                        selectedRect.set(l, t, r, b);
                        hasSelection = true;
                        controlPanel.setVisibility(VISIBLE);
                    }
                    invalidate();
                    return true;
            }
            return super.onTouchEvent(event);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();

            // Vẽ toàn bộ màn hình mờ
            canvas.drawRect(0, 0, w, h, dimPaint);

            if (!hasSelection && !isDragging) {
                canvas.drawText("Kéo để chọn vùng chữ cần dịch trên màn hình", w / 2.0f, h / 2.0f, textPaint);
                return;
            }

            int l = hasSelection ? selectedRect.left : (int) Math.min(startX, curX);
            int t = hasSelection ? selectedRect.top : (int) Math.min(startY, curY);
            int r = hasSelection ? selectedRect.right : (int) Math.max(startX, curX);
            int b = hasSelection ? selectedRect.bottom : (int) Math.max(startY, curY);

            // Khoét rỗng vùng chọn để nhìn thấy màn hình thật bên dưới
            canvas.drawRect(l, t, r, b, clearPaint);
            // Viền sáng quanh vùng chọn
            canvas.drawRect(l, t, r, b, borderPaint);
        }
    }
}
