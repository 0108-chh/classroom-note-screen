package com.classroom.bianqian.remote;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity implements RemoteBus.Listener {

    private static final int REQ_ADD = 21;
    private static final String[] COLOR_KEYS = {"amber", "rose", "sky", "green", "violet"};
    private static final String[] COLOR_VALS = {"#FFF6CF", "#FFE6EC", "#E2EEFF", "#DFF6E6", "#ECE4FF"};
    private static final String[] PHRASES = {
            "请翻到第 12 页", "听力马上开始，请安静", "这道题再听一遍", "课间休息，可以离开座位"
    };

    private DeviceStore store;
    private List<Device> devices = new ArrayList<>();
    private RemoteBus bus;
    private LinearLayout deviceList;
    private EditText input;
    private TextView linkPill;
    private String color = "amber";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Set<String>> expectAck = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        store = new DeviceStore(this);
        devices = store.devices();
        deviceList = findViewById(R.id.deviceList);
        input = findViewById(R.id.input);
        linkPill = findViewById(R.id.linkPill);

        bus = new RemoteBus(this);
        bus.start();
        bus.watch(devices);

        input.setText(store.draft());
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                store.setDraft(s.toString());
            }
        });

        findViewById(R.id.btnAdd).setOnClickListener(v ->
                startActivityForResult(new Intent(this, AddDeviceActivity.class), REQ_ADD));
        findViewById(R.id.btnSend).setOnClickListener(v -> send());

        buildPhrases();
        buildColors();
        renderDevices();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ADD && resultCode == RESULT_OK && data != null) {
            Device d = new Device(
                    data.getStringExtra("id"),
                    data.getStringExtra("key"),
                    data.getStringExtra("name"));
            if (d.valid()) {
                boolean added = store.add(d);
                devices = store.devices();
                bus.watch(devices);
                renderDevices();
                Toast.makeText(this, added ? "已绑定 " + d.displayName() : "已更新 " + d.displayName(),
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        onLinkCount(bus == null ? 0 : bus.onlineLinks(), RemoteBus.BROKERS.length);
    }

    // ---------------- 设备列表 ----------------
    @SuppressLint("SetTextI18n")
    private void renderDevices() {
        deviceList.removeAllViews();
        findViewById(R.id.emptyHint).setVisibility(devices.isEmpty() ? View.VISIBLE : View.GONE);

        for (Device d : devices) {
            View row = LayoutInflater.from(this).inflate(R.layout.row_device, deviceList, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(7);
            row.setLayoutParams(lp);

            CheckBox cb = row.findViewById(R.id.cb);
            cb.setChecked(d.checked);
            cb.setOnCheckedChangeListener((v, checked) -> d.checked = checked);

            ((TextView) row.findViewById(R.id.name)).setText(d.displayName());
            TextView sub = row.findViewById(R.id.sub);
            sub.setText("设备号 " + d.id + " · " + (d.online ? "在线" : "离线"));
            sub.setTextColor(d.online ? 0xFF0F9D63 : 0xFF8B94A6);
            row.findViewById(R.id.dot).setBackgroundResource(
                    d.online ? R.drawable.bg_dot_green : R.drawable.bg_dot_gray);

            row.findViewById(R.id.del).setOnClickListener(v -> {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setMessage("移除「" + d.displayName() + "」？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("移除", (dd, w) -> {
                            store.remove(d.id);
                            bus.unwatch(d.channel());
                            devices = store.devices();
                            renderDevices();
                        })
                        .show();
            });
            deviceList.addView(row);
        }
    }

    // ---------------- 短语 / 颜色 ----------------
    private void buildPhrases() {
        LinearLayout box = findViewById(R.id.phrases);
        for (String p : PHRASES) {
            TextView t = new TextView(this);
            t.setText(p);
            t.setTextSize(11.5f);
            t.setTextColor(0xFF475063);
            t.setBackgroundResource(R.drawable.bg_chip);
            int ph = dp(8), pv = dp(5);
            t.setPadding(dp(12), pv, dp(12), pv);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(7);
            t.setLayoutParams(lp);
            t.setGravity(Gravity.CENTER);
            t.setOnClickListener(v -> {
                String cur = input.getText().toString().trim();
                input.setText(cur.isEmpty() ? p : cur + "\n" + p);
                input.setSelection(input.getText().length());
            });
            box.addView(t);
        }
    }

    private void buildColors() {
        LinearLayout box = findViewById(R.id.colors);
        for (int i = 0; i < COLOR_KEYS.length; i++) {
            final String key = COLOR_KEYS[i];
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(26), dp(26));
            lp.rightMargin = dp(10);
            dot.setLayoutParams(lp);
            dot.setTag(key);
            dot.setOnClickListener(v -> {
                color = key;
                paintColors();
            });
            box.addView(dot);
        }
        paintColors();
    }

    private void paintColors() {
        LinearLayout box = findViewById(R.id.colors);
        for (int i = 0; i < box.getChildCount(); i++) {
            View dot = box.getChildAt(i);
            String key = (String) dot.getTag();
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(android.graphics.Color.parseColor(COLOR_VALS[i]));
            boolean sel = key.equals(color);
            g.setStroke(dp(sel ? 2.5f : 1f),
                    sel ? android.graphics.Color.parseColor("#2F6BFF") : 0xFFE1E6EF);
            dot.setBackground(g);
        }
    }

    // ---------------- 发送 ----------------
    private void send() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            Toast.makeText(this, "先写点内容再发", Toast.LENGTH_SHORT).show();
            return;
        }
        List<Device> targets = new ArrayList<>();
        for (Device d : devices) {
            if (d.checked) {
                targets.add(d);
            }
        }
        if (targets.isEmpty()) {
            Toast.makeText(this, "请先勾选要发送的教室大屏", Toast.LENGTH_SHORT).show();
            return;
        }
        if (bus.onlineLinks() == 0) {
            Toast.makeText(this, "公网通道还没连上，请稍等几秒再试", Toast.LENGTH_LONG).show();
            return;
        }

        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try {
            JSONObject o = new JSONObject();
            o.put("v", 1);
            o.put("t", "note");
            o.put("id", id);
            o.put("text", text);
            o.put("color", color);
            o.put("ts", System.currentTimeMillis() / 1000);
            o.put("from", "老师的手机");
            Set<String> chans = new HashSet<>();
            for (Device d : targets) {
                chans.add(d.channel());
            }
            expectAck.put(id, chans);
            bus.send(targets, o.toString());
            Toast.makeText(this, "已发往 " + targets.size() + " 台设备", Toast.LENGTH_SHORT).show();
            input.setText("");
            store.setDraft("");

            final String noteId = id;
            final int total = chans.size();
            main.postDelayed(() -> {
                Set<String> left = expectAck.remove(noteId);
                if (left != null && !left.isEmpty()) {
                    Toast.makeText(MainActivity.this,
                            "已送达 " + (total - left.size()) + "/" + total + " 台（其余设备可能离线）",
                            Toast.LENGTH_LONG).show();
                }
            }, 3200);
        } catch (Exception e) {
            Toast.makeText(this, "发送失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ---------------- RemoteBus 回调 ----------------
    @Override
    public void onLinkCount(int online, int total) {
        main.post(() -> {
            linkPill.setText(online > 0 ? "通道正常 · " + online + " 路" : "连接中…");
            linkPill.setBackgroundResource(online > 0 ? R.drawable.bg_pill_green : R.drawable.bg_pill_gray);
            linkPill.setTextColor(online > 0 ? 0xFF0F9D63 : 0xFF6B7385);
        });
    }

    @Override
    public void onPresence(String channel, String name, boolean online) {
        main.post(() -> {
            boolean changed = false;
            for (Device d : devices) {
                if (d.channel().equals(channel)) {
                    if (d.online != online) {
                        changed = true;
                    }
                    d.online = online;
                    if ((d.name == null || d.name.trim().isEmpty()) && name != null && !name.isEmpty()) {
                        d.name = name;
                        store.save(devices);
                    }
                }
            }
            if (changed) {
                renderDevices();
            }
        });
    }

    @Override
    public void onAck(String channel, String noteId) {
        main.post(() -> {
            Set<String> left = expectAck.get(noteId);
            if (left != null && left.remove(channel) && left.isEmpty()) {
                expectAck.remove(noteId);
                Toast.makeText(this, "便签已送达并显示", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (bus != null) {
            bus.stop();
        }
        super.onDestroy();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
