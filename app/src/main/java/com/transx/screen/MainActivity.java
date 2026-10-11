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
    private static final int REQ_PERMS = 102;
    private static final int REQ_PROJECTION = 103;

    private Prefs prefs;
    private TextView tvStatus;
    private TextView tvPermissionInfo;
    private EditText etApiKey;
    private Spinner spinnerSourceLang;
    private Spinner spinnerTargetLang;
    private RadioGroup rgAudioSource;
    private SwitchMaterial switchTts;

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

        // Set default selections
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

        Button btnStart = findViewById(R.id.btnStart);
        btnStart.setOnClickListener(v -> startTranslationFlow());

        Button btnStop = findViewById(R.id.btnStop);
        btnStop.setOnClickListener(v -> {
            stopService(new Intent(this, TransXService.class));
            tvStatus.setText("Trạng thái: Đã dừng dịch");
            Toast.makeText(this, "Đã tắt dịch vụ TransX", Toast.LENGTH_SHORT).show();
        });
    }

    private void testAndSaveApiKey() {
        String key = etApiKey.getText().toString().trim();
        prefs.setGoogleApiKey(key);

        if (key.isEmpty()) {
            Toast.makeText(this, "Đã xóa API Key. Ứng dụng sẽ dùng chế độ dự phòng", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "Đang kiểm tra kết nối Google Cloud...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                // Test Google TTS
                byte[] testAudio = GoogleSpeechClient.synthesize("Xin chào", "vi-VN-Neural2-A", "vi-VN", 1.0f, key);
                boolean ttsOk = (testAudio != null && testAudio.length > 0);

                // Test Edge Translation
                String translated = new EdgeTranslator().translateSingle("Hello", "en", "vi");
                boolean edgeOk = !translated.isEmpty();

                runOnUiThread(() -> {
                    if (ttsOk && edgeOk) {
                        Toast.makeText(this, "Kết nối Google Cloud & Edge API thành công!", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "Lưu thành công. Kiểm tra dịch: " + translated, Toast.LENGTH_SHORT).show();
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

        StringBuilder sb = new StringBuilder("• Quyền hệ thống: ");
        sb.append(overlayOk ? "Cửa sổ nổi [ĐÃ CẤP]" : "Cửa sổ nổi [CHƯA CẤP]");
        sb.append(" | ");
        sb.append(micOk ? "Ghi âm [ĐÃ CẤP]" : "Ghi âm [CHƯA CẤP]");
        tvPermissionInfo.setText(sb.toString());
    }

    private void startTranslationFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
            Toast.makeText(this, "Vui lòng cấp quyền Hiển thị trên ứng dụng khác", Toast.LENGTH_LONG).show();
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.RECORD_AUDIO,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? Manifest.permission.POST_NOTIFICATIONS : Manifest.permission.RECORD_AUDIO
            }, REQ_PERMS);
            return;
        }

        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm != null) {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
        } else {
            startTransService(null, 0);
        }
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
                Toast.makeText(this, "Không có quyền quay màn hình, chuyển sang chế độ Mic ngoài", Toast.LENGTH_SHORT).show();
                startTransService(null, 0);
            }
        }
    }

    private void startTransService(Intent projectionData, int projectionCode) {
        TransXService.pendingProjectionData = projectionData;
        TransXService.pendingProjectionCode = projectionCode;

        Intent svc = new Intent(this, TransXService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }

        tvStatus.setText("Trạng thái: Đang chạy nút dịch nổi trên màn hình");
        Toast.makeText(this, "Đã bật TransX Screen!", Toast.LENGTH_SHORT).show();
        finish(); // Cho phép người dùng ra ngoài sử dụng các app khác
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkPermissions();
    }
}
