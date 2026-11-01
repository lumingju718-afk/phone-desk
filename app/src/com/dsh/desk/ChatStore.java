package com.dsh.desk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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

/** 手机 ↔ 助手 的消息存储（JSON Lines，应用私有目录） */
public class ChatStore {

    private static final Object LOCK = new Object();
    private static final String FILE_NAME = "chat.jsonl";
    private static final int MAX_MESSAGES = 500;
    private static final String PREF_LAST_READ = "chat_last_read";

    public static File file(Context c) {
        return new File(c.getFilesDir(), FILE_NAME);
    }

    public static long append(Context c, String from, String text) {
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
                o.put("from", from);
                o.put("text", text);
                o.put("ts", now);
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

    public static List<JSONObject> read(Context c, long sinceId, int limit) {
        synchronized (LOCK) {
            List<JSONObject> out = new ArrayList<>();
            for (String l : readLines(c)) {
                try {
                    JSONObject o = new JSONObject(l);
                    if (o.optLong("id", 0) > sinceId) {
                        out.add(o);
                    }
                } catch (Throwable ignored) {
                }
            }
            if (limit > 0 && out.size() > limit) {
                return new ArrayList<>(out.subList(out.size() - limit, out.size()));
            }
            return out;
        }
    }

    public static int unread(Context c) {
        SharedPreferences sp = c.getSharedPreferences(ExecService.PREFS, Context.MODE_PRIVATE);
        long last = sp.getLong(PREF_LAST_READ, 0);
        int n = 0;
        for (JSONObject o : read(c, 0, 0)) {
            if ("ai".equals(o.optString("from")) && o.optLong("ts", 0) > last) {
                n++;
            }
        }
        return n;
    }

    public static void markRead(Context c) {
        c.getSharedPreferences(ExecService.PREFS, Context.MODE_PRIVATE)
                .edit().putLong(PREF_LAST_READ, System.currentTimeMillis()).apply();
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

    /** 收到助手消息时发系统通知 */
    public static void notify(Context c, String text) {
        try {
            NotificationManager nm = (NotificationManager)
                    c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            String ch = "desk_chat";
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(
                        ch, "消息", NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(channel);
            }
            Intent i = new Intent(c, ChatActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(c, 0, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(c, ch)
                    .setContentTitle("服务器桌面 · 新消息")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(2, n);
        } catch (Throwable ignored) {
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
