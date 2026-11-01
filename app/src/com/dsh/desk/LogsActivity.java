package com.dsh.desk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 日志页：查看 AstrBot / NapCat 日志，并可启动 / 重启服务 */
public class LogsActivity extends Activity {

    private static final int BG = Color.parseColor("#0B1014");
    private static final int CARD = Color.parseColor("#151C24");
    private static final int TEXT = Color.parseColor("#E6EDF3");
    private static final int DIM = Color.parseColor("#8B98A5");
    private static final int GREEN = Color.parseColor("#3DDC84");
    private static final int RED = Color.parseColor("#FF6B6B");
    private static final int BLUE = Color.parseColor("#4DA3FF");
    private static final int AMBER = Color.parseColor("#FFC857");

    private static final String ROOTFS =
            "/data/data/com.termux/files/usr/var/lib/proot-distro/containers/ubuntu-base/rootfs";
    private static final String LOG_NAPCAT = ROOTFS + "/root/napcat.log";
    private static final String LOG_ASTRBOT = ROOTFS + "/root/astrbot.log";

    /** 会话内缓存，切换标签/重开页面时先显示上次内容 */
    private static final java.util.Map<String, String> CACHE = new java.util.HashMap<>();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat hm = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);

    private TextView tvStatus, tvLog, tabNapcat, tabAstrbot, pauseBtn;
    private String current = SvcControl.NAPCAT;
    private boolean paused = false;
    private boolean busy = false;
    private String lastLog = "";

    private String token;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        token = ExecService.ensureToken(getSharedPreferences(ExecService.PREFS, Context.MODE_PRIVATE));
        setContentView(buildUi());
        ui.post(refreshTick);
    }

    private View buildUi() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(BG);
        col.setPadding(dp(12), dp(10), dp(12), dp(8));

        // header: back + title
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("← 返回", 13, BLUE, false);
        back.setPadding(dp(4), dp(4), dp(14), dp(4));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        head.addView(back);
        head.addView(text("服务日志", 18, TEXT, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(head);

        // tabs
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(0, dp(6), 0, 0);
        tabAstrbot = tab("AstrBot", SvcControl.ASTRBOT);
        tabNapcat = tab("NapCat", SvcControl.NAPCAT);
        tabs.addView(tabAstrbot);
        tabs.addView(tabNapcat);
        col.addView(tabs);

        tvStatus = text("", 11, DIM, false);
        tvStatus.setPadding(dp(4), dp(6), dp(4), dp(6));
        col.addView(tvStatus);

        // actions
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(actionButton("启动", new View.OnClickListener() {
            @Override public void onClick(View v) { doStart(); }
        }));
        actions.addView(actionButton("重启", new View.OnClickListener() {
            @Override public void onClick(View v) { doRestart(); }
        }));
        actions.addView(actionButton("刷新", new View.OnClickListener() {
            @Override public void onClick(View v) { refreshAsync(true); }
        }));
        pauseBtn = actionButton("暂停", new View.OnClickListener() {
            @Override public void onClick(View v) { togglePause(); }
        });
        actions.addView(pauseBtn);
        col.addView(actions);

        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#07090C"));
        tvLog = new TextView(this);
        tvLog.setTextColor(Color.parseColor("#9BE3A8"));
        tvLog.setTextSize(10);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setPadding(dp(8), dp(8), dp(8), dp(8));
        scroll.addView(tvLog);
        col.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return col;
    }

    private TextView tab(String label, final String name) {
        TextView t = text(label, 13, DIM, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(18), dp(8), dp(18), dp(8));
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchTo(name); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView actionButton(String label, View.OnClickListener l) {
        TextView b = text(label, 13, TEXT, false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), dp(9), dp(10), dp(9));
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(12));
        b.setBackground(g);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
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

    private void switchTo(String name) {
        if (current.equals(name)) return;
        current = name;
        lastLog = "";
        tvLog.setText("");
        String cached = CACHE.get(name);
        if (cached != null) {
            lastLog = cached;
            tvLog.setText(cached);
        }
        tvStatus.setText("正在读取日志…");
        tvStatus.setTextColor(AMBER);
        refreshAsync(true);
    }

    private void togglePause() {
        paused = !paused;
        pauseBtn.setText(paused ? "继续" : "暂停");
        pauseBtn.setTextColor(paused ? AMBER : TEXT);
        if (!paused) refreshAsync(true);
    }

    private void doStart() {
        if (busy) return;
        if (SvcControl.isRunning(current)) {
            toast(displayName(current) + " 已在运行");
            return;
        }
        runAction("start");
    }

    private void doRestart() {
        if (busy) return;
        new AlertDialog.Builder(this)
                .setTitle("重启 " + displayName(current))
                .setMessage("会中断当前服务，确定重启吗？")
                .setPositiveButton("重启", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { runAction("restart"); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void runAction(final String action) {
        busy = true;
        tvStatus.setText("正在" + ("restart".equals(action) ? "重启" : "启动")
                + " " + displayName(current) + " …");
        tvStatus.setTextColor(AMBER);
        new Thread(new Runnable() {
            @Override public void run() {
                final Shell.Result r = SvcControl.exec(LogsActivity.this, current, action, token, ExecService.PORT);
                ui.post(new Runnable() {
                    @Override public void run() {
                        busy = false;
                        String msg;
                        if (r.code == 0) {
                            msg = "已发送" + ("restart".equals(action) ? "重启" : "启动")
                                    + "命令，等待服务起来…";
                        } else {
                            msg = "命令失败: " + r.out.trim();
                        }
                        tvStatus.setText(msg + "  " + hm.format(new Date()));
                        tvStatus.setTextColor(r.code == 0 ? GREEN : RED);
                        toast(msg);
                        ui.postDelayed(new Runnable() {
                            @Override public void run() { refreshAsync(true); }
                        }, 6000);
                    }
                });
            }
        }).start();
    }

    // ---------------- refresh ----------------

    private final Runnable refreshTick = new Runnable() {
        @Override public void run() {
            refreshAsync(false);
            ui.postDelayed(this, 3000);
        }
    };

    private void refreshAsync(final boolean manual) {
        if (paused && !manual) return;
        String cached = CACHE.get(current);
        if (cached != null && tvLog.getText().length() == 0) {
            lastLog = cached;
            tvLog.setText(cached);
        }
        if (tvStatus.getText().length() == 0) {
            tvStatus.setText("正在读取日志…");
            tvStatus.setTextColor(AMBER);
        }
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean running = SvcControl.isRunning(current);
                final String path = SvcControl.NAPCAT.equals(current) ? LOG_NAPCAT : LOG_ASTRBOT;
                final Shell.Result r = TermuxRunner.run(LogsActivity.this,
                        "tail -n 300 '" + path + "'", 15, token, ExecService.PORT);
                ui.post(new Runnable() {
                    @Override public void run() { apply(running, r, path); }
                });
            }
        }).start();
    }

    private void apply(boolean running, Shell.Result r, String path) {
        String stamp = hm.format(new Date());
        tvStatus.setText((running ? "● 运行中" : "○ 已停止")
                + " · " + displayName(current) + " · 刷新于 " + stamp
                + (paused ? " · 已暂停" : ""));
        tvStatus.setTextColor(running ? GREEN : RED);

        String body;
        if (r.code != 0) {
            body = "[读取失败 code=" + r.code + "]\n" + r.out
                    + "\n路径: " + path
                    + "\n提示: 需要 Termux 桥接权限，且服务至少启动过一次";
        } else {
            body = stripAnsi(r.out);
            if (body == null || body.trim().isEmpty()) {
                body = "(日志为空)";
            }
        }
        CACHE.put(current, body);
        if (!body.equals(lastLog)) {
            boolean atBottom = isAtBottom();
            lastLog = body;
            tvLog.setText(body);
            if (atBottom || !running) {
                scroll.post(new Runnable() {
                    @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
                });
            }
        }
        // 高亮标签
        boolean nap = SvcControl.NAPCAT.equals(current);
        tabNapcat.setTextColor(nap ? TEXT : DIM);
        tabAstrbot.setTextColor(nap ? DIM : TEXT);
        tabNapcat.setTypeface(nap ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        tabAstrbot.setTypeface(nap ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD);
    }

    private boolean isAtBottom() {
        if (scroll == null) return true;
        View child = scroll.getChildAt(0);
        if (child == null) return true;
        return scroll.getScrollY() + scroll.getHeight() >= child.getMeasuredHeight() - 80;
    }

    private static String displayName(String name) {
        return SvcControl.NAPCAT.equals(name) ? "NapCat" : "AstrBot";
    }

    /** 去掉日志里的 ANSI 颜色控制码 */
    private static String stripAnsi(String s) {
        if (s == null) return "";
        return s.replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
