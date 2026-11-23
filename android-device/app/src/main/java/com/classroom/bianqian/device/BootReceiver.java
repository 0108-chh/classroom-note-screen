package com.classroom.bianqian.device;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 开机自启，保证教室大屏断电恢复后自动继续接收便签。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        Prefs p = new Prefs(context);
        if (!p.bootEnabled() || !p.serviceEnabled()) {
            return;
        }
        Intent svc = new Intent(context, NoteService.class).setAction(NoteService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc);
        } else {
            context.startService(svc);
        }
    }
}
