package com.dsh.desk;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 公网页（8090）：给内网穿透用的受控页面。
 *
 * 只提供：状态 / 日志 / 消息 / 服务启停 / token。
 * 故意**不注册** /exec、/termux、/ui/* —— 那些能力留在局域网 8089。
 * 访问需要口令（首次随机生成），登录后下发 HttpOnly Cookie，7 天有效。
 */
public class WebUiServer extends Thread {

    public static final int PORT = 8090;
    public static final String PASS_KEY = "webui_pass";
    private static final String COOKIE = "dshweb";
    private static final long SESSION_MS = 7L * 24 * 3600 * 1000;

    private static final String PROOT_ROOTFS =
            "/data/data/com.termux/files/usr/var/lib/proot-distro/containers/ubuntu-base/rootfs";

    private static final Map<String, Long> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<String, long[]> FAILS = new ConcurrentHashMap<>();

    private final Context ctx;
    private ServerSocket ss;
    private volatile boolean running = true;

    public WebUiServer(Context c) {
        super("webui-8090");
        this.ctx = c.getApplicationContext();
    }

    public void shutdown() {
        running = false;
        try {
            if (ss != null) ss.close();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void run() {
        try {
            ss = new ServerSocket(PORT);
            while (running) {
                final Socket s = ss.accept();
                new Thread(new Runnable() {
                    @Override public void run() { handle(s); }
                }).start();
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 口令 ----------------

    public static String password(Context c) {
        SharedPreferences sp = c.getSharedPreferences(ExecService.PREFS, Context.MODE_PRIVATE);
        String p = sp.getString(PASS_KEY, "");
        if (p == null || p.length() < 6) {
            p = randomAlnum(10);
            sp.edit().putString(PASS_KEY, p).apply();
        }
        return p;
    }

    public static String setPassword(Context c, String p) {
        if (p == null || p.length() < 6) return password(c);
        SharedPreferences sp = c.getSharedPreferences(ExecService.PREFS, Context.MODE_PRIVATE);
        sp.edit().putString(PASS_KEY, p).apply();
        SESSIONS.clear();
        return p;
    }

    // ---------------- 请求 ----------------

    private void handle(Socket s) {
        try {
            s.setSoTimeout(20000);
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String reqLine = br.readLine();
            if (reqLine == null) return;
            String[] parts = reqLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String target = parts[1];

            int contentLen = 0;
            String cookie = "";
            String line;
            String fwdProto = "";
            String reqHost = "";
            while ((line = br.readLine()) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c <= 0) continue;
                String k = line.substring(0, c).trim().toLowerCase();
                String v = line.substring(c + 1).trim();
                if (k.equals("content-length")) contentLen = parseInt(v, 0);
                else if (k.equals("cookie")) cookie = v;
                else if (k.equals("x-forwarded-proto")) fwdProto = v.toLowerCase();
                else if (k.equals("host")) reqHost = v;
            }

            // 隧道以 http 进来时强制跳 https，避免口令走明文
            if ("http".equals(fwdProto) && !reqHost.isEmpty()) {
                sendRedirect(s, "https://" + reqHost + target, null);
                return;
            }
            StringBuilder body = new StringBuilder();
            if ("POST".equals(method) && contentLen > 0 && contentLen <= 8192) {
                char[] buf = new char[512];
                int read = 0;
                while (read < contentLen) {
                    int n = br.read(buf, 0, Math.min(buf.length, contentLen - read));
                    if (n < 0) break;
                    body.append(buf, 0, n);
                    read += n;
                }
            }

            String path = target;
            String query = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                query = target.substring(q + 1);
            }
            Map<String, String> params = parse(query);
            if (body.length() > 0) params.putAll(parse(body.toString()));

            if (path.equals("/login")) {
                if ("POST".equals(method)) doLogin(s, params.get("pass"), clientIp(s));
                else sendHtml(s, 200, WebUiHtml.LOGIN, null);
                return;
            }
            if (path.equals("/logout")) {
                String sid = sessionId(cookie);
                if (sid != null) SESSIONS.remove(sid);
                sendRedirect(s, "/login", null);
                return;
            }
            if (path.equals("/")) {
                if (!authed(cookie)) {
                    sendRedirect(s, "/login", null);
                    return;
                }
                sendHtml(s, 200, WebUiHtml.DESK, null);
                return;
            }
            if (path.startsWith("/api/")) {
                if (!authed(cookie)) {
                    sendJson(s, 401, "{\"ok\":false,\"error\":\"unauthorized\"}");
                    return;
                }
                api(s, path, params);
                return;
            }
            sendJson(s, 404, "{\"ok\":false,\"error\":\"not found\"}");
        } catch (Throwable ignored) {
        } finally {
            try { s.close(); } catch (Throwable ignored) { }
        }
    }

    private void doLogin(Socket s, String pass, String ip) throws Exception {
        long now = System.currentTimeMillis();
        long[] f = FAILS.get(ip);
        if (f != null && now - f[0] < 60000 && f[1] >= 5) {
            sendRedirect(s, "/login?e=1", null);
            return;
        }
        String expected = password(ctx);
        if (pass != null && pass.equals(expected)) {
            FAILS.remove(ip);
            String sid = randomHex(24);
            SESSIONS.put(sid, now + SESSION_MS);
            sendRedirect(s, "/", "Set-Cookie: " + COOKIE + "=" + sid
                    + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=604800\r\n");
            return;
        }
        long[] nf;
        if (f == null || now - f[0] >= 60000) {
            nf = new long[]{now, 0};
        } else {
            nf = f;
        }
        nf[1] = nf[1] + 1;
        FAILS.put(ip, nf);
        sendRedirect(s, "/login?e=1", null);
    }

    private void api(Socket s, String path, Map<String, String> p) throws Exception {
        if (path.equals("/api/status")) {
            sendJson(s, 200, StatusApi.collect(ctx).toString());
            return;
        }
        if (path.equals("/api/token")) {
            String t = NapCatToken.fetch(ctx);
            JSONObject o = new JSONObject();
            o.put("ok", t != null && !t.isEmpty());
            o.put("token", t == null ? "" : t);
            sendJson(s, 200, o.toString());
            return;
        }
        if (path.equals("/api/logs")) {
            String which = p.get("which");
            if (!"astrbot".equals(which)) which = "napcat";
            int lines = parseInt(p.get("lines"), 120);
            if (lines < 10) lines = 10;
            if (lines > 300) lines = 300;
            String file = "astrbot".equals(which) ? "/root/astrbot.log" : "/root/napcat.log";
            String token = ExecService.ensureToken(
                    ctx.getSharedPreferences(ExecService.PREFS, Context.MODE_PRIVATE));
            Shell.Result r = TermuxRunner.run(ctx,
                    "tail -n " + lines + " '" + PROOT_ROOTFS + file + "'",
                    25, token, ExecService.PORT);
            JSONObject o = new JSONObject();
            o.put("ok", r.code == 0);
            o.put("text", r.out == null ? "" : r.out);
            sendJson(s, 200, o.toString());
            return;
        }
        if (path.equals("/api/chat")) {
            int limit = parseInt(p.get("limit"), 100);
            if (limit < 1) limit = 1;
            if (limit > 200) limit = 200;
            List<JSONObject> msgs = ChatStore.read(ctx, 0, limit);
            JSONArray arr = new JSONArray();
            for (JSONObject m : msgs) arr.put(m);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("messages", arr);
            o.put("pending", ChatStore.pending(ctx).size());
            sendJson(s, 200, o.toString());
            return;
        }
        if (path.equals("/api/send")) {
            String text = p.get("text");
            JSONObject o = new JSONObject();
            if (text == null || text.trim().isEmpty()) {
                o.put("ok", false);
                o.put("error", "missing text");
                sendJson(s, 400, o.toString());
                return;
            }
            if (text.length() > 2000) text = text.substring(0, 2000);
            long id = ChatStore.append(ctx, ChatStore.CONV_MAIN, ChatStore.ROLE_USER,
                    text, ChatStore.ST_PENDING, 0);
            ChatEngine.dispatch(ctx, id);
            o.put("ok", true);
            o.put("id", id);
            o.put("status", ChatStore.ST_PENDING);
            sendJson(s, 200, o.toString());
            return;
        }
        if (path.equals("/api/svc")) {
            String name = p.get("name");
            if (name == null || name.isEmpty()) name = "napcat";
            String action = p.get("action");
            if (action == null || action.isEmpty()) action = "start";
            String token = ExecService.ensureToken(
                    ctx.getSharedPreferences(ExecService.PREFS, Context.MODE_PRIVATE));
            Shell.Result r = SvcControl.exec(ctx, name, action, token, ExecService.PORT);
            JSONObject o = new JSONObject();
            o.put("ok", r.code == 0);
            o.put("name", name);
            o.put("action", action);
            o.put("running", SvcControl.isRunning(name));
            o.put("out", r.out == null ? "" : r.out);
            sendJson(s, 200, o.toString());
            return;
        }
        sendJson(s, 404, "{\"ok\":false,\"error\":\"not found\"}");
    }

    // ---------------- 会话 ----------------

    private boolean authed(String cookie) {
        String sid = sessionId(cookie);
        if (sid == null) return false;
        Long exp = SESSIONS.get(sid);
        if (exp == null) return false;
        if (exp < System.currentTimeMillis()) {
            SESSIONS.remove(sid);
            return false;
        }
        return true;
    }

    private static String sessionId(String cookie) {
        if (cookie == null || cookie.isEmpty()) return null;
        for (String part : cookie.split(";")) {
            String t = part.trim();
            if (t.startsWith(COOKIE + "=")) {
                String v = t.substring(COOKIE.length() + 1).trim();
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    private static String clientIp(Socket s) {
        try {
            return s.getInetAddress() == null ? "?" : s.getInetAddress().getHostAddress();
        } catch (Throwable e) {
            return "?";
        }
    }

    // ---------------- 响应 ----------------

    private void sendHtml(Socket s, int code, String html, String extra) throws Exception {
        send(s, code, "OK", "text/html; charset=utf-8",
                html.getBytes(StandardCharsets.UTF_8), extra);
    }

    private void sendJson(Socket s, int code, String json) throws Exception {
        send(s, code, code == 200 ? "OK" : "ERR", "application/json; charset=utf-8",
                json.getBytes(StandardCharsets.UTF_8), null);
    }

    private void sendRedirect(Socket s, String location, String extra) throws Exception {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 302 Found\r\n");
        head.append("Location: ").append(location).append("\r\n");
        head.append("Content-Length: 0\r\n");
        head.append("Cache-Control: no-store\r\n");
        if (extra != null && !extra.isEmpty()) head.append(extra);
        head.append("Connection: close\r\n\r\n");
        OutputStream os = s.getOutputStream();
        os.write(head.toString().getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    private void send(Socket s, int code, String reason, String ctype,
                      byte[] body, String extra) throws Exception {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        head.append("Content-Type: ").append(ctype).append("\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Cache-Control: no-store\r\n");
        head.append("X-Content-Type-Options: nosniff\r\n");
        head.append("X-Frame-Options: DENY\r\n");
        head.append("Referrer-Policy: no-referrer\r\n");
        if (extra != null && !extra.isEmpty()) head.append(extra);
        head.append("Connection: close\r\n\r\n");
        OutputStream os = s.getOutputStream();
        os.write(head.toString().getBytes(StandardCharsets.UTF_8));
        os.write(body);
        os.flush();
    }

    // ---------------- 工具 ----------------

    private static Map<String, String> parse(String q) {
        Map<String, String> m = new HashMap<>();
        if (q == null || q.isEmpty()) return m;
        for (String pair : q.split("&")) {
            if (pair.isEmpty()) continue;
            int i = pair.indexOf('=');
            String k = i < 0 ? pair : pair.substring(0, i);
            String v = i < 0 ? "" : pair.substring(i + 1);
            try {
                m.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
            } catch (Throwable ignored) {
            }
        }
        return m;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable e) {
            return def;
        }
    }

    private static final SecureRandom RND = new SecureRandom();
    private static final char[] ALNUM =
            "abcdefghijkmnpqrstuvwxyz23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();

    private static String randomAlnum(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(ALNUM[RND.nextInt(ALNUM.length)]);
        return sb.toString();
    }

    private static String randomHex(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(Integer.toHexString(RND.nextInt(16)));
        return sb.toString();
    }
}
