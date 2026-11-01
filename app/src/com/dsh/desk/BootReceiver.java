package com.dsh.desk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 开机自启远程终端服务 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (i == null || !Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) return;
        Intent svc = new Intent(c, ExecService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(svc);
            else c.startService(svc);
        } catch (Throwable ignored) {
        }
    }
}
