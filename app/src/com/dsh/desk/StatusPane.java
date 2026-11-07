package com.dsh.desk;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/** 桌面第一页：服务器状态（电量/内存/存储/运行时长/网络/服务） */
public class StatusPane {

    private static final int BG = Color.parseColor("#0B1014");
    private static final int CARD = Color.parseColor("#151C24");
    private static final int TEXT = Color.parseColor("#E6EDF3");
    private static final int DIM = Color.parseColor("#8B98A5");
    private static final int GREEN = Color.parseColor("#3DDC84");
    private static final int RED = Color.parseColor("#FF6B6B");
    private static final int BLUE = Color.parseColor("#4DA3FF");

    private static final long REFRESH_MS = 5000;

    private final Activity act;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView tvClock, tvDate, tvBatt, tvBattSub;
    private TextView[] vTitle = new TextView[6];
    private TextView[] vValue = new TextView[6];
    private TextView[] vSub = new TextView[6];

    private volatile boolean running = false;
    private volatile boolean fetching = false;

    StatusPane(Activity a) {
        this.act = a;
    }

    View create() {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(BG);
        col.setPadding(dp(16), dp(12), dp(16), dp(10));

        // 顶部：时间 / 日期 / 电量
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = new LinearLayout(act);
        left.setOrientation(LinearLayout.VERTICAL);
        tvClock = text("--:--", 44, TEXT, true);
        tvDate = text("", 13, DIM, false);
        left.addView(tvClock);
        left.addView(tvDate);

        LinearLayout right = new LinearLayout(act);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        tvBatt = text("--%", 26, TEXT, true);
        tvBattSub = text("", 12, DIM, false);
        tvBatt.setGravity(Gravity.END);
        tvBattSub.setGravity(Gravity.END);
        right.addView(tvBatt);
        right.addView(tvBattSub);

        head.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(right, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(head);

        // 卡片网格 2 列 × 3 行
        GridLayout grid = new GridLayout(act);
        grid.setColumnCount(2);
        String[] titles = {"内存", "存储", "运行时长", "网络", "AstrBot", "NapCat"};
        for (int i = 0; i < titles.length; i++) {
            View card = buildCard(i, titles[i]);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = dp(86);
            lp.columnSpec = GridLayout.spec(i % 2, 1, 1f);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            grid.addView(card, lp);
        }
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = dp(6);
        col.addView(grid, glp);

        // 底部提示
        TextView hint = text("← 左右滑动切换页面 →", 11, DIM, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(10), 0, 0);
        col.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return col;
    }

    private View buildCard(int idx, String title) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(14));
        card.setBackground(g);

        vTitle[idx] = text(title, 11, DIM, false);
        vValue[idx] = text("--", 20, TEXT, true);
        vValue[idx].setPadding(0, dp(3), 0, dp(1));
        vSub[idx] = text("", 11, DIM, false);

        card.addView(vTitle[idx]);
        card.addView(vValue[idx]);
        card.addView(vSub[idx]);
        return card;
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
        tvClock.setText(String.format(java.util.Locale.CHINA, "%02d:%02d",
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE)));
        String[] wd = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        tvDate.setText(String.format(java.util.Locale.CHINA, "%d月%d日 %s",
                c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH),
                wd[c.get(java.util.Calendar.DAY_OF_WEEK) - 1]));
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
                    }
                });
            }
        }).start();
    }

    private void apply(JSONObject o) {
        try {
            JSONObject bat = o.optJSONObject("battery");
            if (bat != null) {
                int pct = bat.optInt("pct", -1);
                tvBatt.setText(pct >= 0 ? pct + "%" : "--%");
                tvBatt.setTextColor(pct >= 0 && pct <= 25 ? RED : (bat.optBoolean("charging") ? GREEN : TEXT));
                tvBattSub.setText((bat.optBoolean("charging") ? "充电中" : "未充电")
                        + " · " + String.format(java.util.Locale.CHINA, "%.1f℃", bat.optDouble("temp_c", 0)));
            }

            JSONObject ram = o.optJSONObject("ram");
            if (ram != null) {
                int used = ram.optInt("used_mb"), total = ram.optInt("total_mb");
                vValue[0].setText(String.format(java.util.Locale.CHINA, "%.1fG", used / 1024f));
                vSub[0].setText(String.format(java.util.Locale.CHINA, "共 %.1fG · %d%%",
                        total / 1024f, total > 0 ? used * 100 / total : 0));
            }

            JSONObject st = o.optJSONObject("storage");
            if (st != null) {
                vValue[1].setText(String.format(java.util.Locale.CHINA, "%.0fG 可用", st.optDouble("avail_gb", 0)));
                vSub[1].setText(String.format(java.util.Locale.CHINA, "共 %.0fG", st.optDouble("total_gb", 0)));
            }

            long up = o.optLong("uptime_s", 0);
            vValue[2].setText(String.format(java.util.Locale.CHINA, "%d时%d分", up / 3600, (up % 3600) / 60));
            vSub[2].setText("自上次开机");

            vValue[3].setText(o.optString("ip", "--"));
            vSub[3].setText("局域网 IPv4");
            vValue[3].setTextSize(15);

            JSONObject sv = o.optJSONObject("services");
            boolean ab = sv != null && sv.optBoolean("astrbot");
            boolean nc = sv != null && sv.optBoolean("napcat");
            vValue[4].setText(ab ? "在线" : "离线");
            vValue[4].setTextColor(ab ? GREEN : RED);
            vSub[4].setText("端口 6185");
            vValue[5].setText(nc ? "在线" : "离线");
            vValue[5].setTextColor(nc ? GREEN : RED);
            vSub[5].setText("端口 6099");
        } catch (Throwable ignored) {
        }
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }
}
