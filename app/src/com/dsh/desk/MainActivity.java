package com.dsh.desk;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int BG = Color.parseColor("#0B1014");
    private static final int CARD = Color.parseColor("#151C24");
    private static final int TEXT = Color.parseColor("#E6EDF3");
    private static final int DIM = Color.parseColor("#8B98A5");
    private static final int GREEN = Color.parseColor("#3DDC84");
    private static final int RED = Color.parseColor("#FF6B6B");
    private static final int BLUE = Color.parseColor("#4DA3FF");
    private static final int AMBER = Color.parseColor("#FFC857");

    private static final String ESP_URL = "http://192.168.1.13/status";

    private static final int C_BATT = 0, C_RAM = 1, C_STO = 2, C_UP = 3,
            C_NET = 4, C_ASTR = 5, C_NAPCAT = 6, C_SSH = 7, C_ESP = 8;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.CHINA);
    private final SimpleDateFormat df = new SimpleDateFormat("M月d日 EEEE", Locale.CHINA);

    private TextView tvTime, tvDate, tvBatt, tvBattSub;
    private final TextView[] val = new TextView[9];
    private final TextView[] sub = new TextView[9];
    private TextView btnDefault;
    private TextView tvAcc;

    private volatile boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
        startExecService();
        requestTermuxPermission();
        ui.post(clockTick);
        ui.post(refreshTick);
    }

    // ---------------- UI ----------------

    private View buildUi() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(BG);
        col.setPadding(dp(18), dp(14), dp(18), dp(10));
        col.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { showApps(); return true; }
        });

        // header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        tvTime = text("--:--", 46, TEXT, true);
        tvDate = text("", 13, DIM, false);
        left.addView(tvTime);
        left.addView(tvDate);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        tvBatt = text("--%", 28, TEXT, true);
        tvBattSub = text("", 12, DIM, false);
        tvBatt.setGravity(Gravity.END);
        tvBattSub.setGravity(Gravity.END);
        right.addView(tvBatt);
        right.addView(tvBattSub);

        header.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(right, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(header);

        // service cards
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        String[] titles = {"电量", "内存", "存储", "运行时长", "网络", "AstrBot", "NapCat", "SSH", "ESP32 灯"};
        for (int i = 0; i < 9; i++) {
            View card = buildCard(i, titles[i]);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = dp(92);
            lp.columnSpec = GridLayout.spec(i % 2, 1, 1f);
            lp.setMargins(dp(5), dp(5), dp(5), dp(5));
            grid.addView(card, lp);
        }
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = dp(8);
        col.addView(grid, glp);

        // quick launch
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        buttons.addView(button("终端", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, TerminalActivity.class));
            }
        }));
        buttons.addView(button("AstrBot", new View.OnClickListener() {
            @Override public void onClick(View v) { openUrl("http://127.0.0.1:6185"); }
        }));
        buttons.addView(button("NapCat", new View.OnClickListener() {
            @Override public void onClick(View v) { openUrl("http://127.0.0.1:6099"); }
        }));
        buttons.addView(button("应用", new View.OnClickListener() {
            @Override public void onClick(View v) { showApps(); }
        }));
        buttons.addView(button("设置", new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
        }));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(12);
        col.addView(buttons, blp);

        tvAcc = text("", 12, AMBER, false);
        tvAcc.setPadding(0, dp(8), 0, 0);
        tvAcc.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openAccessibilitySettings(); }
        });
        col.addView(tvAcc);

        btnDefault = text("点这里把本应用设为默认桌面", 12, BLUE, false);
        btnDefault.setPadding(0, dp(10), 0, dp(4));
        btnDefault.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestHomeRole(); }
        });
        col.addView(btnDefault);

        return col;
    }

    private View buildCard(int index, String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(16));
        card.setBackground(g);
        card.setPadding(dp(13), dp(10), dp(13), dp(10));

        TextView t = text(title, 11, DIM, false);
        TextView v = text("--", 19, TEXT, true);
        TextView s = text("", 11, DIM, false);
        v.setPadding(0, dp(2), 0, 0);
        card.addView(t);
        card.addView(v);
        card.addView(s);
        val[index] = v;
        sub[index] = s;
        return card;
    }

    private TextView button(String label, View.OnClickListener l) {
        TextView b = text(label, 13, TEXT, false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(9), dp(14), dp(9));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(14));
        b.setBackground(g);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    // ---------------- clock ----------------

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            tvTime.setText(hm.format(now));
            tvDate.setText(df.format(now));
            ui.postDelayed(this, 1000);
        }
    };

    // ---------------- status ----------------

    private final Runnable refreshTick = new Runnable() {
        @Override public void run() {
            refreshAsync();
            ui.postDelayed(this, 5000);
        }
    };

    private void refreshAsync() {
        if (busy) return;
        busy = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final Snapshot s = collect();
                    ui.post(new Runnable() {
                        @Override public void run() { apply(s); }
                    });
                } catch (Throwable ignored) {
                } finally {
                    busy = false;
                }
            }
        }).start();
    }

    private static class Snapshot {
        int pct = -1;
        String charge = "";
        float temp = 0;
        long ramUsed, ramTotal, stoFree, stoTotal, uptime;
        String ip = "-";
        boolean astrbot, napcat, ssh;
        boolean esp;
        int espPct = -1;
        boolean espCharging;
        boolean espAllowed;
    }

    private Snapshot collect() {
        Snapshot s = new Snapshot();

        Intent bi = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bi != null) {
            int level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int t = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            s.pct = scale > 0 ? (int) (level * 100f / scale) : -1;
            s.temp = t / 10f;
            if (status == BatteryManager.BATTERY_STATUS_CHARGING) s.charge = "充电中";
            else if (status == BatteryManager.BATTERY_STATUS_FULL) s.charge = "已充满";
            else if (status == BatteryManager.BATTERY_STATUS_DISCHARGING) s.charge = "未充电";
            else s.charge = "未知";
        }

        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            s.ramTotal = mi.totalMem;
            s.ramUsed = mi.totalMem - mi.availMem;
        }

        try {
            StatFs sf = new StatFs(Environment.getDataDirectory().getPath());
            s.stoFree = sf.getAvailableBytes();
            s.stoTotal = sf.getTotalBytes();
        } catch (Throwable ignored) {
        }

        s.uptime = SystemClock.elapsedRealtime();
        s.ip = localIp();
        s.astrbot = portOpen(6185);
        s.napcat = portOpen(6099);
        s.ssh = portOpen(8022);

        try {
            HttpURLConnection c = (HttpURLConnection) new URL(ESP_URL).openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            try {
                if (c.getResponseCode() == 200) {
                    StringBuilder sb = new StringBuilder();
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    r.close();
                    JSONObject o = new JSONObject(sb.toString());
                    s.esp = true;
                    s.espPct = o.optInt("pct", -1);
                    s.espCharging = o.optBoolean("charging", false);
                    s.espAllowed = o.optBoolean("allow", true);
                }
            } finally {
                c.disconnect();
            }
        } catch (Throwable ignored) {
        }
        return s;
    }

    private void apply(Snapshot s) {
        // battery header
        if (s.pct >= 0) {
            tvBatt.setText(s.pct + "%");
            tvBatt.setTextColor(s.charge.equals("充电中") || s.charge.equals("已充满") ? GREEN : TEXT);
            tvBattSub.setText(s.charge);
        }

        // battery card
        val[C_BATT].setText(s.pct + "% · " + s.charge);
        sub[C_BATT].setText(String.format(Locale.US, "温度 %.1f°C", s.temp));

        // ram
        val[C_RAM].setText(String.format(Locale.US, "%.1f / %.1f GB", s.ramUsed / 1073741824.0, s.ramTotal / 1073741824.0));
        sub[C_RAM].setText(String.format(Locale.US, "已用 %d%%", s.ramTotal > 0 ? (int) (s.ramUsed * 100 / s.ramTotal) : 0));

        // storage
        val[C_STO].setText(String.format(Locale.US, "%.0f GB 可用", s.stoFree / 1073741824.0));
        sub[C_STO].setText(String.format(Locale.US, "共 %.0f GB", s.stoTotal / 1073741824.0));

        // uptime
        long days = s.uptime / 86400000L;
        long hours = (s.uptime % 86400000L) / 3600000L;
        long mins = (s.uptime % 3600000L) / 60000L;
        val[C_UP].setText(days > 0 ? (days + " 天 " + hours + " 小时") : (hours + " 小时 " + mins + " 分"));
        sub[C_UP].setText("自上次开机");

        // network
        val[C_NET].setText(s.ip);
        sub[C_NET].setText("局域网 IPv4");

        // services
        setService(C_ASTR, s.astrbot, 6185);
        setService(C_NAPCAT, s.napcat, 6099);
        setService(C_SSH, s.ssh, 8022);

        // esp32
        if (s.esp) {
            val[C_ESP].setText("在线");
            val[C_ESP].setTextColor(GREEN);
            String detail = (s.espPct >= 0 ? s.espPct + "% " : "") + (s.espCharging ? "充电中" : "未充电");
            if (!s.espAllowed) detail += " · 已判定停止";
            sub[C_ESP].setText(detail);
        } else {
            val[C_ESP].setText("离线");
            val[C_ESP].setTextColor(RED);
            sub[C_ESP].setText("192.168.1.13");
        }

        updateDefaultButton();
        updateAcc();
    }

    private void updateAcc() {
        if (tvAcc == null) return;
        boolean on = DeskAccessibilityService.ready();
        tvAcc.setText(on ? "无障碍：已开启（UI 控制可用）"
                : "无障碍：未开启 · 点这里开启（UI 控制需要）");
        tvAcc.setTextColor(on ? GREEN : AMBER);
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Throwable ignored) {
        }
    }

    private void setService(int idx, boolean on, int port) {
        val[idx].setText(on ? "在线" : "离线");
        val[idx].setTextColor(on ? GREEN : RED);
        sub[idx].setText("端口 " + port);
    }

    // ---------------- helpers ----------------

    private boolean portOpen(int port) {
        Socket so = new Socket();
        try {
            so.connect(new InetSocketAddress("127.0.0.1", port), 400);
            return true;
        } catch (Throwable e) {
            return false;
        } finally {
            try { so.close(); } catch (Throwable ignored) { }
        }
    }

    private String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName();
                if (n == null || (!n.startsWith("wlan") && !n.startsWith("eth"))) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "-";
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable ignored) {
        }
    }

    private void showApps() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        final List<ResolveInfo> list = getPackageManager().queryIntentActivities(i, 0);
        Collections.sort(list, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo a, ResolveInfo b) {
                return a.loadLabel(getPackageManager()).toString()
                        .compareToIgnoreCase(b.loadLabel(getPackageManager()).toString());
            }
        });
        final List<String> names = new ArrayList<>();
        for (ResolveInfo r : list) {
            names.add(r.loadLabel(getPackageManager()).toString());
        }
        new AlertDialog.Builder(this)
                .setTitle("所有应用")
                .setItems(names.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        ResolveInfo r = list.get(which);
                        Intent launch = new Intent(Intent.ACTION_MAIN);
                        launch.addCategory(Intent.CATEGORY_LAUNCHER);
                        launch.setClassName(r.activityInfo.packageName, r.activityInfo.name);
                        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try { startActivity(launch); } catch (Throwable ignored) { }
                    }
                })
                .show();
    }

    private boolean isDefaultHome() {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo r = getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        return r != null && getPackageName().equals(r.activityInfo.packageName);
    }

    private void updateDefaultButton() {
        if (btnDefault == null) return;
        btnDefault.setVisibility(isDefaultHome() ? View.GONE : View.VISIBLE);
    }

    private void startExecService() {
        try {
            Intent svc = new Intent(this, ExecService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
            else startService(svc);
        } catch (Throwable ignored) {
        }
    }

    private void requestTermuxPermission() {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                String p = "com.termux.permission.RUN_COMMAND";
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{p}, 7);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void requestHomeRole() {
        if (Build.VERSION.SDK_INT >= 29) {
            RoleManager rm = (RoleManager) getSystemService(RoleManager.class);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 100);
                return;
            }
        }
        try {
            startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
    }

    @Override
    public void onBackPressed() {
        // launcher: 不退出
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
