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
    private static volatile long fetchedAt = 0;

    public static String get() {
        return cached;
    }

    public static void set(String t) {
        if (t != null) cached = t;
    }

    /** 通过 Termux 桥接读取，失败返回 null；60 秒内复用缓存 */
    public static String fetch(Context ctx) {
        if (!cached.isEmpty() && System.currentTimeMillis() - fetchedAt < 60000L) {
            return cached;
        }
        String token = ExecService.ensureToken(
                ctx.getSharedPreferences(ExecService.PREFS, Context.MODE_PRIVATE));
        Shell.Result r = TermuxRunner.run(ctx,
                "grep -o '\"token\": *\"[^\"]*\"' '" + CONFIG + "' | head -1",
                20, token, ExecService.PORT);
        if (r.code != 0 || r.out == null) return null;
        Matcher m = TOKEN_RE.matcher(r.out);
        if (m.find()) {
            cached = m.group(1);
            fetchedAt = System.currentTimeMillis();
            return cached;
        }
        return null;
    }
}
