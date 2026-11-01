package com.dsh.desk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** 应用内终端：在手机本地执行 shell 命令，并显示远程控制地址 */
public class TerminalActivity extends Activity {

    private TextView output;
    private EditText input;
    private ScrollView scroll;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        SharedPreferences sp = getSharedPreferences(ExecService.PREFS, Context.MODE_PRIVATE);
        String token = ExecService.ensureToken(sp);
        String ip = localIp();

        TextView head = new TextView(this);
        head.setText("本应用终端  http://" + ip + ":" + ExecService.PORT
                + "/exec?token=" + token + "&cmd=<命令>\n"
                + "Termux 桥接  http://" + ip + ":" + ExecService.PORT
                + "/termux?token=" + token + "&cmd=<命令>");
        head.setTextColor(Color.parseColor("#4DA3FF"));
        head.setTextSize(10);
        head.setPadding(dp(10), dp(10), dp(10), dp(4));
        root.addView(head);

        boolean accOn = DeskAccessibilityService.ready();
        TextView accStatus = new TextView(this);
        accStatus.setText(accOn ? "无障碍：已开启，/ui/* 可用"
                : "无障碍：未开启，/ui/* 不可用（点下面按钮开启）");
        accStatus.setTextColor(Color.parseColor(accOn ? "#3DDC84" : "#FFC857"));
        accStatus.setTextSize(11);
        accStatus.setPadding(dp(10), dp(2), dp(10), dp(2));
        root.addView(accStatus);
        if (!accOn) {
            Button accBtn = new Button(this);
            accBtn.setText("开启无障碍（UI 控制需要）");
            accBtn.setTextSize(11);
            accBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (Throwable ignored) {
                    }
                }
            });
            root.addView(accBtn);
        }

        if (!TermuxRunner.hasFileAccess()) {
            Button perm = new Button(this);
            perm.setText("授权「所有文件访问」（可选，文件操作更方便）");
            perm.setTextSize(11);
            perm.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        startActivity(new Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Throwable e) {
                        try {
                            startActivity(new Intent(
                                    Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });
            root.addView(perm);
        }

        scroll = new ScrollView(this);
        output = new TextView(this);
        output.setTextColor(Color.parseColor("#9BE3A8"));
        output.setTextSize(12);
        output.setTypeface(Typeface.MONOSPACE);
        output.setPadding(dp(10), dp(4), dp(10), dp(4));
        scroll.addView(output);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(6), dp(6), dp(6), dp(10));

        input = new EditText(this);
        input.setHint("输入命令，如 id / ls /sdcard");
        input.setHintTextColor(Color.parseColor("#55606B"));
        input.setTextColor(Color.WHITE);
        input.setTextSize(13);
        input.setTypeface(Typeface.MONOSPACE);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        row.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button go = new Button(this);
        go.setText("运行");
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { submit(); }
        });
        row.addView(go);
        root.addView(row);

        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                if (actionId == EditorInfo.IME_ACTION_GO) { submit(); return true; }
                return false;
            }
        });

        setContentView(root);

        try {
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                String p = "com.termux.permission.RUN_COMMAND";
                if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{p}, 8);
                }
            }
        } catch (Throwable ignored) {
        }

        print("$ id\n" + Shell.run("id", 5).out);
        print("$ getprop ro.product.model\n" + Shell.run("getprop ro.product.model", 5).out);
        input.requestFocus();
    }

    private void submit() {
        if (running) return;
        final String cmd = input.getText().toString().trim();
        if (cmd.isEmpty()) return;
        input.setText("");
        print("$ " + cmd + "\n");
        running = true;
        new Thread(new Runnable() {
            @Override public void run() {
                final Shell.Result r = Shell.run(cmd, 40);
                ui.post(new Runnable() {
                    @Override public void run() {
                        print(r.out);
                        if (r.code != 0) print("[exit " + r.code + "]\n");
                        running = false;
                    }
                });
            }
        }).start();
    }

    private void print(String s) {
        output.append(s);
        ui.post(new Runnable() {
            @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
        });
    }

    private String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName();
                if (n == null || (!n.startsWith("wlan") && !n.startsWith("eth"))) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Throwable ignored) {
        }
        return "127.0.0.1";
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
