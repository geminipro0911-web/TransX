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
    }

    private final WindowManager wm;
    private final WindowManager.LayoutParams params;
    private final SubtitleActionListener listener;

    private TextView tvStatus;
    private LinearLayout itemsContainer;
    private ScrollView scrollView;
    private Button btnTts;
    private boolean ttsMuted = false;

    private float initialTouchX, initialTouchY;
    private int initialX, initialY;
    private int initialWidth, initialHeight;

    public FloatingSubtitleView(Context context, WindowManager wm, WindowManager.LayoutParams params, SubtitleActionListener listener) {
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
        tvStatus.setText("Sẵn sàng nghe...");
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

        TextView tvTitle = new TextView(context);
        tvTitle.setText("TransX Screen");
        tvTitle.setTextColor(Color.parseColor("#38BDF8")); // Cyan
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvTitle.setTypeface(tvTitle.getTypeface(), Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.0f);
        header.addView(tvTitle, titleLp);

        btnTts = createSmallButton("🔊");
        btnTts.setOnClickListener(v -> {
            ttsMuted = !ttsMuted;
            btnTts.setText(ttsMuted ? "🔇" : "🔊");
            if (listener != null) listener.onToggleTts();
        });
        header.addView(btnTts);

        Button btnClear = createSmallButton("🗑️");
        btnClear.setOnClickListener(v -> {
            itemsContainer.removeAllViews();
            tvStatus.setText("Sẵn sàng nghe...");
        });
        header.addView(btnClear);

        Button btnClose = createSmallButton("✖");
        btnClose.setOnClickListener(v -> {
            if (listener != null) listener.onClose();
        });
        header.addView(btnClose);

        addView(header);

        // Header touch to drag
        header.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX = params.x;
                    initialY = params.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    params.x = (int) (initialX + (event.getRawX() - initialTouchX));
                    params.y = (int) (initialY + (event.getRawY() - initialTouchY));
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

        // Bottom Status Line + Resize grip
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
                    int newW = Math.max(dp(200), (int) (initialWidth + (event.getRawX() - initialTouchX)));
                    int newH = Math.max(dp(120), (int) (initialHeight + (event.getRawY() - initialTouchY)));
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
            tvOrig.setTextColor(Color.parseColor("#CBD5E1"));
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

    private Button createSmallButton(String text) {
        Button b = new Button(getContext());
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setPadding(dp(6), 0, dp(6), 0);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(32));
        lp.leftMargin = dp(2);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
