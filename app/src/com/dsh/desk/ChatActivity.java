package com.dsh.desk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.List;

/** 消息页：助手 → 手机 的消息，用户也可以在这里回复 */
public class ChatActivity extends Activity {

    private static final int BG = Color.parseColor("#0B1014");
    private static final int TEXT = Color.parseColor("#E6EDF3");
    private static final int DIM = Color.parseColor("#8B98A5");
    private static final int BLUE = Color.parseColor("#4DA3FF");
    private static final int BUBBLE_AI = Color.parseColor("#1E2A38");
    private static final int BUBBLE_ME = Color.parseColor("#1F4D36");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout bubbles;
    private ScrollView scroll;
    private EditText input;
    private long lastRenderedId = -1;
    private String lastSnapshot = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
        ui.post(refreshTick);
    }

    private View buildUi() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(BG);
        col.setPadding(dp(12), dp(10), dp(12), dp(8));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("← 返回", 13, BLUE, false);
        back.setPadding(dp(4), dp(4), dp(14), dp(4));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        head.addView(back);
        head.addView(text("消息", 18, TEXT, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView clear = text("清空", 12, DIM, false);
        clear.setPadding(dp(10), dp(4), dp(4), dp(4));
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmClear(); }
        });
        head.addView(clear);
        col.addView(head);

        scroll = new ScrollView(this);
        bubbles = new LinearLayout(this);
        bubbles.setOrientation(LinearLayout.VERTICAL);
        bubbles.setPadding(0, dp(6), 0, dp(6));
        scroll.addView(bubbles);
        col.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, 0);

        input = new EditText(this);
        input.setHint("给助手回一条…");
        input.setHintTextColor(DIM);
        input.setTextColor(TEXT);
        input.setTextSize(14);
        input.setMaxLines(3);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        row.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView send = text("发送", 14, TEXT, true);
        send.setGravity(Gravity.CENTER);
        send.setPadding(dp(16), dp(10), dp(16), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor("#1E6FD9"));
        g.setCornerRadius(dp(12));
        send.setBackground(g);
        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendMessage(); }
        });
        row.addView(send);
        col.addView(row);

        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                if (actionId == EditorInfo.IME_ACTION_SEND) { sendMessage(); return true; }
                return false;
            }
        });

        return col;
    }

    private final Runnable refreshTick = new Runnable() {
        @Override public void run() {
            refresh();
            ui.postDelayed(this, 2000);
        }
    };

    private void refresh() {
        List<JSONObject> msgs = ChatStore.read(this, 0, 0);
        StringBuilder sig = new StringBuilder();
        for (JSONObject o : msgs) {
            sig.append(o.optLong("id", 0)).append('|').append(o.optString("from"))
               .append('|').append(o.optString("text")).append('\n');
        }
        String snapshot = sig.toString();
        if (!snapshot.equals(lastSnapshot)) {
            lastSnapshot = snapshot;
            render(msgs);
        }
        if (msgs.size() > 0) {
            lastRenderedId = msgs.get(msgs.size() - 1).optLong("id", lastRenderedId);
        }
        ChatStore.markRead(this);
    }

    private void render(List<JSONObject> msgs) {
        bubbles.removeAllViews();
        if (msgs.isEmpty()) {
            TextView empty = text("还没有消息。\n我发消息到这里时，手机会收到通知。", 13, DIM, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(60), 0, 0);
            bubbles.addView(empty);
            return;
        }
        for (JSONObject o : msgs) {
            String from = o.optString("from", "ai");
            String text = o.optString("text", "");
            boolean ai = "ai".equals(from);

            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(ai ? Gravity.START : Gravity.END);
            line.setPadding(dp(2), dp(3), dp(2), dp(3));

            LinearLayout stack = new LinearLayout(this);
            stack.setOrientation(LinearLayout.VERTICAL);
            stack.setGravity(ai ? Gravity.START : Gravity.END);

            TextView who = text(ai ? "助手" : "我", 9, DIM, false);
            who.setPadding(dp(4), 0, dp(4), dp(2));
            stack.addView(who);

            TextView bubble = text(text, 14, TEXT, false);
            bubble.setPadding(dp(12), dp(8), dp(12), dp(8));
            GradientDrawable g = new GradientDrawable();
            g.setColor(ai ? BUBBLE_AI : BUBBLE_ME);
            g.setCornerRadius(dp(14));
            bubble.setBackground(g);
            bubble.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.78));
            stack.addView(bubble);

            line.addView(stack);
            bubbles.addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        scroll.post(new Runnable() {
            @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
        });
    }

    private void sendMessage() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        if (text.length() > 2000) {
            Toast.makeText(this, "消息太长（上限 2000 字）", Toast.LENGTH_SHORT).show();
            return;
        }
        ChatStore.append(this, "user", text);
        input.setText("");
        lastSnapshot = "";
        refresh();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("清空消息")
                .setMessage("会删除本机保存的全部聊天记录，确定吗？")
                .setPositiveButton("清空", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        ChatStore.clear(ChatActivity.this);
                        lastSnapshot = "";
                        refresh();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ChatStore.markRead(this);
        lastSnapshot = "";
        refresh();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
