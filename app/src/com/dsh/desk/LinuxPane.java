package com.dsh.desk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 桌面最左页：Linux 容器程序 —— 看状态 + 点一下启动/重启，长按停止 */
public class LinuxPane {

    private static final int BG = Color.parseColor("#05080A");
    private static final int FG = Color.parseColor("#C6FFD2");
    private static final int GREEN = Color.parseColor("#33FF66");
    private static final int DIM = Color.parseColor("#2E7D4F");
    private static final int RED = Color.parseColor("#FF5555");
    private static final int AMBER = Color.parseColor("#FFD166");

    private static final long REFRESH_MS = 5000;
    private static final String PS_CMD =
            "ps -eo pid,rss,args | grep -Ei 'astrbot|napcat|dsh|proot|node|python3|cloudflared' " +
            "| grep -v grep | grep -v 'while true; do cloudflared' | head -10";

    private static final String DSH_START =
            "setsid nohup proot-distro login ubuntu-base -- bash -lc " +
            "'export PATH=/usr/local/bin:$PATH; exec dsh web --host 127.0.0.1 --port 6200 --no-open' " +
            "> ~/dsh-web.log 2>&1 < /dev/null &";
    private static final String DSH_STOP = "pkill -f 'dsh web' ; pkill -f 'dsh.*--port 6200'";

    /** kind: S = 走 SvcControl(astrbot/napcat)，C = 自定义命令，V = 只看 */
    private static final String[][] DEF = {
            {"astrbot", "S", "6185"},
            {"napcat", "S", "6099"},
            {"dsh", "C", "6200"},
            {"voice_desk", "V", "8091"},
    };

    private final Activity act;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout list;
    private TextView clock, psOut;
    private View[] rows = new View[DEF.length];
    private TextView[] rowLabels = new TextView[DEF.length];
    private boolean[] up = new boolean[DEF.length];
    private volatile boolean running = false;
    private volatile boolean busy = false;

    LinuxPane(Activity a) {
        this.act = a;
    }

