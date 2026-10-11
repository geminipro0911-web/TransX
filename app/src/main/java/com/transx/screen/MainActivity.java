package com.transx.screen;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
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

public class MainActivity extends AppCompatActivity {
    private static final int REQ_OVERLAY = 101;
    private static final int REQ_PERMS_STT = 102;
    private static final int REQ_PROJECTION = 103;

    private Prefs prefs;
    private TextView tvStatus;
    private TextView tvPermissionInfo;
    private EditText etApiKey;
    private Spinner spinnerSourceLang;
    private Spinner spinnerTargetLang;
    private RadioGroup rgAudioSource;
    private SwitchMaterial switchTts;

    private String pendingTargetMode = TransXService.MODE_BOTH;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = new Prefs(this);

        tvStatus = findViewById(R.id.tvStatus);
        tvPermissionInfo = findViewById(R.id.tvPermissionInfo);
        etApiKey = findViewById(R.id.etApiKey);
        spinnerSourceLang = findViewById(R.id.spinnerSourceLang);
        spinnerTargetLang = findViewById(R.id.spinnerTargetLang);
        rgAudioSource = findViewById(R.id.rgAudioSource);
        switchTts = findViewById(R.id.switchTts);

        initViews();
        checkPermissions();
    }

    private void initViews() {
        etApiKey.setText(prefs.getGoogleApiKey());

        Button btnSaveKey = findViewById(R.id.btnSaveKey);
        btnSaveKey.setOnClickListener(v -> testAndSaveApiKey());

        ArrayAdapter<Lang> langAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, Lang.ALL);
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        spinnerSourceLang.setAdapter(langAdapter);
        spinnerTargetLang.setAdapter(langAdapter);

        for (int i = 0; i < Lang.ALL.length; i++) {
            if (Lang.ALL[i].edgeCode.equals(prefs.getSourceLang())) {
                spinnerSourceLang.setSelection(i);
            }
            if (Lang.ALL[i].edgeCode.equals(prefs.getTargetLang())) {
                spinnerTargetLang.setSelection(i);
            }
        }

        spinnerSourceLang.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.setSourceLang(Lang.ALL[pos].edgeCode);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        spinnerTargetLang.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.setTargetLang(Lang.ALL[pos].edgeCode);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

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

        // 1. Nút Bật Dịch Màn Hình (OCR Offline)
        Button btnStartOcr = findViewById(R.id.btnStartOcr);
        btnStartOcr.setOnClickListener(v -> startServiceWithMode(TransXService.MODE_OCR));

        // 2. Nút Bật Dịch Giọng Nói (Live STT)
        Button btnStartStt = findViewById(R.id.btnStartStt);
        btnStartStt.setOnClickListener(v -> startServiceWithMode(TransXService.MODE_STT));

        // 3. Nút Bật Cả Hai
        Button btnStartBoth = findViewById(R.id.btnStartBoth);
        btnStartBoth.setOnClickListener(v -> startServiceWithMode(TransXService.MODE_BOTH));

        // 4. Nút Dừng Dịch Vụ
        Button btnStop = findViewById(R.id.btnStop);
        btnStop.setOnClickListener(v -> {
            stopService(new Intent(this, TransXService.class));
            tvStatus.setText("Trạng thái: Đã dừng dịch");
            Toast.makeText(this, "Đã tắt dịch vụ TransX", Toast.LENGTH_SHORT).show();
        });
    }

    private void startServiceWithMode(String mode) {
        this.pendingTargetMode = mode;

        // Kiểm tra quyền Cửa Sổ Nổi
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
            Toast.makeText(this, "Vui lòng cấp quyền Hiển thị trên ứng dụng khác", Toast.LENGTH_LONG).show();
            return;
        }

        // Nếu chế độ có dịch giọng nói STT, kiểm tra quyền Microphone
        if (!TransXService.MODE_OCR.equals(mode)) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.RECORD_AUDIO,
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? Manifest.permission.POST_NOTIFICATIONS : Manifest.permission.RECORD_AUDIO
                }, REQ_PERMS_STT);
                return;
            }
        }

        // Yêu cầu MediaProjection nếu cần chụp màn hình OCR hoặc thu âm trong máy
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
        Toast.makeText(this, "Đã bật " + modeLabel, Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkPermissions();
    }
}
