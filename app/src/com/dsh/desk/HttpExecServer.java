package com.dsh.desk;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

/**
 * 极简 HTTP 控制端：
 *   GET /ping
 *   GET|POST /exec?token=xxx&cmd=<urlencoded>
 * 只监听局域网，用于远程接管手机（命令以本应用权限执行）。
 */
public class HttpExecServer {

    private final int port;
    private final String token;
    private final Context context;
    private volatile boolean running = false;
    private ServerSocket serverSocket;

    public HttpExecServer(Context ctx, int port, String token) {
        this.context = ctx;
        this.port = port;
        this.token = token;
    }

    public void start() {
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "http-exec");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Throwable ignored) { }
    }

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket(port);
            while (running) {
                Socket s = serverSocket.accept();
                handleAsync(s);
            }
        } catch (Throwable ignored) {
        }
    }

    private void handleAsync(final Socket s) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try { handle(s); } catch (Throwable ignored) {
                } finally {
                    try { s.close(); } catch (Throwable ignored) { }
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void handle(Socket s) throws Exception {
        s.setSoTimeout(15000);
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
        String requestLine = in.readLine();
        if (requestLine == null) return;
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return;
        String method = parts[0];
        String uri = parts[1];

        // 读掉 header
        String line;
        int contentLength = 0;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            int idx = line.indexOf(':');
            if (idx > 0 && line.substring(0, idx).trim().equalsIgnoreCase("Content-Length")) {
                try { contentLength = Integer.parseInt(line.substring(idx + 1).trim()); } catch (Throwable ignored) { }
            }
        }
        StringBuilder body = new StringBuilder();
        if (contentLength > 0) {
            int want = Math.min(contentLength, 200000);
            char[] buf = new char[want];
            int read = 0;
            while (read < want) {
                int n = in.read(buf, read, want - read);
                if (n < 0) break;
                read += n;
            }
            body.append(buf, 0, read);
        }

        String path = uri;
        String query = "";
        int q = uri.indexOf('?');
        if (q >= 0) {
            path = uri.substring(0, q);
            query = uri.substring(q + 1);
        }
        Map<String, String> params = parseQuery(query);
        if (!"POST".equals(method) && body.length() > 0) {
            params.putAll(parseQuery(body.toString()));
        }

        if (path.equals("/ping")) {
            respond(s, 200, "{\"ok\":true,\"service\":\"desk-exec\"}");
            return;
        }

        if (path.equals("/exec")) {
            String t = params.get("token");
            if (t == null || !t.equals(token)) {
                respond(s, 403, "{\"ok\":false,\"error\":\"bad token\"}");
                return;
            }
            String cmd = params.get("cmd");
            if (cmd == null || cmd.trim().isEmpty()) {
                respond(s, 400, "{\"ok\":false,\"error\":\"missing cmd\"}");
                return;
            }
            long t0 = System.currentTimeMillis();
            Shell.Result r = Shell.run(cmd, 40);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("code", r.code);
            o.put("ms", System.currentTimeMillis() - t0);
            o.put("out", r.out);
            respond(s, 200, o.toString());
            return;
        }

        if (path.equals("/callback")) {
            String t = params.get("token");
            if (t == null || !t.equals(token)) {
                respond(s, 403, "{\"ok\":false,\"error\":\"bad token\"}");
                return;
            }
            String id = params.get("id");
            String codeStr = params.get("code");
            TermuxRunner.Callback cb = id == null ? null : TermuxRunner.PENDING.get(id);
            if (cb != null) {
                cb.out = body.toString();
                try { cb.code = Integer.parseInt(codeStr); } catch (Throwable e) { cb.code = 0; }
                cb.done = true;
            }
            respond(s, 200, "{\"ok\":true}");
            return;
        }

        if (path.equals("/termux")) {
            String t = params.get("token");
            if (t == null || !t.equals(token)) {
                respond(s, 403, "{\"ok\":false,\"error\":\"bad token\"}");
                return;
            }
            String cmd = params.get("cmd");
            if (cmd == null || cmd.trim().isEmpty()) {
                respond(s, 400, "{\"ok\":false,\"error\":\"missing cmd\"}");
                return;
            }
            long t0 = System.currentTimeMillis();
            Shell.Result r = TermuxRunner.run(context, cmd, 35, token, port);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("via", "termux");
            o.put("code", r.code);
            o.put("ms", System.currentTimeMillis() - t0);
            o.put("out", r.out);
            respond(s, 200, o.toString());
            return;
        }

        if (path.equals("/") || path.isEmpty()) {
            respond(s, 200, "{\"ok\":true,\"endpoints\":[\"/ping\",\"/exec?token=&cmd=\",\"/termux?token=&cmd=\"]}");
            return;
        }

        respond(s, 404, "{\"ok\":false,\"error\":\"not found\"}");
    }

    private Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<>();
        if (q == null || q.isEmpty()) return m;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            try {
                if (i < 0) {
                    m.put(URLDecoder.decode(kv, "UTF-8"), "");
                } else {
                    m.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"),
                            URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
                }
            } catch (Throwable ignored) {
            }
        }
        return m;
    }

    private void respond(Socket s, int code, String body) throws Exception {
        byte[] bytes = body.getBytes("UTF-8");
        String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "ERR") + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream os = s.getOutputStream();
        os.write(head.getBytes("UTF-8"));
        os.write(bytes);
        os.flush();
    }
}
