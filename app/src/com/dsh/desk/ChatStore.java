package com.dsh.desk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 对话存储 v2（JSON Lines，应用私有目录）
 *
 * 单条消息：{id, conv, role, from, text, ts, status, reply_to, err}
 *  - role/from：user | ai | system（from 保留给旧接口与 ESP32 网页）
 *  - status：pending 已入队待应答 / sent 已回复 / error 失败
 *  - 老数据没有 role/status 时按 from + sent 惰性兼容，不回写文件
 */
public class ChatStore {

    public static final String ROLE_USER = "user";
    public static final String ROLE_AI = "ai";
    public static final String ROLE_SYSTEM = "system";

    public static final String ST_PENDING = "pending";
    public static final String ST_SENT = "sent";
    public static final String ST_ERROR = "error";

    public static final String CONV_MAIN = "main";

    private static final Object LOCK = new Object();
    private static final String FILE_NAME = "chat.jsonl";
    private static final int MAX_MESSAGES = 500;
    private static final String PREF_LAST_READ = "chat_last_read";
    private static final String PREF_LAST_READ_ID = "chat_last_read_id";

    public static File file(Context c) {
        return new File(c.getFilesDir(), FILE_NAME);
    }

    // ---------------- 写 ----------------

    /** 追加一条消息，返回 id */
    public static long append(Context c, String conv, String role, String text,
                              String status, long replyTo) {
        synchronized (LOCK) {
            List<String> lines = readLines(c);
            long maxId = 0;
            for (String l : lines) {
                try {
                    maxId = Math.max(maxId, new JSONObject(l).optLong("id", 0));
                } catch (Throwable ignored) {
                }
            }
            long now = System.currentTimeMillis();
            long id = Math.max(now, maxId + 1);
            try {
                JSONObject o = new JSONObject();
                o.put("id", id);
                o.put("conv", conv == null ? CONV_MAIN : conv);
                o.put("role", role);
                o.put("from", role);
                o.put("text", text == null ? "" : text);
                o.put("ts", now);
                o.put("status", status == null ? ST_SENT : status);
                if (replyTo > 0) o.put("reply_to", replyTo);
                lines.add(o.toString());
            } catch (Throwable ignored) {
            }
            while (lines.size() > MAX_MESSAGES) {
                lines.remove(0);
            }
            writeLines(c, lines);
            return id;
        }
    }

    /** 兼容旧调用：from=ai|user，按已回复处理 */
    public static long append(Context c, String from, String text) {
        return append(c, CONV_MAIN, from, text, ST_SENT, 0);
    }

    /** 更新某条消息的状态（找不到返回 false） */
    public static boolean updateStatus(Context c, long id, String status, String err) {
        synchronized (LOCK) {
            List<String> lines = readLines(c);
            boolean hit = false;
            List<String> out = new ArrayList<>(lines.size());
            for (String l : lines) {
                try {
                    JSONObject o = new JSONObject(l);
                    if (o.optLong("id", 0) == id) {
                        o.put("status", status);
                        if (err == null || err.isEmpty()) o.remove("err");
                        else o.put("err", err);
                        hit = true;
                        out.add(o.toString());
                        continue;
                    }
                } catch (Throwable ignored) {
                }
                out.add(l);
            }
            if (hit) writeLines(c, out);
            return hit;
        }
    }

    // ---------------- 读 ----------------

    /** 按 id 升序返回，惰性补齐 role/status；limit<=0 表示全部 */
    public static List<JSONObject> read(Context c, long sinceId, int limit) {
        synchronized (LOCK) {
            List<JSONObject> out = new ArrayList<>();
            for (String l : readLines(c)) {
                JSONObject o = normalize(l);
                if (o == null) continue;
                if (o.optLong("id", 0) > sinceId) out.add(o);
            }
            if (limit > 0 && out.size() > limit) {
                return new ArrayList<>(out.subList(out.size() - limit, out.size()));
            }
            return out;
        }
    }

    public static JSONObject find(Context c, long id) {
        for (JSONObject o : read(c, 0, 0)) {
            if (o.optLong("id", 0) == id) return o;
        }
        return null;
    }

