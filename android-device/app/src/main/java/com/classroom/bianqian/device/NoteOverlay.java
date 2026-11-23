package com.classroom.bianqian.device;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;

/**
 * 悬浮便签：以系统浮层形式盖在其它应用之上，老师发的文字原样显示。
 */
public final class NoteOverlay {

    private final Context ctx;
    private final WindowManager wm;
    private View root;
    private WindowManager.LayoutParams lp;
    private final ArrayList<JSONObject> queue = new ArrayList<>();
    private int idx = -1;

    private float downRawX, downRawY;
    private int downX, downY;

    public NoteOverlay(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.wm = (WindowManager) this.ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    public static boolean canShow(Context ctx) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx);
    }

    public boolean isShowing() {
        return root != null;
    }

    public void push(JSONObject note) {
        queue.add(note);
        while (queue.size() > 10) {
            queue.remove(0);
        }
        idx = queue.size() - 1;
        render();
    }

    public void nav(int step) {
        if (queue.isEmpty()) {
            return;
        }
        idx = Math.max(0, Math.min(queue.size() - 1, idx + step));
        render();
    }

    public void closeCurrent() {
        if (queue.isEmpty()) {
            return;
        }
        queue.remove(idx);
        if (queue.isEmpty()) {
            detach();
            idx = -1;
            return;
        }
        idx = Math.min(idx, queue.size() - 1);
        render();
    }

    public void detach() {
        if (root != null) {
            try {
                wm.removeView(root);
            } catch (Exception ignored) {
            }
            root = null;
        }
        queue.clear();
        idx = -1;
    }

    private int colorOf(String name) {
        switch (name == null ? "" : name) {
            case "rose":
                return 0xFFFFE6EC;
            case "sky":
                return 0xFFE2EEFF;
            case "green":
                return 0xFFDFF6E6;
            case "violet":
                return 0xFFECE4FF;
            default:
                return 0xFFFFF6CF;
        }
    }

    private int edgeOf(String name) {
        switch (name == null ? "" : name) {
            case "rose":
                return 0xFFEFC3CE;
            case "sky":
                return 0xFFB9D3F7;
            case "green":
                return 0xFFB4E2C5;
            case "violet":
                return 0xFFCBBAEE;
            default:
                return 0xFFF0DCA2;
        }
    }

    private void render() {
        if (queue.isEmpty() || !canShow(ctx)) {
            return;
        }
        JSONObject note = queue.get(idx);
        if (root == null) {
            build(note);
        }
        String color = note.optString("color", "amber");
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(colorOf(color));
        bg.setCornerRadius(dp(16));
        bg.setStroke(Math.max(1, (int) dp(1)), edgeOf(color));
        root.setBackground(bg);

        ((TextView) root.findViewById(R.id.noteText)).setText(note.optString("text", ""));
        String from = note.optString("from", "");
        ((TextView) root.findViewById(R.id.meta)).setText(from.isEmpty() ? "来自老师的手机" : "来自 " + from);
        ((TextView) root.findViewById(R.id.time)).setText(timeText(note.optLong("ts", 0)));

        LinearLayout nav = root.findViewById(R.id.nav);
        if (queue.size() > 1) {
            nav.setVisibility(View.VISIBLE);
            ((TextView) root.findViewById(R.id.counter))
                    .setText((idx + 1) + "/" + queue.size());
        } else {
            nav.setVisibility(View.GONE);
        }
        root.requestLayout();
    }

    private void build(JSONObject note) {
        root = LayoutInflater.from(ctx).inflate(R.layout.overlay_note, null);
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int w = Math.min(dm.widthPixels - (int) dp(40), (int) dp(340));
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(
                w,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        lp.x = dm.widthPixels - w - (int) dp(14);
        lp.y = (int) dp(72);

        root.findViewById(R.id.btnClose).setOnClickListener(v -> closeCurrent());
        root.findViewById(R.id.btnPrev).setOnClickListener(v -> nav(-1));
        root.findViewById(R.id.btnNext).setOnClickListener(v -> nav(1));

        View bar = root.findViewById(R.id.dragBar);
        bar.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = e.getRawX();
                    downRawY = e.getRawY();
                    downX = lp.x;
                    downY = lp.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    lp.x = downX + (int) (e.getRawX() - downRawX);
                    lp.y = downY + (int) (e.getRawY() - downRawY);
                    try {
                        wm.updateViewLayout(root, lp);
                    } catch (Exception ignored) {
                    }
                    return true;
                default:
                    return false;
            }
        });

        try {
            wm.addView(root, lp);
        } catch (Exception e) {
            root = null;
        }
    }

    private String timeText(long ts) {
        Calendar c = Calendar.getInstance();
        if (ts > 0) {
            c.setTimeInMillis(ts > 100000000000L ? ts : ts * 1000);
        }
        int h = c.get(Calendar.HOUR_OF_DAY);
        int m = c.get(Calendar.MINUTE);
        return (h < 10 ? "0" : "") + h + ":" + (m < 10 ? "0" : "") + m;
    }

    private float dp(float v) {
        return v * ctx.getResources().getDisplayMetrics().density;
    }
}
