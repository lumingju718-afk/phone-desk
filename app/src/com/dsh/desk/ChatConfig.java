package com.dsh.desk;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** 人机对话的应答配置（存在 SharedPreferences 的 chat_config 里） */
public class ChatConfig {

    public static final String MODE_RELAY = "relay";
    public static final String MODE_OPENAI = "openai";
    public static final String MODE_ASTRBOT = "astrbot";

    private static final String KEY = "chat_config";

    public static JSONObject load(Context c) {
        JSONObject d = defaults();
        try {
            SharedPreferences sp = c.getSharedPreferences(ExecService.PREFS, Context.MODE_PRIVATE);
            String raw = sp.getString(KEY, "");
            if (raw != null && !raw.isEmpty()) {
                JSONObject saved = new JSONObject(raw);
                java.util.Iterator<String> it = saved.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    d.put(k, saved.get(k));
                }
            }
        } catch (Throwable ignored) {
        }
        return d;
    }

    public static void save(Context c, JSONObject cfg) {
        try {
            SharedPreferences sp = c.getSharedPreferences(ExecService.PREFS, Context.MODE_PRIVATE);
            sp.edit().putString(KEY, cfg.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    public static String mode(Context c) {
        return load(c).optString("mode", MODE_RELAY);
    }

    public static int timeoutSeconds(Context c) {
        int t = load(c).optInt("timeout_s", 120);
        if (t < 15) t = 15;
        if (t > 600) t = 600;
        return t;
    }

    public static boolean brainReady(Context c) {
        String mode = mode(c);
        if (MODE_RELAY.equals(mode)) return true;
        if (MODE_OPENAI.equals(mode)) return !openaiBase(c).isEmpty();
        if (MODE_ASTRBOT.equals(mode)) return AstrBotBrain.available();
        return false;
    }

    public static String openaiBase(Context c) {
        String b = load(c).optString("base_url", "").trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    public static String openaiModel(Context c) {
        String m = load(c).optString("model", "").trim();
        return m.isEmpty() ? "gpt-4o-mini" : m;
    }

    public static String openaiKey(Context c) {
        return load(c).optString("api_key", "").trim();
    }

    public static String systemPrompt(Context c) {
        return load(c).optString("system_prompt",
                "你是这台安卓服务器的助手，回答简洁、直接、用中文。");
    }

    /** 给 HTTP 接口展示用：key 打码 */
    public static JSONObject publicView(Context c) {
        JSONObject cfg = load(c);
        try {
            String key = cfg.optString("api_key", "");
            if (key.length() > 6) {
                cfg.put("api_key", key.substring(0, 3) + "***" + key.substring(key.length() - 3));
            } else if (!key.isEmpty()) {
                cfg.put("api_key", "***");
            }
            cfg.put("available", brainReady(c));
        } catch (Throwable ignored) {
        }
        return cfg;
    }

    public static JSONObject defaults() {
        JSONObject d = new JSONObject();
        try {
            d.put("mode", MODE_RELAY);
            d.put("base_url", "");
            d.put("api_key", "");
            d.put("model", "gpt-4o-mini");
            d.put("system_prompt", "你是这台安卓服务器的助手，回答简洁、直接、用中文。");
            d.put("timeout_s", 120);
            d.put("astrbot_url", "http://127.0.0.1:6185");
            d.put("astrbot_user", "astrbot");
            d.put("astrbot_pass", "");
            d.put("astrbot_session", "dsh-desk");
        } catch (Throwable ignored) {
        }
        return d;
    }
}
