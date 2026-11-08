package com.dsh.desk;

import android.app.Activity;
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

import org.json.JSONObject;

/** 桌面第一页：终端风格的系统状态输出（neofetch 式） */
public class StatusPane {

    private static final int BG = Color.parseColor("#05080A");
    private static final int FG = Color.parseColor("#C6FFD2");
    private static final int GREEN = Color.parseColor("#33FF66");
    private static final int DIM = Color.parseColor("#2E7D4F");

    private static final long REFRESH_MS = 5000;

    private final Activity act;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView out;
    private TextView clock;

    private volatile boolean running = false;
    private volatile boolean fetching = false;
    private String lastBody = "# connecting to phone...";

    StatusPane(Activity a) {
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
        head.addView(mono("desk@honor:~#", 11, DIM, false),
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
        out = mono(lastBody, 12, FG, false);
        out.setLineSpacing(dp(3), 1f);
        sc.addView(out);
        col.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView foot = mono("$ <- linux   msg ->", 10, DIM, false);
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
            updateClock();
            fetch();
            ui.postDelayed(this, REFRESH_MS);
        }
    };

    private void updateClock() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        clock.setText(String.format(java.util.Locale.CHINA, "%02d:%02d:%02d",
                c.get(java.util.Calendar.HOUR_OF_DAY),
                c.get(java.util.Calendar.MINUTE),
                c.get(java.util.Calendar.SECOND)));
        if (out == null || lastBody == null) return;
        if (lastBody.startsWith("#")) return;
        String stamp = String.format(java.util.Locale.CHINA, "date    %04d-%02d-%02d %02d:%02d",
                c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH),
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
        out.setText(lastBody.replaceAll("(?m)^date\\s+.*$", java.util.regex.Matcher.quoteReplacement(stamp)));
    }

    private void fetch() {
        if (fetching) return;
        fetching = true;
        new Thread(new Runnable() {
            @Override public void run() {
                JSONObject o = null;
                try {
                    o = StatusApi.collect(act);
                } catch (Throwable ignored) {
                }
                final JSONObject r = o;
                ui.post(new Runnable() {
                    @Override public void run() {
                        fetching = false;
                        if (r != null) apply(r);
                        else {
                            lastBody = "$ ping 192.168.1.9\n# phone unreachable - retrying";
                            out.setText(lastBody);
                        }
                    }
                });
            }
        }).start();
    }

    private void apply(JSONObject o) {
        StringBuilder sb = new StringBuilder();
        try {
            java.util.Calendar c = java.util.Calendar.getInstance();
            sb.append("$ status --all\n");
            sb.append("os      Honor X40i / Android 12\n");
            sb.append(String.format(java.util.Locale.CHINA, "date    %04d-%02d-%02d %02d:%02d\n",
                    c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1,
                    c.get(java.util.Calendar.DAY_OF_MONTH),
                    c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE)));

            JSONObject bat = o.optJSONObject("battery");
            if (bat != null) {
                sb.append(String.format(java.util.Locale.CHINA, "bat     %d%% %s %.1fC\n",
                        bat.optInt("pct", -1),
                        bat.optBoolean("charging") ? "charging" : "discharging",
                        bat.optDouble("temp_c", 0)));
            }
            JSONObject ram = o.optJSONObject("ram");
            if (ram != null) {
                int used = ram.optInt("used_mb"), total = ram.optInt("total_mb");
                sb.append(String.format(java.util.Locale.CHINA, "mem     %.1fG / %.1fG (%d%%)\n",
                        used / 1024f, total / 1024f, total > 0 ? used * 100 / total : 0));
            }
            JSONObject st = o.optJSONObject("storage");
            if (st != null) {
                sb.append(String.format(java.util.Locale.CHINA, "disk    %.0fG free / %.0fG\n",
                        st.optDouble("avail_gb", 0), st.optDouble("total_gb", 0)));
            }
            long up = o.optLong("uptime_s", 0);
            sb.append(String.format(java.util.Locale.CHINA, "uptime  %dh%02dm\n",
                    up / 3600, (up % 3600) / 60));
            sb.append("net     ").append(o.optString("ip", "-")).append(" (wlan0)\n");

            JSONObject sv = o.optJSONObject("services");
            boolean ab = sv != null && sv.optBoolean("astrbot");
            boolean nc = sv != null && sv.optBoolean("napcat");
            boolean sh = sv != null && sv.optBoolean("ssh");
            sb.append("svc     astrbot:").append(ab ? "up" : "down")
              .append("  napcat:").append(nc ? "up" : "down")
              .append("  ssh:").append(sh ? "up" : "down").append("\n");
            sb.append("ports   8089 api  8090 web  8091 screen\n");
            sb.append("$ _");

            lastBody = sb.toString();
            out.setText(lastBody);
        } catch (Throwable ignored) {
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
