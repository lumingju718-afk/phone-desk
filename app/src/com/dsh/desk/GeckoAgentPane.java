package com.dsh.desk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

/**
 * 消息页（第 3 屏）：用 GeckoView（Mozilla 引擎）内嵌容器里的 DSH Web。
 * 系统 WebView 太旧（< Chrome 119，缺 Promise.withResolvers、新语法解析失败），所以换引擎。
 */
public class GeckoAgentPane {

    private static final int BG = Color.parseColor("#05080A");
    private static final int FG = Color.parseColor("#C6FFD2");
    private static final int GREEN = Color.parseColor("#33FF66");
    private static final int DIM = Color.parseColor("#2E7D4F");
    private static final int AMBER = Color.parseColor("#FFD166");
    private static final int RED = Color.parseColor("#FF5555");

    private static final String START_CMD = "bash ~/start-dsh.sh";

    private static GeckoRuntime sRuntime;
    private static String sLastError;
    private static boolean sRuntimeFailed;

    private final Activity act;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView status;
    private FrameLayout holder;
    private GeckoSession session;
    private GeckoView view;

    private String loadedUrl;
    private boolean busy = false;
    private boolean shown = false;

    GeckoAgentPane(Activity a) {
        this.act = a;
    }

    View create() {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(BG);

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(12), dp(10), dp(12), dp(6));

        head.addView(mono("dsh@gecko:~#", 13, GREEN, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView start = mono("[start]", 11, AMBER, true);
        start.setPadding(dp(8), dp(4), dp(8), dp(4));
        start.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startDsh(); }
        });
        head.addView(start);

        TextView reload = mono("[reload]", 11, GREEN, true);
        reload.setPadding(dp(8), dp(4), dp(8), dp(4));
        reload.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { loadDsh(); }
        });
        head.addView(reload);

        TextView open = mono("[browser]", 11, DIM, false);
        open.setPadding(dp(8), dp(4), dp(2), dp(4));
        open.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openBrowser(); }
        });
        head.addView(open);
        col.addView(head);

        status = mono("# 准备 Gecko …", 10, DIM, false);
        status.setPadding(dp(14), 0, dp(14), dp(4));
        col.addView(status);

        holder = new FrameLayout(act);
        col.addView(holder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return col;
    }

    void onShow() {
        shown = true;
        loadDsh();
    }

    void onHide() {
        shown = false;
    }

    void destroy() {
        try {
            if (session != null) { session.close(); session = null; }
        } catch (Throwable ignored) { }
        view = null;
    }

    private String cachedUrl() {
        try {
            SharedPreferences sp = act.getSharedPreferences(ExecService.PREFS, Context.MODE_PRIVATE);
            String u = sp.getString("dsh_url", "");
            return (u == null || u.isEmpty()) ? null : u;
        } catch (Throwable e) {
            return null;
        }
    }

    /** Gecko 初始化必须在主线程（线程 1），否则 IllegalThreadStateException */
    private void ensureGecko(final Runnable then) {
        if (view != null) { then.run(); return; }
        if (sRuntimeFailed) {
            status.setText("# Gecko 失败: " + (sLastError == null ? "?" : sLastError));
            status.setTextColor(RED);
            return;
        }
        status.setText("# 初始化 Gecko 引擎（首次较慢）…");
        status.setTextColor(AMBER);
        try {
            if (sRuntime == null) {
                sRuntime = GeckoRuntime.create(act.getApplicationContext());
            }
            GeckoSession s = new GeckoSession();
            s.open(sRuntime);
            session = s;
            view = new GeckoView(act);
            session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
                @Override public void onPageStart(GeckoSession gs, String url) {
                    status.setText("# loading " + shortUrl(url));
                    status.setTextColor(DIM);
                }
                @Override public void onPageStop(GeckoSession gs, boolean success) {
                    status.setText(success ? "# loaded" : "# load failed");
                    status.setTextColor(success ? GREEN : RED);
                }
            });
            view.setSession(session);
            holder.removeAllViews();
            holder.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            if (then != null) then.run();
        } catch (Throwable e) {
            sRuntimeFailed = true;
            sLastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            status.setText("# Gecko 失败: " + sLastError);
            status.setTextColor(RED);
        }
    }
    private void loadDsh() {
        if (!shown) return;
        ensureGecko(new Runnable() {
            @Override public void run() {
                String u = cachedUrl();
                if (u == null) {
                    status.setText("# 还没有 DSH 地址：点 [start] 或去最左屏点 dsh");
                    status.setTextColor(AMBER);
                    return;
                }
                if (u.equals(loadedUrl) && session != null) return;
                loadedUrl = u;
                status.setText("# loading …");
                status.setTextColor(DIM);
                session.loadUri(u);
            }
        });
    }

    private void startDsh() {
        if (busy) { Toast.makeText(act, "正在启动…", Toast.LENGTH_SHORT).show(); return; }
        busy = true;
        status.setText("# 启动 DSH（约 30-60 秒）…");
        status.setTextColor(AMBER);
        new Thread(new Runnable() {
            @Override public void run() {
                termux(START_CMD, 120);
                for (int i = 0; i < 24; i++) {
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                    if (cachedUrl() != null && portOpen(6200)) {
                        ui.post(new Runnable() {
                            @Override public void run() { busy = false; loadedUrl = null; loadDsh(); }
                        });
                        return;
                    }
                }
                ui.post(new Runnable() {
                    @Override public void run() {
                        busy = false;
                        status.setText("# 启动超时，看 ~/dsh-web.log");
                        status.setTextColor(RED);
                    }
                });
            }
        }).start();
    }

    private void openBrowser() {
        String u = cachedUrl();
        if (u == null) { Toast.makeText(act, "还没有 DSH 地址", Toast.LENGTH_SHORT).show(); return; }
        try {
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(it);
        } catch (Throwable ignored) { }
    }

    private boolean portOpen(int port) {
        java.net.Socket s = new java.net.Socket();
        try { s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 400); return true; }
        catch (Throwable e) { return false; }
        finally { try { s.close(); } catch (Throwable ignored) {} }
    }

    private String termux(String cmd, int timeoutSec) {
        try {
            Context ctx = act.getApplicationContext();
            String token = ExecService.ensureToken(
                    ctx.getSharedPreferences(ExecService.PREFS, Context.MODE_PRIVATE));
            Shell.Result r = TermuxRunner.run(ctx, cmd, timeoutSec, token, ExecService.PORT);
            return r == null ? "" : r.out;
        } catch (Throwable e) {
            return null;
        }
    }

    private static String shortUrl(String u) {
        if (u == null) return "";
        return u.replaceAll("token=[^&]+", "token=***");
    }

    private TextView mono(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        t.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }
}
