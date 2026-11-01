package com.dsh.desk;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 应答引擎：把一条用户消息变成助手回复
 *
 * 三种实现：
 *  - RelayBrain  中继：不本地生成，等我通过 HTTP 回复（默认模式，超时由 ChatEngine 负责）
 *  - OpenAiBrain 任意 OpenAI 兼容的 /chat/completions
 *  - AstrBotBrain 复用手机上 AstrBot 的模型（登录拿 JWT，再走 /api/v1/chat 的 SSE 流）
 */
public interface ChatBrain {

    void ask(Context c, List<JSONObject> history, Callback cb);

    interface Callback {
        void ok(String text);

        void fail(String reason);
    }
}

/** 中继模式：什么也不做，等外部助手回复 */
class RelayBrain implements ChatBrain {
    @Override
    public void ask(Context c, List<JSONObject> history, Callback cb) {
        // 故意不回调：消息保持「等待应答」，由 /chat/send?from=ai&reply_to= 来完结
    }
}

/** OpenAI 兼容接口 */
class OpenAiBrain implements ChatBrain {

    private final String base;
    private final String key;
    private final String model;
    private final String system;

    OpenAiBrain(Context c) {
        this.base = ChatConfig.openaiBase(c);
        this.key = ChatConfig.openaiKey(c);
        this.model = ChatConfig.openaiModel(c);
        this.system = ChatConfig.systemPrompt(c);
    }

    @Override
    public void ask(Context c, List<JSONObject> history, Callback cb) {
        HttpURLConnection conn = null;
        try {
            String url = base.contains("/v1") ? base + "/chat/completions"
                    : base + "/v1/chat/completions";
            JSONArray msgs = new JSONArray();
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", system);
            msgs.put(sys);
            for (JSONObject m : history) {
                JSONObject j = new JSONObject();
                j.put("role", ChatStore.ROLE_AI.equals(m.optString("role")) ? "assistant" : "user");
                j.put("content", m.optString("text"));
                msgs.put(j);
            }
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("messages", msgs);
            body.put("temperature", 0.7);

            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(180000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            if (!key.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + key);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            String resp = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code != 200) {
                cb.fail("HTTP " + code + " " + shorten(resp));
                return;
            }
            JSONObject j = new JSONObject(resp);
            String text = j.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content", "");
            if (text.trim().isEmpty()) cb.fail("模型返回空内容");
            else cb.ok(text.trim());
        } catch (Throwable e) {
            cb.fail(String.valueOf(e.getMessage()));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String shorten(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }

    private static String readAll(java.io.InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }
}

/** AstrBot（跑在这台手机上）的模型 */
class AstrBotBrain implements ChatBrain {

    private static String token = "";
    private static long tokenAt = 0;

    static boolean available() {
        return StatusApi.portOpen(6185);
    }

    @Override
    public void ask(Context c, List<JSONObject> history, Callback cb) {
        HttpURLConnection conn = null;
        try {
            JSONObject cfg = ChatConfig.load(c);
            String base = cfg.optString("astrbot_url", "http://127.0.0.1:6185").trim();
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            String user = cfg.optString("astrbot_user", "astrbot");
            String pass = cfg.optString("astrbot_pass", "");
            String session = cfg.optString("astrbot_session", "dsh-desk");

            String jwt = token(c, base, user, pass);
            if (jwt == null) {
                cb.fail("AstrBot 登录失败");
                return;
            }

            String lastUser = "";
            for (int i = history.size() - 1; i >= 0; i--) {
                JSONObject m = history.get(i);
                if (ChatStore.ROLE_USER.equals(m.optString("role"))) {
                    lastUser = m.optString("text");
                    break;
                }
            }
            if (lastUser.isEmpty()) {
                cb.fail("没有可发送的内容");
                return;
            }

            JSONObject body = new JSONObject();
            body.put("message", lastUser);
            body.put("session_id", session);

            conn = (HttpURLConnection) new URL(base + "/api/v1/chat").openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(300000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Authorization", "Bearer " + jwt);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                cb.fail("AstrBot HTTP " + code);
                return;
            }

            StringBuilder acc = new StringBuilder();
            String complete = null;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String payload = line.substring(5).trim();
                    if (payload.isEmpty()) continue;
                    try {
                        JSONObject ev = new JSONObject(payload);
                        String type = ev.optString("type");
                        if ("plain".equals(type)) {
                            acc.append(ev.optString("data"));
                        } else if ("complete".equals(type)) {
                            complete = ev.optString("data");
                            break;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            String text = complete != null ? complete : acc.toString();
            text = text.trim();
            if (text.isEmpty()) {
                cb.fail("AstrBot 没有返回内容");
            } else if (text.startsWith("LLM 响应错误")) {
                cb.fail(text.length() > 200 ? text.substring(0, 200) : text);
            } else {
                cb.ok(text);
            }
        } catch (Throwable e) {
            cb.fail(String.valueOf(e.getMessage()));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static synchronized String token(Context c, String base, String user, String pass) {
        if (!token.isEmpty() && System.currentTimeMillis() - tokenAt < 6L * 3600 * 1000) {
            return token;
        }
        HttpURLConnection conn = null;
        try {
            JSONObject body = new JSONObject();
            body.put("username", user);
            body.put("password", pass);
            conn = (HttpURLConnection) new URL(base + "/api/auth/login").openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (conn.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            JSONObject j = new JSONObject(sb.toString());
            String t = j.optJSONObject("data") != null
                    ? j.getJSONObject("data").optString("token", "") : "";
            if (t.isEmpty()) return null;
            token = t;
            tokenAt = System.currentTimeMillis();
            return token;
        } catch (Throwable e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
