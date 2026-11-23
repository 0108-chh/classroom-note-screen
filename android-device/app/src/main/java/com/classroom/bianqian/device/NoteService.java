package com.classroom.bianqian.device;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

/**
 * 常驻前台服务：一直挂着 MQTT，收到便签就弹浮层。
 * 只有用户在应用里点「停止」或系统强杀才会结束。
 */
public class NoteService extends Service implements Bus.Listener {

    public static final String ACTION_START = "com.classroom.bianqian.device.START";
    public static final String ACTION_STOP = "com.classroom.bianqian.device.STOP";
    private static final String CH_ID = "note_screen_service";
    private static final int NOTIF_ID = 1001;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Bus bus;
    private NoteOverlay overlay;
    private Prefs prefs;
    private PowerManager.WakeLock wakeLock;
    private static boolean running;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        overlay = new NoteOverlay(this);
        running = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            prefs.setServiceEnabled(false);
            stopSelf();
            return START_NOT_STICKY;
        }
        ensureForeground("正在接收便签");
        startBus();
        return START_STICKY;
    }

    private void ensureForeground(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "便签接收服务", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setDescription("保持后台连接，接收老师发来的便签");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, NoteService.class).setAction(ACTION_STOP);
        PendingIntent ps = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new NotificationCompat.Builder(this, CH_ID)
                .setContentTitle("班级便签屏")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_note)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pi)
                .addAction(0, "停止接收", ps)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void startBus() {
        if (bus != null) {
            return;
        }
        bus = new Bus(prefs.deviceId(), prefs.deviceKey(), this);
        bus.start(prefs.name());
        acquireWake();
    }

    private void acquireWake() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NoteScreen::mqtt");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Exception ignored) {
        }
    }

    private void updateNotification() {
        if (bus == null) {
            return;
        }
        int on = bus.onlineCount();
        String text = on > 0
                ? "通道正常 · " + on + " 路在线 · " + prefs.name()
                : "正在连接公网通道…";
        ensureForeground(text);
    }

    @Override
    public void onNote(JSONObject note) {
        main.post(() -> overlay.push(note));
    }

    @Override
    public void onStateChanged() {
        main.post(this::updateNotification);
    }

    @Override
    public void onDestroy() {
        running = false;
        if (bus != null) {
            bus.stop();
            bus = null;
        }
        if (overlay != null) {
            overlay.detach();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 从最近任务划掉也要继续跑
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
