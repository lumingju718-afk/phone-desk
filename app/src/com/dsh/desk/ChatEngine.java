package com.dsh.desk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对话状态机：
 *   用户消息入队(pending) → 选应答引擎 → 成功则追加 ai 消息并把源消息置 sent
 *   → 失败/超时置 error（可重试）
 */
public class ChatEngine {

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 中继模式等我来回复，给足时间 */
    private static final long RELAY_TIMEOUT_MS = 15L * 60 * 1000;

    public static ChatBrain brainFor(Context c) {
        String mode = ChatConfig.mode(c);
        if (ChatConfig.MODE_OPENAI.equals(mode)) return new OpenAiBrain(c);
        if (ChatConfig.MODE_ASTRBOT.equals(mode)) return new AstrBotBrain();
        return new RelayBrain();
    }

    public static void dispatch(Context ctx, long msgId) {
        final Context c = ctx.getApplicationContext();
        EXEC.execute(new Runnable() {
            @Override public void run() {
                runOnce(c, msgId);
            }
        });
    }

    private static void runOnce(final Context c, final long msgId) {
        JSONObject msg = ChatStore.find(c, msgId);
        if (msg == null) return;
        if (!ChatStore.ST_PENDING.equals(msg.optString("status"))) return;

        final ChatBrain brain = brainFor(c);
        final List<JSONObject> history = ChatStore.history(c, 20);
        final AtomicBoolean done = new AtomicBoolean(false);

        long timeoutMs = (brain instanceof RelayBrain)
                ? RELAY_TIMEOUT_MS
                : ChatConfig.timeoutSeconds(c) * 1000L;

        Runnable timeout = new Runnable() {
            @Override public void run() {
                if (!done.compareAndSet(false, true)) return;
                ChatStore.updateStatus(c, msgId, ChatStore.ST_ERROR, "等待应答超时");
            }
        };
        MAIN.postDelayed(timeout, timeoutMs);

        brain.ask(c, history, new ChatBrain.Callback() {
            @Override public void ok(String text) {
                if (!done.compareAndSet(false, true)) return;
                MAIN.removeCallbacks(timeout);
                ChatStore.append(c, ChatStore.CONV_MAIN, ChatStore.ROLE_AI,
                        text, ChatStore.ST_SENT, msgId);
                ChatStore.updateStatus(c, msgId, ChatStore.ST_SENT, null);
                ChatStore.notify(c, text);
            }

            @Override public void fail(String reason) {
                if (!done.compareAndSet(false, true)) return;
                MAIN.removeCallbacks(timeout);
                ChatStore.updateStatus(c, msgId, ChatStore.ST_ERROR,
                        reason == null || reason.isEmpty() ? "发送失败" : reason);
            }
        });
    }

    /** 重试：把失败消息重置为 pending 再派发 */
    public static void retry(Context c, long msgId) {
        JSONObject msg = ChatStore.find(c, msgId);
        if (msg == null) return;
        if (!ChatStore.ROLE_USER.equals(msg.optString("role"))) return;
        ChatStore.updateStatus(c, msgId, ChatStore.ST_PENDING, null);
        dispatch(c, msgId);
    }
}
