package com.transx.screen;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FloatingSubtitleView extends LinearLayout {
    public interface SubtitleActionListener {
        void onClose();
        void onToggleTts();
        void onToggleListening();
        void onToggleLiveOcr();
        void onCaptureScreen();
        void onSelectRegion();
    }

    private final WindowManager wm;
    private final WindowManager.LayoutParams params;
    private final SubtitleActionListener listener;

    private TextView tvTitle;
    private TextView tvStatus;
    private LinearLayout itemsContainer;
    private ScrollView scrollView;

    private Button btnLiveOcr;
    private Button btnCapture;
    private Button btnCrop;
    private Button btnListen;
    private Button btnTts;

    private boolean ttsMuted = false;
    private boolean isListening = true;
    private boolean isLiveOcrRunning = true;

    private float initialTouchX, initialTouchY;
    private int initialX, initialY;
    private int initialWidth, initialHeight;

    public FloatingSubtitleView(Context context, WindowManager wm, WindowManager.LayoutParams params, String mode, SubtitleActionListener listener) {
        super(context);
        this.wm = wm;
        this.params = params;
        this.listener = listener;

        setOrientation(VERTICAL);
        int pad = dp(10);
        setPadding(pad, dp(6), pad, dp(6));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(16));
        bg.setColor(Color.parseColor("#E60F172A")); // Slate 900 semi-transparent
        bg.setStroke(dp(1), Color.parseColor("#334155"));
        setBackground(bg);

        // Pre-create member views
        tvStatus = new TextView(context);
        tvStatus.setTextColor(Color.parseColor("#94A3B8"));
        tvStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);

        itemsContainer = new LinearLayout(context);
        itemsContainer.setOrientation(VERTICAL);

        scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.addView(itemsContainer);

        // Header (Drag zone)
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(4));

        tvTitle = new TextView(context);
        tvTitle.setTextColor(Color.parseColor("#38BDF8")); // Cyan
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvTitle.setTypeface(tvTitle.getTypeface(), Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.0f);
        header.addView(tvTitle, titleLp);

        // Buttons
        btnLiveOcr = createTextButton("⏸ Live", v -> {
            isLiveOcrRunning = !isLiveOcrRunning;
            btnLiveOcr.setText(isLiveOcrRunning ? "⏸ Live" : "▶ Live");
            String msg = isLiveOcrRunning ? "Đã bật tự động quét màn hình liên tục" : "Đã tạm dừng quét màn hình";
            Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
            if (listener != null) listener.onToggleLiveOcr();
        });
        header.addView(btnLiveOcr);

        btnCrop = createSmallButton("🔲", v -> {
            Toast.makeText(getContext(), "Kéo chọn vùng muốn quét Live", Toast.LENGTH_SHORT).show();
            if (listener != null) listener.onSelectRegion();
        });
        header.addView(btnCrop);

        btnCapture = createSmallButton("📷", v -> {
            Toast.makeText(getContext(), "Quét màn hình 1 lần...", Toast.LENGTH_SHORT).show();
            if (listener != null) listener.onCaptureScreen();
        });
        header.addView(btnCapture);

        btnListen = createSmallButton("⏸", v -> {
            isListening = !isListening;
            btnListen.setText(isListening ? "⏸" : "▶");
            if (listener != null) listener.onToggleListening();
        });
        header.addView(btnListen);

        btnTts = createSmallButton("🔊", v -> {
            ttsMuted = !ttsMuted;
            btnTts.setText(ttsMuted ? "🔇" : "🔊");
            if (listener != null) listener.onToggleTts();
        });
        header.addView(btnTts);

        Button btnClear = createSmallButton("🗑️", v -> {
            itemsContainer.removeAllViews();
            tvStatus.setText("Đã xóa bản dịch.");
        });
        header.addView(btnClear);

        Button btnClose = createSmallButton("✖", v -> {
            if (listener != null) listener.onClose();
        });
        header.addView(btnClose);

        applyMode(mode);

        addView(header);

        // Header drag: TOP|START direct delta add
        header.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX = params.x;
                    initialY = params.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - initialTouchX;
                    float dy = event.getRawY() - initialTouchY;
                    params.x = (int) (initialX + dx);
                    params.y = (int) (initialY + dy);
                    try { wm.updateViewLayout(this, params); } catch (Exception ignored) {}
                    return true;
            }
            return false;
        });

        // Content ScrollView
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1.0f);
        scrollLp.topMargin = dp(4);
        scrollLp.bottomMargin = dp(4);
        addView(scrollView, scrollLp);

        // Footer status + Resize grip
        LinearLayout footer = new LinearLayout(context);
        footer.setOrientation(HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.0f);
        footer.addView(tvStatus, statusLp);

        TextView resizeGrip = new TextView(context);
        resizeGrip.setText("◢");
        resizeGrip.setTextColor(Color.parseColor("#64748B"));
        resizeGrip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        resizeGrip.setPadding(dp(6), dp(2), dp(2), dp(2));
        resizeGrip.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialWidth = params.width;
                    initialHeight = params.height;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int newW = Math.max(dp(220), (int) (initialWidth + (event.getRawX() - initialTouchX)));
                    int newH = Math.max(dp(140), (int) (initialHeight + (event.getRawY() - initialTouchY)));
                    params.width = newW;
                    params.height = newH;
                    try { wm.updateViewLayout(this, params); } catch (Exception ignored) {}
                    return true;
            }
            return false;
        });
        footer.addView(resizeGrip);

        addView(footer);
    }

    public void applyMode(String mode) {
        if ("mode_ocr".equals(mode)) {
            tvTitle.setText("Live OCR");
            tvStatus.setText("Đang tự động quét màn hình liên tục...");
            btnLiveOcr.setVisibility(VISIBLE);
            btnCrop.setVisibility(VISIBLE);
            btnCapture.setVisibility(VISIBLE);
            btnListen.setVisibility(GONE);
            btnTts.setVisibility(VISIBLE);
        } else if ("mode_stt".equals(mode)) {
            tvTitle.setText("Live STT");
            tvStatus.setText("Đang lắng nghe âm thanh trực tiếp...");
            btnLiveOcr.setVisibility(GONE);
            btnCrop.setVisibility(GONE);
            btnCapture.setVisibility(GONE);
            btnListen.setVisibility(VISIBLE);
            btnTts.setVisibility(VISIBLE);
        } else {
            tvTitle.setText("Live TransX");
            tvStatus.setText("Đang quét màn hình & nghe âm thanh...");
            btnLiveOcr.setVisibility(VISIBLE);
            btnCrop.setVisibility(VISIBLE);
            btnCapture.setVisibility(VISIBLE);
            btnListen.setVisibility(VISIBLE);
            btnTts.setVisibility(VISIBLE);
        }
    }

    public void setLiveOcrState(boolean isRunning) {
        this.isLiveOcrRunning = isRunning;
        post(() -> btnLiveOcr.setText(isRunning ? "⏸ Live" : "▶ Live"));
    }

    public void setStatus(String text) {
        post(() -> tvStatus.setText(text));
    }

    public void addTranslation(String orig, String trans) {
        post(() -> {
            LinearLayout entry = new LinearLayout(getContext());
            entry.setOrientation(VERTICAL);
            entry.setPadding(0, dp(4), 0, dp(6));

            String timeStr = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());

            TextView tvOrig = new TextView(getContext());
            tvOrig.setText("[" + timeStr + "] " + orig);
            tvOrig.setTextColor(Color.parseColor("#94A3B8"));
            tvOrig.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            entry.addView(tvOrig);

            TextView tvTrans = new TextView(getContext());
            tvTrans.setText(trans);
            tvTrans.setTextColor(Color.parseColor("#38BDF8")); // Bright Cyan
            tvTrans.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tvTrans.setTypeface(tvTrans.getTypeface(), Typeface.BOLD);
            tvTrans.setPadding(0, dp(2), 0, 0);
            entry.addView(tvTrans);

            entry.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("TransX", trans));
                    Toast.makeText(getContext(), "Đã copy bản dịch!", Toast.LENGTH_SHORT).show();
                }
            });

            itemsContainer.addView(entry);
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        });
    }

    private Button createTextButton(String text, OnClickListener listener) {
        Button b = new Button(getContext());
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        b.setPadding(dp(6), 0, dp(6), 0);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor("#1E293B"));
        gd.setCornerRadius(dp(8));
        gd.setStroke(dp(1), Color.parseColor("#38BDF8"));
        b.setBackground(gd);
        b.setTextColor(Color.parseColor("#38BDF8"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(32));
        lp.leftMargin = dp(2);
        b.setLayoutParams(lp);
        b.setOnClickListener(listener);
        return b;
    }

    private Button createSmallButton(String text, OnClickListener listener) {
        Button b = new Button(getContext());
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(32));
        lp.leftMargin = dp(2);
        b.setLayoutParams(lp);
        b.setOnClickListener(listener);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
