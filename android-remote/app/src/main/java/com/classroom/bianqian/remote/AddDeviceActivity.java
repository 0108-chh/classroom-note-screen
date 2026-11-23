package com.classroom.bianqian.remote;

import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

/** 扫码或手输，把一台教室大屏绑定到本机。 */
public class AddDeviceActivity extends AppCompatActivity {

    private EditText etId;
    private EditText etKey;
    private EditText etName;

    private final ActivityResultLauncher<ScanOptions> scanner =
            registerForActivityResult(new ScanContract(), result -> {
                if (result.getContents() == null) {
                    return;
                }
                applyCode(result.getContents());
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add);

        etId = findViewById(R.id.etId);
        etKey = findViewById(R.id.etKey);
        etName = findViewById(R.id.etName);

        findViewById(R.id.btnScan).setOnClickListener(v -> {
            ScanOptions o = new ScanOptions();
            o.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
            o.setPrompt("对准教室大屏上的二维码");
            o.setBeepEnabled(false);
            o.setOrientationLocked(false);
            scanner.launch(o);
        });

        findViewById(R.id.btnSave).setOnClickListener(v -> save());
    }

    private void applyCode(String text) {
        String[] p = Pairing.parse(text);
        if (p == null) {
            Toast.makeText(this, "这不是本程序大屏上的二维码", Toast.LENGTH_LONG).show();
            return;
        }
        etId.setText(p[0]);
        etKey.setText(p[1]);
        if (p[2] != null && !p[2].isEmpty()) {
            etName.setText(p[2]);
        }
        Toast.makeText(this, "已识别，点「保存绑定」完成", Toast.LENGTH_SHORT).show();
    }

    private void save() {
        String id = etId.getText().toString().trim().toUpperCase();
        String key = etKey.getText().toString().trim().toUpperCase();
        String name = etName.getText().toString().trim();
        if (id.length() < 4 || key.length() < 6) {
            Toast.makeText(this, "设备号或密钥不对，请重新扫码", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent out = new Intent();
        out.putExtra("id", id);
        out.putExtra("key", key);
        out.putExtra("name", name);
        setResult(RESULT_OK, out);
        finish();
    }
}
