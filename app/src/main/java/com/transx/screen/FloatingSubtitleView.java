package com.transx.screen;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.transx.screen.util.Prefs;
import com.transx.screen.util.SubtitleExporter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class FloatingSubtitleView extends LinearLayout {
    public interface SubtitleActionListener {
        void onClose();
        void onToggleTts();
        void onToggleListening();
        void onToggleLiveOcr();
        void onCaptureScreen();
        void onSelectRegion();
        void onClearHistory();
        void onToggleInPlace(boolean enabled);
    }

    private final WindowManager wm;
    private final WindowManager.LayoutParams params;
    private final SubtitleActionListener listener;
    private final Prefs prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final List<SubtitleExporter.SubtitleItem> historyItems = new ArrayList<>();

    private LinearLayout header;
    private LinearLayout footer;
    private TextView tvTitle;
    private TextView tvStatus;
    private LinearLayout itemsContainer;
    private ScrollView scrollView;

    // Streaming card views (Real-time live streaming)
    private LinearLayout streamingCard;
    private TextView tvStreamBadge;
    private TextView tvStreamOrig;
    private TextView tvStreamTrans;

    // Cinema Subtitle View
    private TextView tvCinemaSubtitle;
    private Button btnExitCinema;

    private Button btnLiveOcr;
    private Button btnCapture;
    private Button btnCrop;
    private Button btnCinema;
    private Button btnBilingual;
    private Button btnInPlace;
    private Button btnExport;
    private Button btnListen;
    private Button btnTts;

    private boolean ttsMuted = false;
    private boolean isListening = true;
    private boolean isLiveOcrRunning = true;
    private boolean isCinemaMode = false;

    private GradientDrawable normalBg;

    private float initialTouchX, initialTouchY;
    private int initialX, initialY;
    private int initialWidth, initialHeight;

    public FloatingSubtitleView(Context context, WindowManager wm, WindowManager.LayoutParams params, String mode, SubtitleActionListener listener) {
        super(context);
        this.wm = wm;
        this.params = params;
        this.listener = listener;
        this.prefs = new Prefs(context);

        setOrientation(VERTICAL);
        int pad = dp(10);
        setPadding(pad, dp(6), pad, dp(6));

        normalBg = new GradientDrawable();
        normalBg.setShape(GradientDrawable.RECTANGLE);
        normalBg.setCornerRadius(dp(16));
        normalBg.setColor(Color.parseColor("#E60F172A")); // Slate 900 semi-transparent
        normalBg.setStroke(dp(1), Color.parseColor("#334155"));
        setBackground(normalBg);

        // Pre-create member views
        tvStatus = new TextView(context);
        tvStatus.setTextColor(Color.parseColor("#94A3B8"));
        tvStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);

        itemsContainer = new LinearLayout(context);
        itemsContainer.setOrientation(VERTICAL);

        // Streaming Card (Active live streaming card at bottom of list)
        streamingCard = new LinearLayout(context);
        streamingCard.setOrientation(VERTICAL);
        int scPad = dp(10);
        streamingCard.setPadding(scPad, scPad, scPad, scPad);
        GradientDrawable scBg = new GradientDrawable();
        scBg.setShape(GradientDrawable.RECTANGLE);
        scBg.setColor(Color.parseColor("#1E293B"));
        scBg.setCornerRadius(dp(12));
        scBg.setStroke(dp(1), Color.parseColor("#38BDF8"));
        streamingCard.setBackground(scBg);
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        scLp.bottomMargin = dp(8);
        streamingCard.setLayoutParams(scLp);

        tvStreamBadge = new TextView(context);
        tvStreamBadge.setText("⚡ LIVE STREAM");
        tvStreamBadge.setTextColor(Color.parseColor("#38BDF8"));
        tvStreamBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvStreamBadge.setTypeface(Typeface.DEFAULT_BOLD);
        streamingCard.addView(tvStreamBadge);

        tvStreamOrig = new TextView(context);
        tvStreamOrig.setTextColor(Color.parseColor("#F1F5F9"));
        tvStreamOrig.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvStreamOrig.setPadding(0, dp(2), 0, 0);
        streamingCard.addView(tvStreamOrig);

        tvStreamTrans = new TextView(context);
        tvStreamTrans.setTextColor(Color.parseColor("#22D3EE"));
        tvStreamTrans.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tvStreamTrans.setTypeface(Typeface.DEFAULT_BOLD);
        tvStreamTrans.setPadding(0, dp(2), 0, 0);
        streamingCard.addView(tvStreamTrans);

        streamingCard.setVisibility(GONE);
        itemsContainer.addView(streamingCard);

        scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.addView(itemsContainer);

        // Cinema Subtitle TextView (Hiển thị to, nổi bật khi ở Cinema Mode)
        tvCinemaSubtitle = new TextView(context);
        tvCinemaSubtitle.setTextColor(Color.parseColor("#FEF08A")); // Vàng phụ đề nổi bật
        tvCinemaSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tvCinemaSubtitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvCinemaSubtitle.setShadowLayer(dp(3), 0, dp(1), Color.BLACK);
        tvCinemaSubtitle.setGravity(Gravity.CENTER);
        tvCinemaSubtitle.setPadding(dp(8), dp(8), dp(8), dp(8));
        tvCinemaSubtitle.setVisibility(GONE);

        btnExitCinema = createTextButton("🎬 Thoát Rạp Phim", v -> toggleCinemaMode());
        btnExitCinema.setVisibility(GONE);

        // Header (Drag zone)
        header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(4));

        tvTitle = new TextView(context);
        tvTitle.setTextColor(Color.parseColor("#38BDF8")); // Cyan
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvTitle.setTypeface(tvTitle.getTypeface(), Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        titleLp.rightMargin = dp(6);
        header.addView(tvTitle, titleLp);

        // Action Buttons trong HorizontalScrollView để không bao giờ bị tràn
        HorizontalScrollView hsv = new HorizontalScrollView(context);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout btnBar = new LinearLayout(context);
        btnBar.setOrientation(HORIZONTAL);
        btnBar.setGravity(Gravity.CENTER_VERTICAL);

        // 1. Nút Bật/Tắt Live OCR
        btnLiveOcr = createTextButton("⏸ Live", v -> {
            isLiveOcrRunning = !isLiveOcrRunning;
            btnLiveOcr.setText(isLiveOcrRunning ? "⏸ Live" : "▶ Live");
            String msg = isLiveOcrRunning ? "Đã bật tự động quét màn hình liên tục" : "Đã tạm dừng quét màn hình";
            Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
            if (listener != null) listener.onToggleLiveOcr();
        });
        btnBar.addView(btnLiveOcr);

        // 2. Nút Chọn Vùng
        btnCrop = createSmallButton("🔲", "Khóa vùng phụ đề", v -> {
            Toast.makeText(getContext(), "Kéo chọn vùng muốn quét Live", Toast.LENGTH_SHORT).show();
            if (listener != null) listener.onSelectRegion();
        });
        btnBar.addView(btnCrop);

        // 3. Nút Chụp Nhanh
        btnCapture = createSmallButton("📷", "Quét màn hình 1 lần", v -> {
            Toast.makeText(getContext(), "Quét màn hình 1 lần...", Toast.LENGTH_SHORT).show();
            if (listener != null) listener.onCaptureScreen();
        });
        btnBar.addView(btnCapture);

        // 4. Nút Chế độ Rạp phim (Cinema Mode)
        btnCinema = createSmallButton("🎬", "Chế độ phụ đề xem phim", v -> toggleCinemaMode());
        btnBar.addView(btnCinema);

        // 5. Nút Song ngữ / Đơn ngữ (Bilingual Mode)
        btnBilingual = createSmallButton("🌐", "Chuyển Song ngữ / Chỉ tiếng Việt", v -> {
            prefs.setBilingualMode(!prefs.isBilingualMode());
            String msg = prefs.isBilingualMode() ? "Chế độ: Song ngữ (Gốc + Dịch)" : "Chế độ: Chỉ hiển thị bản dịch tiếng Việt";
            Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
            updateBilingualState();
        });
        btnBar.addView(btnBilingual);

        // 6. Nút Dịch đè trực tiếp màn hình (In-Place Mode)
        btnInPlace = createSmallButton("🔤", "Bật/Tắt dịch đè màn hình", v -> {
            prefs.setInPlaceMode(!prefs.isInPlaceMode());
            String msg = prefs.isInPlaceMode() ? "Đã bật dịch đè (Cửa sổ phụ đề sẽ ẩn). Chạm vào bong bóng tròn để mở lại!" : "Đã tắt dịch đè trực tiếp";
            Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
            updateInPlaceState();
            if (listener != null) listener.onToggleInPlace(prefs.isInPlaceMode());
        });
        btnBar.addView(btnInPlace);

        // 7. Nút Xuất file phụ đề SRT / TXT
        btnExport = createSmallButton("💾", "Xuất file phụ đề SRT hoặc Ghi chú", v -> showExportDialog());
        btnBar.addView(btnExport);

        // 8. Nút Nghe STT
        btnListen = createSmallButton("⏸", "Tạm dừng nghe giọng nói", v -> {
            isListening = !isListening;
            btnListen.setText(isListening ? "⏸" : "▶");
            if (listener != null) listener.onToggleListening();
        });
        btnBar.addView(btnListen);

        // 9. Nút Giọng Đọc TTS
        btnTts = createSmallButton("🔊", "Bật/Tắt đọc thành tiếng", v -> {
            ttsMuted = !ttsMuted;
            btnTts.setText(ttsMuted ? "🔇" : "🔊");
            if (listener != null) listener.onToggleTts();
        });
        btnBar.addView(btnTts);

        // 10. Nút Xóa lịch sử
        Button btnClear = createSmallButton("🗑️", "Xóa lịch sử", v -> {
            itemsContainer.removeAllViews();
            itemsContainer.addView(streamingCard);
            streamingCard.setVisibility(GONE);
            historyItems.clear();
            tvCinemaSubtitle.setText("");
            tvStatus.setText("Đã xóa bản dịch.");
            if (listener != null) listener.onClearHistory();
        });
        btnBar.addView(btnClear);

        // 11. Nút Đóng
        Button btnClose = createSmallButton("✖", "Đóng cửa sổ", v -> {
            if (listener != null) listener.onClose();
        });
        btnBar.addView(btnClose);

        hsv.addView(btnBar);
        LinearLayout.LayoutParams hsvLp = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.0f);
        header.addView(hsv, hsvLp);

        applyMode(mode);

        addView(header);

        // Drag Handler
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

        // Cinema Subtitle Container
        LinearLayout.LayoutParams cinemaLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        cinemaLp.gravity = Gravity.CENTER;
        addView(tvCinemaSubtitle, cinemaLp);
        addView(btnExitCinema);

        // Chạm vào phụ đề Cinema sẽ hiện nút thoát
        tvCinemaSubtitle.setOnClickListener(v -> {
            btnExitCinema.setVisibility(VISIBLE);
            handler.removeCallbacks(hideExitCinemaRunnable);
            handler.postDelayed(hideExitCinemaRunnable, 3500);
        });

        // Footer status + Resize grip
        footer = new LinearLayout(context);
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
        updateBilingualState();
        updateInPlaceState();
    }

    private final Runnable hideExitCinemaRunnable = () -> btnExitCinema.setVisibility(GONE);

    public void toggleCinemaMode() {
        isCinemaMode = !isCinemaMode;
        if (isCinemaMode) {
            header.setVisibility(GONE);
            footer.setVisibility(GONE);
            scrollView.setVisibility(GONE);
            tvCinemaSubtitle.setVisibility(VISIBLE);
            btnExitCinema.setVisibility(VISIBLE);
            setBackgroundColor(Color.TRANSPARENT);
            setPadding(0, 0, 0, 0);
            handler.postDelayed(hideExitCinemaRunnable, 3000);
            Toast.makeText(getContext(), "Chế độ Rạp phim: Chạm vào chữ để hiện điều khiển", Toast.LENGTH_SHORT).show();
        } else {
            header.setVisibility(VISIBLE);
            footer.setVisibility(VISIBLE);
            scrollView.setVisibility(VISIBLE);
            tvCinemaSubtitle.setVisibility(GONE);
            btnExitCinema.setVisibility(GONE);
            setBackground(normalBg);
            int pad = dp(10);
            setPadding(pad, dp(6), pad, dp(6));
        }
        try { wm.updateViewLayout(this, params); } catch (Exception ignored) {}
    }

    private void updateBilingualState() {
        if (btnBilingual != null) {
            btnBilingual.setTextColor(prefs.isBilingualMode() ? Color.parseColor("#38BDF8") : Color.parseColor("#94A3B8"));
        }
    }

    private void updateInPlaceState() {
        if (btnInPlace != null) {
            btnInPlace.setTextColor(prefs.isInPlaceMode() ? Color.parseColor("#10B981") : Color.parseColor("#94A3B8"));
        }
    }

    private void showExportDialog() {
        String[] options = {"Xuất file Phụ đề (.SRT)", "Xuất file Ghi chú (.TXT)"};
        new AlertDialog.Builder(getContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Xuất lịch sử dịch")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        SubtitleExporter.exportAsSrt(getContext(), historyItems);
                    } else {
                        SubtitleExporter.exportAsTxt(getContext(), historyItems);
                    }
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    public void applyMode(String mode) {
        if ("mode_ocr".equals(mode)) {
            tvTitle.setText("Live OCR");
            tvStatus.setText("Đang tự động quét màn hình liên tục...");
            btnLiveOcr.setVisibility(VISIBLE);
            btnCrop.setVisibility(VISIBLE);
            btnCapture.setVisibility(VISIBLE);
            btnInPlace.setVisibility(VISIBLE);
            btnListen.setVisibility(GONE);
            btnTts.setVisibility(VISIBLE);
        } else if ("mode_stt".equals(mode)) {
            tvTitle.setText("Live STT");
            tvStatus.setText("Đang lắng nghe âm thanh trực tiếp...");
            btnLiveOcr.setVisibility(GONE);
            btnCrop.setVisibility(GONE);
            btnCapture.setVisibility(GONE);
            btnInPlace.setVisibility(GONE);
            btnListen.setVisibility(VISIBLE);
            btnTts.setVisibility(VISIBLE);
        } else {
            tvTitle.setText("Live TransX");
            tvStatus.setText("Đang quét màn hình & nghe âm thanh...");
            btnLiveOcr.setVisibility(VISIBLE);
            btnCrop.setVisibility(VISIBLE);
            btnCapture.setVisibility(VISIBLE);
            btnInPlace.setVisibility(VISIBLE);
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

    public void updateStreamingText(String orig, String trans, boolean isFinal) {
        post(() -> {
            if (isFinal) {
                streamingCard.setVisibility(GONE);
                tvStreamOrig.setText("");
                tvStreamTrans.setText("");
                if (trans != null && !trans.isEmpty()) {
                    tvCinemaSubtitle.setText(trans);
                }
            } else {
                streamingCard.setVisibility(VISIBLE);
                if (orig == null || orig.trim().isEmpty()) {
                    tvStreamBadge.setText("⚡ Đang lắng nghe...");
                    tvStreamOrig.setText("...");
                    tvStreamTrans.setText("");
                } else {
                    tvStreamBadge.setText("⚡ Live Stream");
                    tvStreamOrig.setText(orig + " ▌");
                    tvStreamTrans.setText(trans != null && !trans.trim().isEmpty() ? trans : "Đang dịch...");
                    if (trans != null && !trans.trim().isEmpty()) {
                        tvCinemaSubtitle.setText(trans);
                    } else {
                        tvCinemaSubtitle.setText(orig);
                    }
                }
                scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    public void addTranslation(String orig, String trans) {
        post(() -> {
            historyItems.add(new SubtitleExporter.SubtitleItem(System.currentTimeMillis(), orig, trans));

            // Cập nhật phụ đề rạp phim
            tvCinemaSubtitle.setText(trans);

            LinearLayout entry = new LinearLayout(getContext());
            entry.setOrientation(VERTICAL);
            int ePad = dp(10);
            entry.setPadding(ePad, ePad, ePad, ePad);

            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setShape(GradientDrawable.RECTANGLE);
            cardBg.setColor(Color.parseColor("#1E293B"));
            cardBg.setCornerRadius(dp(12));
            cardBg.setStroke(dp(1), Color.parseColor("#334155"));
            entry.setBackground(cardBg);

            LinearLayout.LayoutParams entryLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            entryLp.bottomMargin = dp(8);
            entry.setLayoutParams(entryLp);

            String timeStr = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());

            // Header row with time
            TextView tvTime = new TextView(getContext());
            tvTime.setText("🕒 " + timeStr);
            tvTime.setTextColor(Color.parseColor("#64748B"));
            tvTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            entry.addView(tvTime);

            // Nếu có câu gốc thì hiển thị
            if (orig != null && !orig.trim().isEmpty()) {
                TextView tvOrig = new TextView(getContext());
                tvOrig.setText(orig);
                tvOrig.setTextColor(Color.parseColor("#F1F5F9"));
                tvOrig.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                tvOrig.setPadding(0, dp(2), 0, 0);
                entry.addView(tvOrig);
            }

            TextView tvTrans = new TextView(getContext());
            tvTrans.setText(trans);
            tvTrans.setTextColor(Color.parseColor("#38BDF8")); // Bright Cyan
            tvTrans.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tvTrans.setTypeface(tvTrans.getTypeface(), Typeface.BOLD);
            tvTrans.setPadding(0, dp(3), 0, 0);
            entry.addView(tvTrans);

            entry.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("TransX", trans));
                    Toast.makeText(getContext(), "Đã copy bản dịch!", Toast.LENGTH_SHORT).show();
                }
            });

            // Luôn thêm trước streamingCard để streamingCard luôn nằm ở đáy danh sách
            int streamIdx = itemsContainer.indexOfChild(streamingCard);
            if (streamIdx >= 0) {
                itemsContainer.addView(entry, streamIdx);
            } else {
                itemsContainer.addView(entry);
            }
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

    private Button createSmallButton(String text, String tooltip, OnClickListener listener) {
        Button b = new Button(getContext());
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(32));
        lp.leftMargin = dp(2);
        b.setLayoutParams(lp);
        b.setOnClickListener(listener);
        b.setOnLongClickListener(v -> {
            Toast.makeText(getContext(), tooltip, Toast.LENGTH_SHORT).show();
            return true;
        });
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
