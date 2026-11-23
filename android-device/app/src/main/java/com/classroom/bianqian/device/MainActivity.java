package com.classroom.bianqian.device;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private Prefs prefs;
    private ImageView qr;
    private TextView statusPill;
    private TextView overlayHint;
    private TextView batteryHint;
    private Button btnToggle;
    private Switch swBoot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);

        qr = findViewById(R.id.qr);
        statusPill = findViewById(R.id.statusPill);
        overlayHint = findViewById(R.id.overlayHint);
        batteryHint = findViewById(R.id.batteryHint);
        btnToggle = findViewById(R.id.btnToggle);
        swBoot = findViewById(R.id.swBoot);

        findViewById(R.id.btnOverlay).setOnClickListener(v -> askOverlay());
        findViewById(R.id.btnBattery).setOnClickListener(v -> askBattery());

        EditText name = findViewById(R.id.deviceName);
        name.setText(prefs.name());
        name.setOnFocusChangeListener((v, has) -> {
            if (!has) {
                prefs.setName(name.getText().toString());
                refresh();
            }
        });

        swBoot.setChecked(prefs.bootEnabled());
        swBoot.setOnCheckedChangeListener((v, checked) -> prefs.setBootEnabled(checked));

        btnToggle.setOnClickListener(v -> {
            if (NoteService.isRunning()) {
                stopService(new Intent(this, NoteService.class));
                prefs.setServiceEnabled(false);
                Toast.makeText(this, "已停止接收便签", Toast.LENGTH_SHORT).show();
            } else {
                if (!NoteOverlay.canShow(this)) {
                    Toast.makeText(this, "请先允许悬浮窗权限", Toast.LENGTH_SHORT).show();
                    askOverlay();
                    return;
                }
                startNoteService();
                Toast.makeText(this, "已开启常驻接收", Toast.LENGTH_SHORT).show();
            }
            btnToggle.postDelayed(this::refresh, 700);
        });

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 11);
        }
        refresh();
    }

    private void startNoteService() {
        prefs.setServiceEnabled(true);
        Intent svc = new Intent(this, NoteService.class).setAction(NoteService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, svc);
        } else {
            startService(svc);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        TextView idv = findViewById(R.id.deviceId);
        TextView keyv = findViewById(R.id.deviceKey);
        idv.setText(prefs.deviceId());
        keyv.setText("密钥 " + prefs.deviceKey());

        EditText name = findViewById(R.id.deviceName);
        if (!name.hasFocus() && !TextUtils.equals(name.getText(), prefs.name())) {
            name.setText(prefs.name());
        }
        qr.setImageBitmap(makeQr(Pairing.pairingString(prefs.deviceId(), prefs.deviceKey(), prefs.name())));

        boolean running = NoteService.isRunning();
        statusPill.setText(running ? "运行中" : "未开启");
        statusPill.setBackgroundResource(running ? R.drawable.bg_pill_green : R.drawable.bg_pill_gray);
        statusPill.setTextColor(running ? 0xFF0F9D63 : 0xFF6B7385);
        btnToggle.setText(running ? "停止接收" : "开启常驻接收");

        boolean can = NoteOverlay.canShow(this);
        overlayHint.setText(can ? "已授权，便签可浮在任意应用上层" : "未授权，便签无法浮在应用上层");
        overlayHint.setTextColor(can ? 0xFF0F9D63 : 0xFFD98600);

        boolean ignoring = isIgnoringBattery();
        batteryHint.setText(ignoring ? "已设为不限制，后台更稳" : "建议设为「不限制」，避免后台被系统杀掉");
        batteryHint.setTextColor(ignoring ? 0xFF0F9D63 : 0xFF8B94A6);

        if (running && !can) {
            overlayHint.setText("服务在跑，但缺少悬浮窗权限，便签不会显示");
            overlayHint.setTextColor(0xFFD92D3C);
        }
    }

    private boolean isIgnoringBattery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void askOverlay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
    }

    private void askBattery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    private Bitmap makeQr(String text) {
        int size = 480;
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int[] px = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    px[y * size + x] = m.get(x, y) ? 0xFF171C26 : Color.WHITE;
                }
            }
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.RGB_565);
        } catch (Exception e) {
            return null;
        }
    }
}
