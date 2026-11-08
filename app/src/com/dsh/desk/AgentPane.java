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
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 消息页（第 3 屏）：内嵌手机容器里的 DSH Web。
 * token URL 是 303 + HttpOnly cookie，WebView 里容易丢；所以先用 curl 取 cookie，塞进 CookieManager 再加载首页。
 */
public class AgentPane {

    private static final int BG = Color.parseColor("#05080A");
    private static final int FG = Color.parseColor("#C6FFD2");
    private static final int GREEN = Color.parseColor("#33FF66");
    private static final int DIM = Color.parseColor("#2E7D4F");
    private static final int AMBER = Color.parseColor("#FFD166");
    private static final int RED = Color.parseColor("#FF5555");

    private static final String START_CMD = "bash ~/start-dsh.sh";

    /** 注入到页面的错误收集器：把 JS 报错画到右下角，便于 dump 出来 */
    private static final String ERR_HOOK =
            "javascript:(function(){if(window.__dshErr)return;window.__dshErr=1;" +
            "function show(t){try{var p=document.getElementById('__dsherr');" +
            "if(!p){p=document.createElement('pre');p.id='__dsherr';" +
            "p.style.cssText='position:fixed;left:0;bottom:0;max-height:40%;overflow:auto;" +
            "background:#300;color:#f88;font:10px monospace;z-index:2147483647;margin:0;padding:4px;" +
            "white-space:pre-wrap;width:100%';document.documentElement.appendChild(p);}" +
            "p.textContent+=t+'\\n';}catch(e){}}" +
            "window.addEventListener('error',function(e){show('ERR '+e.message+' @'+(e.filename||'').split('/').pop()+':'+e.lineno);});" +
            "window.addEventListener('unhandledrejection',function(e){show('REJ '+((e.reason&&(e.reason.message||e.reason))||'?'));});" +
            "})()";

    private final Activity act;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView status;
    private WebView web;
    private String rootUrl;
    private boolean busy = false;
    private boolean shown = false;

    AgentPane(Activity a) {
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

        head.addView(mono("dsh@agent:~#", 13, GREEN, true),
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

        status = mono("# 等待 DSH …", 10, DIM, false);
        status.setPadding(dp(14), 0, dp(14), dp(4));
        col.addView(status);

        web = new WebView(act);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        web.setBackgroundColor(BG);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
                v.evaluateJavascript(ERR_HOOK, null);
            }
            @Override public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(ERR_HOOK, null);
                status.setText("# loaded");
                status.setTextColor(GREEN);
            }
            @Override public void onReceivedError(WebView v, int code, String desc, String failingUrl) {
                status.setText("# load error: " + desc);
                status.setTextColor(RED);
            }
        });
        col.addView(web, new LinearLayout.LayoutParams(
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
        if (web != null) { web.stopLoading(); web.destroy(); web = null; }
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

    private void loadDsh() {
        String u = cachedUrl();
        if (u == null) {
            status.setText("# 还没有 DSH 地址：点 [start] 或去最左屏点 dsh");
            status.setTextColor(AMBER);
            return;
        }
        status.setText("# 取 cookie 并加载 …");
        status.setTextColor(DIM);
        new Thread(new Runnable() {
            @Override public void run() {
                // 1) 用 curl 走一遍 303，拿到 HttpOnly cookie
                final String[] cookie = new String[2];
                try {
                    Context ctx = act.getApplicationContext();
                    String token = ExecService.ensureToken(
                            ctx.getSharedPreferences(ExecService.PREFS, Context.MODE_PRIVATE));
                    String url = cachedUrl();
                    String out = termux("curl -s -D - -o /dev/null -m 10 '" + url + "' | grep -i '^set-cookie' | head -1", 20);
                    if (out != null && out.contains("=")) {
                        String c = out.substring(out.indexOf(':') + 1).trim();
                        int semi = c.indexOf(';');
                        if (semi > 0) c = c.substring(0, semi);
                        c = c.replace("Set-Cookie:", "").replace("set-cookie:", "").trim();
                        int eq = c.indexOf('=');
                        if (eq > 0) {
                            cookie[0] = c.substring(0, eq);
                            cookie[1] = c.substring(eq + 1);
                        }
                    }
                    rootUrl = rootOf(url);
                } catch (Throwable ignored) {
                }
                ui.post(new Runnable() {
                    @Override public void run() {
                        CookieManager cm = CookieManager.getInstance();
                        cm.setAcceptCookie(true);
                        if (cookie[0] != null && rootUrl != null) {
                            cm.setCookie(rootUrl, cookie[0] + "=" + cookie[1] + "; Path=/");
                            status.setText("# cookie ok, loading " + rootUrl);
                        } else {
                            status.setText("# 没取到 cookie，直接加载试试");
                        }
                        cm.flush();
                        web.loadUrl(rootUrl != null ? rootUrl : cachedUrl());
                    }
                });
            }
        }).start();
    }

    private static String rootOf(String tokenUrl) {
        try {
            java.net.URI u = java.net.URI.create(tokenUrl);
            return u.getScheme() + "://" + u.getAuthority() + "/";
        } catch (Throwable e) {
            return tokenUrl;
        }
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
                            @Override public void run() { busy = false; loadDsh(); }
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
