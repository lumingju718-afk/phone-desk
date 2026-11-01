package com.dsh.desk;

import android.content.Context;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * NapCat / AstrBot 的启停控制。
 * 通过 Termux 桥接（RUN_COMMAND）在 Termux 侧执行 proot 启动脚本与 pkill。
 */
public class SvcControl {

    public static final String NAPCAT = "napcat";
    public static final String ASTRBOT = "astrbot";

    public static boolean isRunning(String name) {
        int port = NAPCAT.equals(name) ? 6099 : 6185;
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", port), 400);
            return true;
        } catch (Throwable e) {
            return false;
        } finally {
            try { s.close(); } catch (Throwable ignored) { }
        }
    }

    public static String startCmd(String name) {
        if (NAPCAT.equals(name)) {
            return "(setsid nohup proot-distro login ubuntu-base -- bash /root/start-napcat.sh "
                    + ">/dev/null 2>&1 </dev/null &) ; echo started";
        }
        return "(setsid nohup proot-distro login ubuntu-base -- bash /root/run-astrbot.sh "
                + ">/dev/null 2>&1 </dev/null &) ; echo started";
    }

    public static String stopCmd(String name) {
        if (NAPCAT.equals(name)) {
            return "pkill -f '[s]quashfs-root/qq' ; pkill -f '[x]vfb-run -a bash -c' ; echo stopped";
        }
        return "pkill -f '[a]strbot run' ; echo stopped";
    }

    public static String command(String name, String action) {
        if ("start".equals(action)) return startCmd(name);
        if ("stop".equals(action)) return stopCmd(name);
        if ("restart".equals(action)) return stopCmd(name) + " ; sleep 2 ; " + startCmd(name);
        return null;
    }

    /** 通过 Termux 桥接执行；返回底层结果 */
    public static Shell.Result exec(Context ctx, String name, String action,
                                    String token, int port) {
        String cmd = command(name, action);
        if (cmd == null) {
            Shell.Result r = new Shell.Result();
            r.code = -1;
            r.out = "unknown action: " + action + "\n";
            return r;
        }
        return TermuxRunner.run(ctx, cmd, 25, token, port);
    }
}
