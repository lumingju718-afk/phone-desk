package com.dsh.desk;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/** 在 app 自身权限下执行 shell 命令 */
public class Shell {

    public static class Result {
        public int code;
        public String out = "";
    }

    public static Result run(String cmd, int timeoutSec) {
        Result r = new Result();
        final StringBuilder sb = new StringBuilder();
        Process p = null;
        try {
            p = new ProcessBuilder("/system/bin/sh", "-c", cmd)
                    .redirectErrorStream(true)
                    .start();
            final Process fp = p;
            Thread reader = new Thread(new Runnable() {
                @Override public void run() {
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(fp.getInputStream(), "UTF-8"))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            sb.append(line).append('\n');
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            reader.setDaemon(true);
            reader.start();
            boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
            }
            reader.join(800);
            r.code = done ? p.exitValue() : 124;
        } catch (Throwable e) {
            r.code = -1;
            sb.append("exec error: ").append(e.getMessage()).append('\n');
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) { }
            }
        }
        r.out = sb.toString();
        return r;
    }
}
