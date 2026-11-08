package com.dsh.desk;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** 桌面最左页：Linux 容器里正在跑的程序（经 Termux 桥接取 ps） */
public class LinuxPane {

    private static final int BG = Color.parseColor("#05080A");
    private static final int FG = Color.parseColor("#C6FFD2");
    private static final int GREEN = Color.parseColor("#33FF66");
    private static final int DIM = Color.parseColor("#2E7D4F");
    private static final int AMBER = Color.parseColor("#FFD166");

    private static final long REFRESH_MS = 8000;
    private static final String CMD =
            "ps -eo pid,rss,args | grep -Ei 'astrbot|napcat|dsh|proot|node|python3|cloudflared' " +
            "| grep -v grep | head -14";

    private final Activity act;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView clock, out;
    private volatile boolean running = false;
    private volatile boolean fetching = false;
    private String body = "# reading ps ...";

    LinuxPane(Activity a) {
        this.act = a;
    }

    View create() {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(BG);
        col.setPadding(dp(12), dp(10), dp(12), dp(8));

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
        out = mono(body, 11, FG, false);
        out.setLineSpacing(dp(3), 1f);
        sc.addView(out);
        col.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView foot = mono("$ ps -eo pid,rss,args", 10, DIM, false);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(6), 0, 0);
        col.addView(foot);

        return col;
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
            fetch();
            ui.postDelayed(this, REFRESH_MS);
        }
    };

    private void fetch() {
        if (fetching) return;
        fetching = true;
        new Thread(new Runnable() {
            @Override public void run() {
                String result = null;
                try {
                    Context ctx = act.getApplicationContext();
                    String token = ExecService.ensureToken(
                            ctx.getSharedPreferences(ExecService.PREFS, Context.MODE_PRIVATE));
                    Shell.Result r = TermuxRunner.run(ctx, CMD, 25, token, ExecService.PORT);
                    result = r == null ? null : r.out;
                } catch (Throwable ignored) {
                }
                final String res = result;
                ui.post(new Runnable() {
                    @Override public void run() {
                        fetching = false;
                        body = render(res);
                        if (out != null) out.setText(body);
                    }
                });
            }
        }).start();
    }

    private String render(String ps) {
        StringBuilder sb = new StringBuilder();
        sb.append("$ ps -eo pid,rss,args\n");
        if (ps == null || ps.trim().isEmpty()) {
            sb.append("# 容器里没有匹配的进程\n");
            sb.append("# (astrbot / napcat / dsh 都没在跑)\n");
            sb.append("$ _");
            return sb.toString();
        }
        String[] lines = ps.split("\n");
        int n = 0;
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            String[] parts = t.split("\\s+", 3);
            String pid = parts.length > 0 ? parts[0] : "?";
            long rssKb = 0;
            try { rssKb = Long.parseLong(parts.length > 1 ? parts[1] : "0"); } catch (Throwable ignored) {}
            String cmd = parts.length > 2 ? parts[2] : "";
            if (cmd.length() > 46) cmd = cmd.substring(0, 46) + "…";
            sb.append(String.format(java.util.Locale.CHINA, "%-6s %5dM  %s\n", pid, rssKb / 1024, cmd));
            n++;
        }
        sb.append("-- ").append(n).append(" process(es) --\n");
        sb.append("$ _");
        return sb.toString();
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
