package com.dsh.desk;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 HTTP 控制端：
 *   GET /ping
 *   GET|POST /exec?token=xxx&cmd=<urlencoded>
 * 只监听局域网，用于远程接管手机（命令以本应用权限执行）。
 */
public class HttpExecServer {

    private static final String PROOT_ROOTFS =
            "/data/data/com.termux/files/usr/var/lib/proot-distro/containers/ubuntu-base/rootfs";
    private static final String LOG_NAPCAT = PROOT_ROOTFS + "/root/napcat.log";
    private static final String LOG_ASTRBOT = PROOT_ROOTFS + "/root/astrbot.log";

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

        if ("OPTIONS".equals(method)) {
            respond(s, 200, "{\"ok\":true}");
            return;
        }

        if (path.equals("/ping")) {
            respond(s, 200, "{\"ok\":true,\"service\":\"desk-exec\"}");
            return;
        }

        if (path.equals("/status")) {
            respond(s, 200, StatusApi.collect(context).toString());
            return;
        }

        if (path.equals("/token")) {
            String t = params.get("token");
            if (t == null || !t.equals(token)) {
                respond(s, 403, "{\"ok\":false,\"error\":\"bad token\"}");
                return;
            }
            String nt = NapCatToken.fetch(context);
            JSONObject o = new JSONObject();
            o.put("ok", nt != null && !nt.isEmpty());
            o.put("token", nt == null ? "" : nt);
            respond(s, 200, o.toString());
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

        if (path.equals("/logs")) {
            String t = params.get("token");
            if (t == null || !t.equals(token)) {
                respond(s, 403, "{\"ok\":false,\"error\":\"bad token\"}");
                return;
            }
            String which = params.get("which");
            if (which == null || which.isEmpty()) which = "napcat";
            if (!which.equals("napcat") && !which.equals("astrbot")) {
                respond(s, 400, "{\"ok\":false,\"error\":\"which must be napcat|astrbot\"}");
                return;
            }
            int lines = parseInt(params.get("lines"), 200);
            if (lines < 1) lines = 1;
            if (lines > 500) lines = 500;
            String logPath = which.equals("napcat") ? LOG_NAPCAT : LOG_ASTRBOT;
            Shell.Result r = TermuxRunner.run(context,
                    "tail -n " + lines + " '" + logPath + "'", 15, token, port);
            JSONObject o = new JSONObject();
            o.put("ok", r.code == 0);
            o.put("which", which);
            o.put("code", r.code);
            o.put("text", r.out);
            respond(s, 200, o.toString());
            return;
        }

        if (path.equals("/chat/send")) {
            String t = params.get("token");
            if (t == null || !t.equals(token)) {
                respond(s, 403, "{\"ok\":false,\"error\":\"bad token\"}");
                return;
            }
            String from = params.get("from");
            if (from == null || from.isEmpty()) from = "user";
            if (!from.equals("ai") && !from.equals("user")) {
                respond(s, 400, "{\"ok\":false,\"error\":\"from must be ai|user\"}");
                return;
            }
            String text = params.get("text");
            if (text == null || text.trim().isEmpty()) {
                respond(s, 400, "{\"ok\":false,\"error\":\"missing text\"}");
                return;
            }
            if (text.length() > 2000) text = text.substring(0, 2000);
            long replyTo = parseLong(params.get("reply_to"), 0);
            String status = "user".equals(from) ? ChatStore.ST_PENDING : ChatStore.ST_SENT;
            long id = ChatStore.append(context, ChatStore.CONV_MAIN, from, text, status, replyTo);
            if ("ai".equals(from)) {
                if (replyTo > 0) ChatStore.updateStatus(context, replyTo, ChatStore.ST_SENT, null);
                ChatStore.notify(context, text);
            } else {
                ChatEngine.dispatch(context, id);
            }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("id", id);
            o.put("status", status);
            respond(s, 200, o.toString());
            return;
        }

        if (path.equals("/chat")) {
            String t = params.get("token");
            if (t == null || !t.equals(token)) {
                respond(s, 403, "{\"ok\":false,\"error\":\"bad token\"}");
                return;
            }
            long since = parseLong(params.get("since"), 0);
            int limit = parseInt(params.get("limit"), 100);
            if (limit < 1) limit = 1;
            if (limit > 200) limit = 200;
            ChatStore.expireStale(context, 15L * 60 * 1000);
            List<JSONObject> msgs = ChatStore.read(context, since, limit);
            JSONArray arr = new JSONArray();
            for (JSONObject m : msgs) arr.put(m);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("messages", arr);
            o.put("pending", ChatStore.pending(context).size());
            o.put("unread", ChatStore.unread(context));
            respond(s, 200, o.toString());
            return;
        }

        if (path.equals("/chat/retry")) {
            String t = params.get("token");
            if (t == null || !t.equals(token)) {
                respond(s, 403, "{\"ok\":false,\"error\":\"bad token\"}");
                return;
            }
            long id = parseLong(params.get("id"), 0);
            JSONObject m = ChatStore.find(context, id);
            if (m == null || !ChatStore.ROLE_USER.equals(m.optString("role"))) {
                respond(s, 400, "{\"ok\":false,\"error\":\"no such user message\"}");
                return;
            }
            ChatEngine.retry(context, id);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("id", id);
            o.put("status", ChatStore.ST_PENDING);
            respond(s, 200, o.toString());
            return;
        }

        if (path.equals("/chat/brain")) {
            String t = params.get("token");
            if (t == null || !t.equals(token)) {
                respond(s, 403, "{\"ok\":false,\"error\":\"bad token\"}");
                return;
            }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("mode", ChatConfig.mode(context));
            o.put("available", ChatConfig.brainReady(context));
            o.put("pending", ChatStore.pending(context).size());
            respond(s, 200, o.toString());
            return;
        }

        if (path.equals("/chat/config")) {
            String t = params.get("token");
            if (t == null || !t.equals(token)) {
                respond(s, 403, "{\"ok\":false,\"error\":\"bad token\"}");
                return;
            }
            JSONObject cfg = ChatConfig.load(context);
            String[] keys = {"mode", "base_url", "api_key", "model", "system_prompt",
                    "timeout_s", "astrbot_url", "astrbot_user", "astrbot_pass",
                    "astrbot_session"};
            boolean changed = false;
            for (String k : keys) {
                if (params.containsKey(k)) {
                    String v = params.get(k);
                    if ("timeout_s".equals(k)) {
                        cfg.put(k, parseInt(v, 120));
                    } else {
                        cfg.put(k, v == null ? "" : v);
                    }
                    changed = true;
                }
            }
            if (changed) ChatConfig.save(context, cfg);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("config", ChatConfig.publicView(context));
            respond(s, 200, o.toString());
            return;
        }

        if (path.equals("/chat/read")) {
            String t = params.get("token");
            if (t == null || !t.equals(token)) {
                respond(s, 403, "{\"ok\":false,\"error\":\"bad token\"}");
                return;
            }
            long id = parseLong(params.get("id"), 0);
            ChatStore.markReadUpTo(context, id);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("unread", ChatStore.unread(context));
            respond(s, 200, o.toString());
            return;
        }

        if (path.equals("/svc")) {
            String t = params.get("token");
            if (t == null || !t.equals(token)) {
                respond(s, 403, "{\"ok\":false,\"error\":\"bad token\"}");
                return;
            }
            String name = params.get("name");
            if (name == null || name.isEmpty()) name = "napcat";
            if (!name.equals("napcat") && !name.equals("astrbot")) {
                respond(s, 400, "{\"ok\":false,\"error\":\"name must be napcat|astrbot\"}");
                return;
            }
            String action = params.get("action");
            if (action == null || action.isEmpty()) action = "restart";
            if (!action.equals("start") && !action.equals("stop") && !action.equals("restart")) {
                respond(s, 400, "{\"ok\":false,\"error\":\"action must be start|stop|restart\"}");
                return;
            }
            Shell.Result r = SvcControl.exec(context, name, action, token, port);
            JSONObject o = new JSONObject();
            o.put("ok", r.code == 0);
            o.put("name", name);
            o.put("action", action);
            o.put("code", r.code);
            o.put("out", r.out);
            o.put("running", SvcControl.isRunning(name));
            respond(s, 200, o.toString());
            return;
        }

        if (path.equals("/webauth")) {
            String t = params.get("token");
            if (t == null || !t.equals(token)) {
                respond(s, 403, "{\"ok\":false,\"error\":\"bad token\"}");
                return;
            }
            String np = params.get("pass");
            JSONObject o = new JSONObject();
            if (np != null && !np.isEmpty()) {
                if (np.length() < 6) {
                    respond(s, 400, "{\"ok\":false,\"error\":\"pass too short (>=6)\"}");
                    return;
                }
                o.put("pass", WebUiServer.setPassword(context, np));
                o.put("changed", true);
            } else {
                o.put("pass", WebUiServer.password(context));
                o.put("changed", false);
            }
            o.put("ok", true);
            o.put("port", WebUiServer.PORT);
            respond(s, 200, o.toString());
            return;
        }

        if (path.startsWith("/ui")) {
            handleUi(path, params, s);
            return;
        }

        if (path.equals("/") || path.isEmpty()) {
            respond(s, 200, "{\"ok\":true,\"endpoints\":["
                    + "\"/ping\",\"/exec?token=&cmd=\",\"/termux?token=&cmd=\","
                    + "\"/status\",\"/token?token=\","
                    + "\"/logs?token=&which=napcat|astrbot\","
                    + "\"/chat?token=\",\"/chat/send?token=&from=ai|user&text=&reply_to=\","
                    + "\"/chat/retry?token=&id=\",\"/chat/config?token=&mode=\",\"/chat/brain?token=\","
                    + "\"/svc?token=&name=napcat|astrbot&action=start|stop|restart\","
                    + "\"/webauth?token=&pass=\"],\"web_port\":8090}");
            return;
        }

        respond(s, 404, "{\"ok\":false,\"error\":\"not found\"}");
    }

