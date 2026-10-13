package com.transx.screen;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.transx.screen.speech.EdgeTranslator;
import com.transx.screen.speech.GoogleSpeechClient;
import com.transx.screen.util.Lang;
import com.transx.screen.util.Prefs;
import com.transx.screen.util.SubtitleExporter;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_OVERLAY = 101;
    private static final int REQ_PERMS_STT = 102;
    private static final int REQ_PROJECTION = 103;

    private Prefs prefs;

    // Tabs
    private Button tabLiveStream;
    private Button tabSettingsOcr;
    private View viewLiveStream;
    private View viewSettingsOcr;

    // Tab 1: Live Stream views
    private Spinner spinnerLiveSourceLang;
    private Button btnSwapLangs;
    private Spinner spinnerLiveTargetLang;
    private ScrollView scrollTranscript;
    private LinearLayout llTranscriptContainer;
    private TextView tvEmptyTranscript;
    private LinearLayout mainStreamingCard;
    private TextView tvMainStreamBadge;
    private TextView tvMainStreamOrig;
    private TextView tvMainStreamTrans;
    private TextView tvLiveStatus;
    private Button btnLiveMic;
    private Button btnLaunchFloating;
    private Button btnClearTranscript;
    private Button btnExportTranscript;

    // Tab 2: Settings & OCR views
    private TextView tvStatus;
    private TextView tvPermissionInfo;
    private EditText etApiKey;
    private Spinner spinnerSourceLang;
    private Spinner spinnerTargetLang;
    private RadioGroup rgAudioSource;
    private SwitchMaterial switchTts;

    private String pendingTargetMode = TransXService.MODE_STT;
    private boolean closeAfterStart = false;

    private final TransXService.StreamingListener streamingListener = new TransXService.StreamingListener() {
        @Override
        public void onStreamingUpdate(String original, String translated, boolean isFinal) {
            runOnUiThread(() -> {
                if (isFinal) {
                    updateStreamingCard("", "", true);
                    if (original != null && !original.trim().isEmpty()) {
                        addTranscriptCard(original, translated);
                    }
                } else {
                    updateStreamingCard(original, translated, false);
                }
            });
        }

        @Override
        public void onSpeechStart() {
            runOnUiThread(() -> updateStreamingCard("", "", false));
        }

        @Override
        public void onStatusUpdate(String status, int color) {
            runOnUiThread(() -> {
                tvLiveStatus.setText(status);
                tvStatus.setText("Trạng thái: " + status);
            });
        }

        @Override
        public void onHistoryCleared() {
            runOnUiThread(() -> refreshTranscriptList());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = new Prefs(this);

        bindViews();
        setupTabs();
        setupLanguageSpinners();
        setupLiveStreamControls();
        setupSettingsAndOcrControls();
        checkPermissions();
    }

    private void bindViews() {
        tabLiveStream = findViewById(R.id.tabLiveStream);
        tabSettingsOcr = findViewById(R.id.tabSettingsOcr);
        viewLiveStream = findViewById(R.id.viewLiveStream);
        viewSettingsOcr = findViewById(R.id.viewSettingsOcr);

        spinnerLiveSourceLang = findViewById(R.id.spinnerLiveSourceLang);
        btnSwapLangs = findViewById(R.id.btnSwapLangs);
        spinnerLiveTargetLang = findViewById(R.id.spinnerLiveTargetLang);
        scrollTranscript = findViewById(R.id.scrollTranscript);
        llTranscriptContainer = findViewById(R.id.llTranscriptContainer);
        tvEmptyTranscript = findViewById(R.id.tvEmptyTranscript);
        mainStreamingCard = findViewById(R.id.mainStreamingCard);
        tvMainStreamBadge = findViewById(R.id.tvMainStreamBadge);
        tvMainStreamOrig = findViewById(R.id.tvMainStreamOrig);
        tvMainStreamTrans = findViewById(R.id.tvMainStreamTrans);
        tvLiveStatus = findViewById(R.id.tvLiveStatus);
        btnLiveMic = findViewById(R.id.btnLiveMic);
        btnLaunchFloating = findViewById(R.id.btnLaunchFloating);
        btnClearTranscript = findViewById(R.id.btnClearTranscript);
        btnExportTranscript = findViewById(R.id.btnExportTranscript);

        tvStatus = findViewById(R.id.tvStatus);
        tvPermissionInfo = findViewById(R.id.tvPermissionInfo);
        etApiKey = findViewById(R.id.etApiKey);
        spinnerSourceLang = findViewById(R.id.spinnerSourceLang);
        spinnerTargetLang = findViewById(R.id.spinnerTargetLang);
        rgAudioSource = findViewById(R.id.rgAudioSource);
        switchTts = findViewById(R.id.switchTts);
    }

    private void setupTabs() {
        tabLiveStream.setOnClickListener(v -> switchTab(true));
        tabSettingsOcr.setOnClickListener(v -> switchTab(false));
    }

    private void switchTab(boolean isLive) {
        if (isLive) {
            viewLiveStream.setVisibility(View.VISIBLE);
            viewSettingsOcr.setVisibility(View.GONE);
            tabLiveStream.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#0284C7")));
            tabLiveStream.setTextColor(Color.WHITE);
            tabSettingsOcr.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
            tabSettingsOcr.setTextColor(Color.parseColor("#94A3B8"));
        } else {
            viewLiveStream.setVisibility(View.GONE);
            viewSettingsOcr.setVisibility(View.VISIBLE);
            tabSettingsOcr.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#0284C7")));
            tabSettingsOcr.setTextColor(Color.WHITE);
            tabLiveStream.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
            tabLiveStream.setTextColor(Color.parseColor("#94A3B8"));
        }
    }

    private void setupLanguageSpinners() {
        ArrayAdapter<Lang> langAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, Lang.ALL);
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        spinnerLiveSourceLang.setAdapter(langAdapter);
        spinnerLiveTargetLang.setAdapter(langAdapter);
        spinnerSourceLang.setAdapter(langAdapter);
        spinnerTargetLang.setAdapter(langAdapter);

        // Sync initial positions
        for (int i = 0; i < Lang.ALL.length; i++) {
            if (Lang.ALL[i].edgeCode.equals(prefs.getSourceLang())) {
                spinnerLiveSourceLang.setSelection(i);
                spinnerSourceLang.setSelection(i);
            }
            if (Lang.ALL[i].edgeCode.equals(prefs.getTargetLang())) {
                spinnerLiveTargetLang.setSelection(i);
                spinnerTargetLang.setSelection(i);
            }
        }

        AdapterView.OnItemSelectedListener srcListener = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                String code = Lang.ALL[pos].edgeCode;
                prefs.setSourceLang(code);
                if (spinnerSourceLang.getSelectedItemPosition() != pos) spinnerSourceLang.setSelection(pos);
                if (spinnerLiveSourceLang.getSelectedItemPosition() != pos) spinnerLiveSourceLang.setSelection(pos);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        };

        AdapterView.OnItemSelectedListener tgtListener = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                String code = Lang.ALL[pos].edgeCode;
                prefs.setTargetLang(code);
                if (spinnerTargetLang.getSelectedItemPosition() != pos) spinnerTargetLang.setSelection(pos);
                if (spinnerLiveTargetLang.getSelectedItemPosition() != pos) spinnerLiveTargetLang.setSelection(pos);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        };

        spinnerLiveSourceLang.setOnItemSelectedListener(srcListener);
        spinnerSourceLang.setOnItemSelectedListener(srcListener);
        spinnerLiveTargetLang.setOnItemSelectedListener(tgtListener);
        spinnerTargetLang.setOnItemSelectedListener(tgtListener);

        // Quick Swap Languages Button
        btnSwapLangs.setOnClickListener(v -> {
            int srcPos = spinnerLiveSourceLang.getSelectedItemPosition();
            int tgtPos = spinnerLiveTargetLang.getSelectedItemPosition();
            spinnerLiveSourceLang.setSelection(tgtPos);
            spinnerLiveTargetLang.setSelection(srcPos);
            Toast.makeText(this, "Đã hoán đổi ngôn ngữ nguồn và đích", Toast.LENGTH_SHORT).show();
        });
    }

    private void setupLiveStreamControls() {
        btnLiveMic.setOnClickListener(v -> {
            if (TransXService.isServiceRunning()) {
                stopService(new Intent(this, TransXService.class));
                updateMicUi(false);
                tvLiveStatus.setText("Đã dừng dịch");
                updateStreamingCard("", "", true);
            } else {
                closeAfterStart = false;
                startServiceWithMode(TransXService.MODE_STT);
            }
        });

        btnLaunchFloating.setOnClickListener(v -> {
            if (!TransXService.isServiceRunning()) {
                closeAfterStart = true;
                startServiceWithMode(TransXService.MODE_STT);
            } else {
                Toast.makeText(this, "Đang mở cửa sổ nổi trên màn hình", Toast.LENGTH_SHORT).show();
                moveTaskToBack(true);
            }
        });

        btnClearTranscript.setOnClickListener(v -> {
            TransXService.clearSessionHistory();
            refreshTranscriptList();
            Toast.makeText(this, "Đã xóa toàn bộ hội thoại", Toast.LENGTH_SHORT).show();
        });

        btnExportTranscript.setOnClickListener(v -> showExportDialog());
    }

    private void setupSettingsAndOcrControls() {
        etApiKey.setText(prefs.getGoogleApiKey());

        Button btnSaveKey = findViewById(R.id.btnSaveKey);
        btnSaveKey.setOnClickListener(v -> testAndSaveApiKey());

        if ("mic".equals(prefs.getAudioSource())) {
            ((RadioButton) findViewById(R.id.rbMicAudio)).setChecked(true);
        } else {
            ((RadioButton) findViewById(R.id.rbInternalAudio)).setChecked(true);
        }

        rgAudioSource.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rbMicAudio) {
                prefs.setAudioSource("mic");
            } else {
                prefs.setAudioSource("internal");
            }
        });

        switchTts.setChecked(prefs.isTtsEnabled());
        switchTts.setOnCheckedChangeListener((b, isChecked) -> prefs.setTtsEnabled(isChecked));

        Button btnStartOcr = findViewById(R.id.btnStartOcr);
        btnStartOcr.setOnClickListener(v -> {
            closeAfterStart = true;
            startServiceWithMode(TransXService.MODE_OCR);
        });

        Button btnStartStt = findViewById(R.id.btnStartStt);
        btnStartStt.setOnClickListener(v -> {
            closeAfterStart = true;
            startServiceWithMode(TransXService.MODE_STT);
        });

        Button btnStartBoth = findViewById(R.id.btnStartBoth);
        btnStartBoth.setOnClickListener(v -> {
            closeAfterStart = true;
            startServiceWithMode(TransXService.MODE_BOTH);
        });

        Button btnStop = findViewById(R.id.btnStop);
        btnStop.setOnClickListener(v -> {
            stopService(new Intent(this, TransXService.class));
            updateMicUi(false);
            tvStatus.setText("Trạng thái: Đã dừng dịch");
            tvLiveStatus.setText("Đã dừng dịch");
            updateStreamingCard("", "", true);
            Toast.makeText(this, "Đã tắt dịch vụ TransX", Toast.LENGTH_SHORT).show();
        });
    }

    private void updateMicUi(boolean isRunning) {
        if (isRunning) {
            btnLiveMic.setText("🛑 DỪNG DỊCH");
            btnLiveMic.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#DC2626")));
            tvLiveStatus.setText("Đang lắng nghe trực tiếp...");
        } else {
            btnLiveMic.setText("🎙️ BẮT ĐẦU DỊCH TRỰC TIẾP");
            btnLiveMic.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#0284C7")));
            tvLiveStatus.setText("Sẵn sàng dịch trực tiếp");
        }
    }

    private void updateStreamingCard(String orig, String trans, boolean isFinal) {
        if (isFinal) {
            mainStreamingCard.setVisibility(View.GONE);
            tvMainStreamOrig.setText("");
            tvMainStreamTrans.setText("");
        } else {
            tvEmptyTranscript.setVisibility(View.GONE);
            mainStreamingCard.setVisibility(View.VISIBLE);
            if (orig == null || orig.trim().isEmpty()) {
                tvMainStreamBadge.setText("⚡ ĐANG LẮNG NGHE...");
                tvMainStreamOrig.setText("...");
                tvMainStreamTrans.setText("");
            } else {
                tvMainStreamBadge.setText("⚡ LIVE STREAM");
                tvMainStreamOrig.setText(orig + " ▌");
                tvMainStreamTrans.setText(trans != null && !trans.trim().isEmpty() ? trans : "Đang dịch...");
            }
            scrollTranscript.post(() -> scrollTranscript.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void addTranscriptCard(String orig, String trans) {
        tvEmptyTranscript.setVisibility(View.GONE);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        card.setPadding(pad, pad, pad, pad);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(Color.parseColor("#1E293B"));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.parseColor("#334155"));
        card.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);

        String timeStr = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());

        TextView tvTime = new TextView(this);
        tvTime.setText("🕒 " + timeStr);
        tvTime.setTextColor(Color.parseColor("#64748B"));
        tvTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        card.addView(tvTime);

        if (orig != null && !orig.trim().isEmpty()) {
            TextView tvOrig = new TextView(this);
            tvOrig.setText(orig);
            tvOrig.setTextColor(Color.WHITE);
            tvOrig.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tvOrig.setTypeface(Typeface.DEFAULT_BOLD);
            tvOrig.setPadding(0, dp(4), 0, 0);
            card.addView(tvOrig);
        }

        TextView tvTrans = new TextView(this);
        tvTrans.setText(trans);
        tvTrans.setTextColor(Color.parseColor("#38BDF8"));
        tvTrans.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvTrans.setTypeface(Typeface.DEFAULT_BOLD);
        tvTrans.setPadding(0, dp(4), 0, 0);
        card.addView(tvTrans);

        card.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("TransX", trans));
                Toast.makeText(this, "Đã copy bản dịch!", Toast.LENGTH_SHORT).show();
            }
        });

        int streamIdx = llTranscriptContainer.indexOfChild(mainStreamingCard);
        if (streamIdx >= 0) {
            llTranscriptContainer.addView(card, streamIdx);
        } else {
            llTranscriptContainer.addView(card);
        }

        scrollTranscript.post(() -> scrollTranscript.fullScroll(View.FOCUS_DOWN));
    }

    private void refreshTranscriptList() {
        llTranscriptContainer.removeAllViews();
        llTranscriptContainer.addView(tvEmptyTranscript);
        llTranscriptContainer.addView(mainStreamingCard);

        List<SubtitleExporter.SubtitleItem> history = TransXService.getSessionHistory();
        if (history.isEmpty()) {
            tvEmptyTranscript.setVisibility(View.VISIBLE);
        } else {
            tvEmptyTranscript.setVisibility(View.GONE);
            for (SubtitleExporter.SubtitleItem item : history) {
                addTranscriptCard(item.original, item.translated);
            }
        }
    }

    private void showExportDialog() {
        List<SubtitleExporter.SubtitleItem> history = TransXService.getSessionHistory();
        if (history.isEmpty()) {
            Toast.makeText(this, "Chưa có bản ghi nào để xuất!", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] options = {"Xuất file Phụ đề (.SRT)", "Xuất file Ghi chú (.TXT)"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Xuất hội thoại đã dịch")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        SubtitleExporter.exportAsSrt(this, history);
                    } else {
                        SubtitleExporter.exportAsTxt(this, history);
                    }
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    private void startServiceWithMode(String mode) {
        this.pendingTargetMode = mode;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
            Toast.makeText(this, "Vui lòng cấp quyền Hiển thị trên ứng dụng khác", Toast.LENGTH_LONG).show();
            return;
        }

        if (!TransXService.MODE_OCR.equals(mode)) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.RECORD_AUDIO,
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? Manifest.permission.POST_NOTIFICATIONS : Manifest.permission.RECORD_AUDIO
                }, REQ_PERMS_STT);
                return;
            }
        }

        boolean needProjection = TransXService.MODE_OCR.equals(mode) || TransXService.MODE_BOTH.equals(mode) || "internal".equals(prefs.getAudioSource());
        if (needProjection) {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mpm != null) {
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
            } else {
                startTransService(null, 0);
            }
        } else {
            startTransService(null, 0);
        }
    }

    private void testAndSaveApiKey() {
        String key = etApiKey.getText().toString().trim();
        prefs.setGoogleApiKey(key);

        if (key.isEmpty()) {
            Toast.makeText(this, "Đã xóa API Key. STT/TTS sẽ dùng giọng mặc định máy", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "Đang kiểm tra kết nối Google Cloud...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                byte[] testAudio = GoogleSpeechClient.synthesize("Xin chào", "vi-VN-Neural2-A", "vi-VN", 1.0f, key);
                boolean ttsOk = (testAudio != null && testAudio.length > 0);

                String translated = new EdgeTranslator().translateSingle("Hello", "en", "vi");
                boolean edgeOk = !translated.isEmpty();

                runOnUiThread(() -> {
                    if (ttsOk && edgeOk) {
                        Toast.makeText(this, "Google Cloud STT/TTS & Edge API kết nối thành công!", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "Lưu thành công: " + translated, Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Lỗi kiểm tra API Key: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void checkPermissions() {
        boolean overlayOk = (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) || Settings.canDrawOverlays(this);
        boolean micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;

        StringBuilder sb = new StringBuilder("• Quyền: ");
        sb.append(overlayOk ? "Cửa sổ nổi [✓]" : "Cửa sổ nổi [✕]");
        sb.append(" | ");
        sb.append(micOk ? "Microphone [✓]" : "Microphone [✕]");
        tvPermissionInfo.setText(sb.toString());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY) {
            checkPermissions();
        } else if (requestCode == REQ_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                startTransService(data, resultCode);
            } else {
                Toast.makeText(this, "Chưa cấp quyền màn hình", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startTransService(Intent projectionData, int projectionCode) {
        TransXService.pendingProjectionData = projectionData;
        TransXService.pendingProjectionCode = projectionCode;

        Intent svc = new Intent(this, TransXService.class);
        svc.putExtra(TransXService.EXTRA_MODE, pendingTargetMode);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }

        String modeLabel = TransXService.MODE_OCR.equals(pendingTargetMode) ? "Dịch Màn Hình (OCR)" :
                (TransXService.MODE_STT.equals(pendingTargetMode) ? "Dịch Giọng Nói (STT)" : "Dịch Màn Hình & Giọng Nói");
        tvStatus.setText("Trạng thái: Đang chạy " + modeLabel);
        updateMicUi(true);
        Toast.makeText(this, "Đã bật " + modeLabel, Toast.LENGTH_SHORT).show();

        if (closeAfterStart) {
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkPermissions();
        TransXService.setStreamingListener(streamingListener);
        updateMicUi(TransXService.isServiceRunning());
        refreshTranscriptList();
    }

    @Override
    protected void onPause() {
        super.onPause();
        TransXService.removeStreamingListener(streamingListener);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