    View create() {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(BG);
        col.setPadding(dp(12), dp(10), dp(12), dp(6));

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        clock = mono("--:--:--", 13, GREEN, true);
        clock.setPadding(dp(2), dp(2), dp(10), dp(2));
        head.addView(clock);
        head.addView(mono("linux@container:~#", 11, DIM, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(head);

        View sep = new View(act);
        sep.setBackgroundColor(DIM);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        slp.topMargin = dp(4);
        slp.bottomMargin = dp(6);
        col.addView(sep, slp);

        ScrollView sc = new ScrollView(act);
        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);

        body.addView(mono("$ programs", 11, DIM, false));
        list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < DEF.length; i++) {
            rows[i] = buildRow(i);
            list.addView(rows[i]);
        }
        body.addView(list);

        TextView run = mono("$ run command…", 12, AMBER, true);
        run.setPadding(dp(2), dp(10), dp(2), dp(8));
        run.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { promptCommand(); }
        });
        body.addView(run);

        TextView hint = mono("# tap=start/restart   long-press=stop", 10, DIM, false);
        hint.setPadding(dp(2), 0, dp(2), dp(10));
        body.addView(hint);

        body.addView(mono("$ ps -eo pid,rss,args", 10, DIM, false));
        psOut = mono("# ...", 10, FG, false);
        psOut.setLineSpacing(dp(2), 1f);
        body.addView(psOut);

        sc.addView(body);
        col.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView foot = mono("← 左滑到头  右滑看状态 →", 10, DIM, false);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(6), 0, 0);
        col.addView(foot);

        return col;
    }

    private View buildRow(final int i) {
        final String[] d = DEF[i];
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(9), dp(2), dp(9));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { start(i); }
        });
        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { stop(i); return true; }
        });

        TextView dot = mono("\u25cf", 12, RED, false);
        dot.setPadding(0, 0, dp(8), 0);
        row.addView(dot);

        TextView name = mono(d[0], 13, FG, true);
        name.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(name);

        TextView st = mono(":" + d[2] + "  ?", 11, DIM, false);
        rowLabels[i] = st;
        row.addView(st);

        return row;
    }

    void start() {
        running = true;
        ui.post(tick);
    }

    void stop() {
        running = false;
        ui.removeCallbacksAndMessages(null);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            java.util.Calendar c = java.util.Calendar.getInstance();
            clock.setText(String.format(java.util.Locale.CHINA, "%02d:%02d:%02d",
                    c.get(java.util.Calendar.HOUR_OF_DAY),
                    c.get(java.util.Calendar.MINUTE),
                    c.get(java.util.Calendar.SECOND)));
            refresh();
            ui.postDelayed(this, REFRESH_MS);
        }
    };

    private void refresh() {
        new Thread(new Runnable() {
            @Override public void run() {
                for (int i = 0; i < DEF.length; i++) {
                    boolean on = portOpen(Integer.parseInt(DEF[i][2]));
                    final int idx = i;
                    final boolean val = on;
                    ui.post(new Runnable() {
                        @Override public void run() {
                            up[idx] = val;
                            updateRow(idx);
                        }
                    });
                }
                String ps = termux(PS_CMD, 20);
                final String f = ps;
                ui.post(new Runnable() {
                    @Override public void run() { if (psOut != null) psOut.setText(renderPs(f)); }
                });
            }
        }).start();
    }

    private void updateRow(int i) {
        TextView t = rowLabels[i];
        if (t == null) return;
        boolean on = up[i];
        t.setText(":" + DEF[i][2] + "  " + (on ? "UP" : "DOWN"));
        t.setTextColor(on ? GREEN : RED);
    }

    private void start(final int i) {
        final String[] d = DEF[i];
        if ("V".equals(d[1])) {
            Toast.makeText(act, d[0] + " 由 voice_desk 脚本托管（关机自启）", Toast.LENGTH_SHORT).show();
            return;
        }
        if (up[i]) {
            if ("dsh".equals(d[0])) { openDsh(); }
            else Toast.makeText(act, d[0] + " 运行中（长按停止）", Toast.LENGTH_SHORT).show();
            return;
        }
        if (busy) { Toast.makeText(act, "上一个操作还没完成", Toast.LENGTH_SHORT).show(); return; }
        busy = true;
        Toast.makeText(act, "启动 " + d[0] + " …", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                String out;
                if ("S".equals(d[1])) {
                    Shell.Result r = SvcControl.exec(ctx(), d[0], "restart", token(), ExecService.PORT);
                    out = r == null ? "" : r.out;
                } else {
                    out = termux(d[0].equals("dsh") ? DSH_START : "echo no-cmd", 30);
                }
                final String o = out;
                ui.post(new Runnable() {
                    @Override public void run() {
                        busy = false;
                        Toast.makeText(act, d[0] + " 已触发，稍后刷新状态", Toast.LENGTH_SHORT).show();
                        ui.postDelayed(new Runnable() {
                            @Override public void run() { refresh(); }
                        }, 3000);
                        if (o != null && o.trim().length() > 0 && o.trim().contains("error")) {
                            showOutput(d[0] + " 输出", o);
                        }
                    }
                });
            }
        }).start();
    }

    /** dsh 运行中：从 ~/dsh-web.log 取带 token 的地址，用手机浏览器打开 */
    private void openDsh() {
        Toast.makeText(act, "打开 DSH …", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                String out = termux("tail -1 ~/dsh-web.log 2>/dev/null", 15);
                String url = null;
                if (out != null) {
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("http://[^\\s]+").matcher(out);
                    if (m.find()) url = m.group();
                }
                final String u = url;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (u == null) {
                            Toast.makeText(act, "还没拿到 DSH 地址（刚启动需等 30-60 秒）", Toast.LENGTH_LONG).show();
                            return;
                        }
                        try {
                            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
                            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            act.startActivity(it);
                        } catch (Throwable e) {
                            showOutput("DSH 地址", u);
                        }
                    }
                });
            }
        }).start();
    }

    private void stop(final int i) {
        final String[] d = DEF[i];
        if ("V".equals(d[1])) return;
        new AlertDialog.Builder(act)
                .setTitle("停止 " + d[0] + "?")
                .setPositiveButton("停止", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dia, int w) {
                        new Thread(new Runnable() {
                            @Override public void run() {
                                if ("S".equals(d[1])) {
                                    SvcControl.exec(ctx(), d[0], "stop", token(), ExecService.PORT);
                                } else {
                                    termux(DSH_STOP, 20);
                                }
                                ui.post(new Runnable() {
                                    @Override public void run() {
                                        Toast.makeText(act, d[0] + " 已停止", Toast.LENGTH_SHORT).show();
                                        ui.postDelayed(new Runnable() {
                                            @Override public void run() { refresh(); }
                                        }, 2000);
                                    }
                                });
                            }
                        }).start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void promptCommand() {
        final EditText e = new EditText(act);
        e.setHint("例如: proot-distro login ubuntu-base -- uname -a");
        e.setHintTextColor(DIM);
        e.setTextColor(FG);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setTypeface(Typeface.MONOSPACE);
        new AlertDialog.Builder(act)
                .setTitle("在 Termux 里执行")
                .setView(e)
                .setPositiveButton("执行", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dia, int w) {
                        final String cmd = e.getText().toString().trim();
                        if (cmd.isEmpty()) return;
                        new Thread(new Runnable() {
                            @Override public void run() {
                                final String out = termux(cmd, 40);
                                ui.post(new Runnable() {
                                    @Override public void run() { showOutput("$ " + cmd, out); }
                                });
                            }
                        }).start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showOutput(String title, String out) {
        TextView t = mono(out == null || out.trim().isEmpty() ? "(no output)" : out, 11, FG, false);
        t.setTextIsSelectable(true);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        ScrollView sc = new ScrollView(act);
        sc.addView(t);
        new AlertDialog.Builder(act).setTitle(title).setView(sc)
                .setPositiveButton("关闭", null).show();
    }

    private String renderPs(String ps) {
        if (ps == null || ps.trim().isEmpty()) return "# no matching process";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String line : ps.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            String[] p = t.split("\\s+", 3);
            long rss = 0;
            try { rss = Long.parseLong(p.length > 1 ? p[1] : "0"); } catch (Throwable ignored) {}
            String cmd = p.length > 2 ? p[2] : "";
            if (cmd.length() > 40) cmd = cmd.substring(0, 40) + "…";
            sb.append(String.format(java.util.Locale.CHINA, "%-6s %4dM %s\n", p[0], rss / 1024, cmd));
            n++;
        }
        sb.append("-- ").append(n).append(" --");
        return sb.toString();
    }

    // ---------------- 工具 ----------------

    private Context ctx() { return act.getApplicationContext(); }

    private String token() {
        return ExecService.ensureToken(ctx().getSharedPreferences(ExecService.PREFS, Context.MODE_PRIVATE));
    }

    private String termux(String cmd, int timeoutSec) {
        try {
            Shell.Result r = TermuxRunner.run(ctx(), cmd, timeoutSec, token(), ExecService.PORT);
            return r == null ? "" : r.out;
        } catch (Throwable e) {
            return "error: " + e.getMessage();
        }
    }

    private boolean portOpen(int port) {
        java.net.Socket s = new java.net.Socket();
        try {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 350);
            return true;
        } catch (Throwable e) {
            return false;
        } finally {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    private TextView mono(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }
}
