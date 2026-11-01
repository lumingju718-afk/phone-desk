package com.dsh.desk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import java.security.SecureRandom;

/** 前台服务：常驻 HTTP 控制端，并提供终端执行能力 */
public class ExecService extends Service {

    public static final int PORT = 8089;
    public static final String PREFS = "desk";
    public static final String CHANNEL = "desk_exec";

    private HttpExecServer server;

    public static String ensureToken(SharedPreferences sp) {
        String t = sp.getString("token", null);
        if (t == null || t.length() < 6) {
            t = "dshdesk1";
            sp.edit().putString("token", t).apply();
        }
        return t;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        String token = ensureToken(getSharedPreferences(PREFS, MODE_PRIVATE));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("服务器桌面 · 远程终端已开启")
                .setContentText("端口 " + PORT + " · token " + token)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
        startForeground(1, n);
        server = new HttpExecServer(this, PORT, token);
        server.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) server.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "远程终端", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }
}
