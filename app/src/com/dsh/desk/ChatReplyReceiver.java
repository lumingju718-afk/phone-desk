package com.dsh.desk;

import android.app.NotificationManager;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** 通知栏「直接回复」：收到 RemoteInput 的文本后，当成一条用户消息入队并派发 */
public class ChatReplyReceiver extends BroadcastReceiver {

    public static final String ACTION_REPLY = "com.dsh.desk.REPLY";
    public static final String REPLY_KEY = "reply_text";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_REPLY.equals(intent.getAction())) return;
        Bundle res = RemoteInput.getResultsFromIntent(intent);
        CharSequence cs = res == null ? null : res.getCharSequence(REPLY_KEY);
        String text = cs == null ? "" : cs.toString().trim();
        if (text.isEmpty()) return;
        if (text.length() > 2000) text = text.substring(0, 2000);

        Context app = context.getApplicationContext();
        long id = ChatStore.append(app, ChatStore.CONV_MAIN, ChatStore.ROLE_USER,
                text, ChatStore.ST_PENDING, 0);
        ChatEngine.dispatch(app, id);

        try {
            NotificationManager nm = (NotificationManager)
                    app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(2);
        } catch (Throwable ignored) {
        }
    }
}