    public static List<JSONObject> pending(Context c) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject o : read(c, 0, 0)) {
            if (ST_PENDING.equals(o.optString("status"))) out.add(o);
        }
        return out;
    }

    /** 把超过 olderThanMs 仍在 pending 的消息标为失败（打开页面/读接口时兜底） */
    public static int expireStale(Context c, long olderThanMs) {
        long now = System.currentTimeMillis();
        int n = 0;
        for (JSONObject o : pending(c)) {
            if (now - o.optLong("ts", 0) > olderThanMs) {
                updateStatus(c, o.optLong("id", 0), ST_ERROR, "超时未回复");
                n++;
            }
        }
        return n;
    }

    /** 给应答引擎用的对话历史（最后 limit 条，含 system 之外的 user/ai） */
    public static List<JSONObject> history(Context c, int limit) {
        List<JSONObject> all = read(c, 0, limit);
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject o : all) {
            String r = o.optString("role");
            if (ROLE_USER.equals(r) || ROLE_AI.equals(r)) out.add(o);
        }
        return out;
    }

    // ---------------- 未读 ----------------

    public static int unread(Context c) {
        SharedPreferences sp = c.getSharedPreferences(ExecService.PREFS, Context.MODE_PRIVATE);
        long lastId = sp.getLong(PREF_LAST_READ_ID, 0);
        long lastTs = sp.getLong(PREF_LAST_READ, 0);
        int n = 0;
        for (JSONObject o : read(c, 0, 0)) {
            if (!ROLE_AI.equals(o.optString("role"))) continue;
            if (lastId > 0) {
                if (o.optLong("id", 0) > lastId) n++;
            } else if (o.optLong("ts", 0) > lastTs) {
                n++;
            }
        }
        return n;
    }

    public static void markRead(Context c) {
        List<JSONObject> all = read(c, 0, 0);
        long maxId = 0;
        for (JSONObject o : all) {
            maxId = Math.max(maxId, o.optLong("id", 0));
        }
        SharedPreferences sp = c.getSharedPreferences(ExecService.PREFS, Context.MODE_PRIVATE);
        sp.edit().putLong(PREF_LAST_READ_ID, maxId)
                .putLong(PREF_LAST_READ, System.currentTimeMillis()).apply();
    }

    public static void markReadUpTo(Context c, long id) {
        if (id <= 0) return;
        SharedPreferences sp = c.getSharedPreferences(ExecService.PREFS, Context.MODE_PRIVATE);
        long cur = sp.getLong(PREF_LAST_READ_ID, 0);
        if (id > cur) sp.edit().putLong(PREF_LAST_READ_ID, id).apply();
    }

    public static void clear(Context c) {
        synchronized (LOCK) {
            File f = file(c);
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    /** 收到助手消息时发系统通知（带「直接回复」输入框） */
    public static void notify(Context c, String text) {
        try {
            NotificationManager nm = (NotificationManager)
                    c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            String ch = "desk_chat";
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(
                        ch, "消息", NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(channel);
            }
            Intent i = new Intent(c, ChatActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(c, 0, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            String preview = preview(text);
            Notification.Builder b = new Notification.Builder(c, ch)
                    .setContentTitle("服务器桌面 · 新消息")
                    .setContentText(preview)
                    .setStyle(new Notification.BigTextStyle().bigText(preview))
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentIntent(pi)
                    .setAutoCancel(true);

            try {
                RemoteInput ri = new RemoteInput.Builder(ChatReplyReceiver.REPLY_KEY)
                        .setLabel("回复…").build();
                Intent reply = new Intent(c, ChatReplyReceiver.class);
                reply.setAction(ChatReplyReceiver.ACTION_REPLY);
                PendingIntent pr = PendingIntent.getBroadcast(c, 7, reply,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                Notification.Action act = new Notification.Action.Builder(
                        android.R.drawable.ic_menu_send, "回复", pr)
                        .addRemoteInput(ri).build();
                b.addAction(act);
            } catch (Throwable ignored) {
            }

            nm.notify(2, b.build());
        } catch (Throwable ignored) {
        }
    }

    private static String preview(String text) {
        if (text == null) return "";
        String s = text.replaceAll("\\s+", " ").trim();
        if (s.length() > 140) s = s.substring(0, 140) + "…";
        return s;
    }

    // ---------------- 内部 ----------------

    private static JSONObject normalize(String line) {
        try {
            JSONObject o = new JSONObject(line);
            if (!o.has("role")) o.put("role", o.optString("from", ROLE_AI));
            if (!o.has("from")) o.put("from", o.optString("role", ROLE_AI));
            if (!o.has("status")) o.put("status", ST_SENT);
            if (!o.has("conv")) o.put("conv", CONV_MAIN);
            return o;
        } catch (Throwable e) {
            return null;
        }
    }

    private static List<String> readLines(Context c) {
        List<String> lines = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return lines;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.trim().isEmpty()) lines.add(line);
            }
        } catch (Throwable ignored) {
        }
        return lines;
    }

    private static void writeLines(Context c, List<String> lines) {
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(file(c)), StandardCharsets.UTF_8)) {
            for (String l : lines) {
                w.write(l);
                w.write('\n');
            }
        } catch (Throwable ignored) {
        }
    }
}