    private void handleUi(String path, Map<String, String> params, Socket s) throws Exception {
        String t = params.get("token");
        if (t == null || !t.equals(token)) {
            respond(s, 403, "{\"ok\":false,\"error\":\"bad token\"}");
            return;
        }
        DeskAccessibilityService svc = DeskAccessibilityService.INSTANCE;

        if (path.equals("/ui/on")) {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("enabled", svc != null);
            if (svc == null) {
                o.put("hint", "enable in Settings > Accessibility > desk");
            }
            respond(s, 200, o.toString());
            return;
        }

        if (svc == null) {
            respond(s, 503, "{\"ok\":false,\"error\":\"accessibility service not enabled\","
                    + "\"hint\":\"Settings > Accessibility > enable desk\"}");
            return;
        }

        JSONObject o = new JSONObject();
        try {
            if (path.equals("/ui/shot")) {
                int w = parseInt(params.get("w"), 1080);
                String b64 = svc.screenshotBase64(w);
                if (b64 == null) {
                    o.put("ok", false);
                    o.put("error", "screenshot failed or unsupported");
                } else {
                    o.put("ok", true);
                    o.put("mime", "image/jpeg");
                    o.put("b64", b64);
                }
            } else if (path.equals("/ui/dump")) {
                int depth = parseInt(params.get("depth"), 20);
                respond(s, 200, svc.dumpJson(depth).toString());
                return;
            } else if (path.equals("/ui/tap")) {
                float x = parseFloat(params.get("x"), -1);
                float y = parseFloat(params.get("y"), -1);
                o.put("ok", x >= 0 && y >= 0 && svc.tap(x, y));
                o.put("x", x);
                o.put("y", y);
            } else if (path.equals("/ui/long")) {
                o.put("ok", svc.longPress(
                        parseFloat(params.get("x"), -1),
                        parseFloat(params.get("y"), -1),
                        parseInt(params.get("ms"), 600)));
            } else if (path.equals("/ui/swipe")) {
                o.put("ok", svc.swipe(
                        parseFloat(params.get("x1"), -1),
                        parseFloat(params.get("y1"), -1),
                        parseFloat(params.get("x2"), -1),
                        parseFloat(params.get("y2"), -1),
                        parseInt(params.get("ms"), 300)));
            } else if (path.equals("/ui/global")) {
                String a = params.get("a");
                o.put("ok", svc.global(a));
                o.put("action", a == null ? "" : a);
            } else if (path.equals("/ui/text")) {
                String text = params.get("text");
                o.put("ok", text != null && svc.setText(text));
            } else if (path.equals("/ui/click")) {
                String text = params.get("text");
                String id = params.get("id");
                boolean ok = false;
                if (text != null && !text.isEmpty()) ok = svc.clickText(text);
                else if (id != null && !id.isEmpty()) ok = svc.clickText(id);
                o.put("ok", ok);
            } else {
                respond(s, 404, "{\"ok\":false,\"error\":\"unknown ui endpoint\"}");
                return;
            }
        } catch (Throwable e) {
            o = new JSONObject();
            o.put("ok", false);
            o.put("error", String.valueOf(e.getMessage()));
        }
        respond(s, 200, o.toString());
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable e) {
            return def;
        }
    }

    private static float parseFloat(String s, float def) {
        try {
            return Float.parseFloat(s.trim());
        } catch (Throwable e) {
            return def;
        }
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable e) {
            return def;
        }
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
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: GET,POST,OPTIONS\r\n"
                + "Access-Control-Allow-Headers: *\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream os = s.getOutputStream();
        os.write(head.getBytes("UTF-8"));
        os.write(bytes);
        os.flush();
    }
}
