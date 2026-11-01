package com.dsh.desk;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.BatteryManager;
import android.os.StatFs;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Collections;

/** 供网页/ESP32 用的状态聚合接口 */
public class StatusApi {

    public static JSONObject collect(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            // 电量
            Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            JSONObject bat = new JSONObject();
            if (b != null) {
                int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                int temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                bat.put("pct", scale > 0 ? Math.round(level * 100f / scale) : -1);
                bat.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL);
                bat.put("temp_c", temp / 10.0);
            }
            o.put("battery", bat);

            // 内存
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            JSONObject ram = new JSONObject();
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                ram.put("used_mb", (mi.totalMem - mi.availMem) / (1024 * 1024));
                ram.put("total_mb", mi.totalMem / (1024 * 1024));
            }
            o.put("ram", ram);

            // 存储
            StatFs sf = new StatFs("/data");
            JSONObject sto = new JSONObject();
            sto.put("avail_gb", Math.round(sf.getAvailableBytes() / 1073741824.0 * 10) / 10.0);
            sto.put("total_gb", Math.round(sf.getTotalBytes() / 1073741824.0 * 10) / 10.0);
            o.put("storage", sto);

            o.put("uptime_s", SystemClock.elapsedRealtime() / 1000);
            o.put("ip", ipv4());
            o.put("now", System.currentTimeMillis());

            JSONObject svc = new JSONObject();
            svc.put("astrbot", portOpen(6185));
            svc.put("napcat", portOpen(6099));
            svc.put("ssh", portOpen(8022));
            o.put("services", svc);
            o.put("ok", true);
        } catch (Throwable e) {
            try { o.put("ok", false); o.put("error", String.valueOf(e.getMessage())); }
            catch (Throwable ignored) { }
        }
        return o;
    }

    public static boolean portOpen(int port) {
        Socket s = new Socket();
        try {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 350);
            return true;
        } catch (Throwable e) {
            return false;
        } finally {
            try { s.close(); } catch (Throwable ignored) { }
        }
    }

    public static String ipv4() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) { }
        return "";
    }
}
