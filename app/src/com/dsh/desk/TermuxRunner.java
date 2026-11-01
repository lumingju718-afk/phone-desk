package com.dsh.desk;

import android.content.Context;
import android.content.Intent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把命令转交给 Termux 执行（借用 Termux 的完整工具链）。
 * 依赖：Termux 的 allow-external-apps=true。
 * 结果通过 curl 回调 http://127.0.0.1:<port>/callback 传回，不需要存储权限。
 */
public class TermuxRunner {

    public static class Callback {
        public volatile boolean done = false;
        public volatile String out = "";
        public volatile int code = -1;
    }

    public static final Map<String, Callback> PENDING = new ConcurrentHashMap<>();

    public static boolean hasFileAccess() {
        try {
            return android.os.Environment.isExternalStorageManager();
        } catch (Throwable e) {
            return false;
        }
    }

    private static final String TMP_PREFIX = "/data/data/com.termux/files/usr/tmp/.dsh_out_";

    public static Shell.Result run(Context ctx, String cmd, int timeoutSec,
                                   String token, int port) {
        Shell.Result r = new Shell.Result();
        final String id = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Callback cb = new Callback();
        PENDING.put(id, cb);

        String tmp = TMP_PREFIX + id;
        String script = "{ " + cmd + " ; } > " + tmp + " 2>&1; c=$?; "
                + "curl -s --max-time 8 -X POST --data-binary @" + tmp + " "
                + "'http://127.0.0.1:" + port + "/callback?token=" + token
                + "&id=" + id + "&code='$c >/dev/null 2>&1; rm -f " + tmp;
        try {
            Intent i = new Intent();
            i.setClassName("com.termux", "com.termux.app.RunCommandService");
            i.setAction("com.termux.RUN_COMMAND");
            i.putExtra("com.termux.RUN_COMMAND_PATH",
                    "/data/data/com.termux/files/usr/bin/bash");
            i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS",
                    new String[]{"-c", script});
            i.putExtra("com.termux.RUN_COMMAND_WORKDIR",
                    "/data/data/com.termux/files/home");
            i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            i.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");
            ctx.startService(i);
        } catch (Throwable e) {
            PENDING.remove(id);
            r.code = -3;
            r.out = "cannot start termux: " + e.getMessage() + "\n";
            return r;
        }

        long end = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < end && !cb.done) {
            try { Thread.sleep(150); } catch (InterruptedException e) { break; }
        }
        PENDING.remove(id);
        if (!cb.done) {
            r.code = -4;
            r.out = "timeout waiting for termux callback\n";
            return r;
        }
        r.code = cb.code;
        r.out = cb.out;
        return r;
    }
}
