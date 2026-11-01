package com.dsh.desk;

import android.content.Context;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 读取 NapCat WebUI 的 token（存在容器的 config/webui.json 里） */
public class NapCatToken {

    private static final String CONFIG =
            "/data/data/com.termux/files/usr/var/lib/proot-distro/containers/ubuntu-base/rootfs"
                    + "/root/config/webui.json";

    private static final Pattern TOKEN_RE =
            Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");

    private static volatile String cached = "";

    public static String get() {
        return cached;
    }

    public static void set(String t) {
        if (t != null) cached = t;
    }

    /** 通过 Termux 桥接读取，失败返回 null */
    public static String fetch(Context ctx) {
        String token = ExecService.ensureToken(
                ctx.getSharedPreferences(ExecService.PREFS, Context.MODE_PRIVATE));
        Shell.Result r = TermuxRunner.run(ctx,
                "grep -o '\"token\": *\"[^\"]*\"' '" + CONFIG + "' | head -1",
                20, token, ExecService.PORT);
        if (r.code != 0 || r.out == null) return null;
        Matcher m = TOKEN_RE.matcher(r.out);
        if (m.find()) {
            cached = m.group(1);
            return cached;
        }
        return null;
    }
}
