package com.dsh.desk;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.List;

/**
 * 消息页 · 人机对话
 *
 * 状态机：发出去 pending(等待应答) → 引擎回复后 sent(已回复) / error(失败，可重试)
 * 应答引擎可切换：中继（助手在本会话回复）/ OpenAI 兼容 / AstrBot
 */
public class ChatActivity extends Activity {

    private static final int BG = Color.parseColor("#0B1014");
    private static final int TEXT = Color.parseColor("#E6EDF3");
    private static final int DIM = Color.parseColor("#8B98A5");
    private static final int BLUE = Color.parseColor("#4DA3FF");
    private static final int AMBER = Color.parseColor("#FFC857");
    private static final int RED = Color.parseColor("#FF6B6B");
    private static final int GREEN = Color.parseColor("#3DDC84");
    private static final int BUBBLE_AI = Color.parseColor("#1E2A38");
    private static final int BUBBLE_ME = Color.parseColor("#1F4D36");

    private static final long STALE_MS = 15L * 60 * 1000;
    private static final int TICK_MS = 1500;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout bubbles;
    private ScrollView scroll;
    private EditText input;
    private TextView jump;
    private TextView banner;
    private String lastSnapshot = "";
    private boolean atBottom = true;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
        ui.post(refreshTick);
    }

    // ---------------- UI ----------------

    private View buildUi() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(BG);
        col.setPadding(dp(12), dp(10), dp(12), dp(8));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView back = text("← 返回", 13, BLUE, false);
        back.setPadding(dp(4), dp(4), dp(12), dp(4));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        head.addView(back);
        head.addView(text("消息", 18, TEXT, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView gear = text("⚙", 17, DIM, false);
        gear.setPadding(dp(10), dp(4), dp(10), dp(4));
        gear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSettings(); }
        });
        head.addView(gear);

        TextView clear = text("清空", 12, DIM, false);
        clear.setPadding(dp(6), dp(4), dp(4), dp(4));
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmClear(); }
        });
        head.addView(clear);
        col.addView(head);

        banner = text("", 11, DIM, false);
        banner.setPadding(dp(2), dp(2), dp(2), dp(4));
        banner.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSettings(); }
        });
        col.addView(banner);

        scroll = new ScrollView(this);
        bubbles = new LinearLayout(this);
        bubbles.setOrientation(LinearLayout.VERTICAL);
        bubbles.setPadding(0, dp(4), 0, dp(6));
        scroll.addView(bubbles);
        col.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        jump = text("↓ 有新消息，点这里回到底部", 12, TEXT, false);
        jump.setGravity(Gravity.CENTER);
        jump.setPadding(0, dp(6), 0, dp(6));
        GradientDrawable jg = new GradientDrawable();
        jg.setColor(Color.parseColor("#1E6FD9"));
        jg.setCornerRadius(dp(10));
        jump.setBackground(jg);
        jump.setVisibility(View.GONE);
        jump.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { scrollToBottom(); }
        });
        col.addView(jump);

        scroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override public void onScrollChange(View v, int x, int y, int ox, int oy) {
                atBottom = isAtBottom();
                if (atBottom) {
                    jump.setVisibility(View.GONE);
                    ChatStore.markRead(ChatActivity.this);
                }
            }
        });

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, 0);

        input = new EditText(this);
        input.setHint("说点什么…");
        input.setHintTextColor(DIM);
        input.setTextColor(TEXT);
        input.setTextSize(14);
        input.setMaxLines(4);
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
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendMessage();
                    return true;
                }
                return false;
            }
        });

        return col;
    }

    // ---------------- 刷新 ----------------

    private final Runnable refreshTick = new Runnable() {
        @Override public void run() {
            refresh();
            ui.postDelayed(this, TICK_MS);
        }
    };

    private void refresh() {
        List<JSONObject> msgs = ChatStore.read(this, 0, 0);
        StringBuilder sig = new StringBuilder();
        int pending = 0;
        for (JSONObject o : msgs) {
            sig.append(o.optLong("id", 0)).append('|')
               .append(o.optString("role")).append('|')
               .append(o.optString("status")).append('|')
               .append(o.optString("err")).append('|')
               .append(o.optString("text")).append('\n');
            if (ChatStore.ST_PENDING.equals(o.optString("status"))) pending++;
        }
        if (!sig.toString().equals(lastSnapshot)) {
            lastSnapshot = sig.toString();
            render(msgs, pending > 0);
        }
        updateBanner(pending);
        if (atBottom) ChatStore.markRead(this);
    }

    private void updateBanner(int pending) {
        String mode = ChatConfig.mode(this);
        String label;
        if (ChatConfig.MODE_OPENAI.equals(mode)) {
            label = "OpenAI · " + ChatConfig.openaiModel(this);
        } else if (ChatConfig.MODE_ASTRBOT.equals(mode)) {
            label = "AstrBot · 本机模型";
        } else {
            label = "中继 · 助手在这里回复";
        }
        StringBuilder sb = new StringBuilder("应答：").append(label);
        if (pending > 0) sb.append(" · ").append(pending).append(" 条等待应答");
        banner.setText(sb.toString());
        banner.setTextColor(pending > 0 ? AMBER : DIM);
    }

    private void render(List<JSONObject> msgs, boolean typing) {
        bubbles.removeAllViews();
        if (msgs.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(70), 0, 0);
            TextView t1 = text("还没有消息", 15, TEXT, true);
            t1.setGravity(Gravity.CENTER);
            TextView t2 = text("发一条试试——中继模式下我会在这里回复；\n右上角 ⚙ 可以换成 OpenAI 或本机 AstrBot。",
                    12, DIM, false);
            t2.setGravity(Gravity.CENTER);
            t2.setPadding(0, dp(8), 0, dp(14));
            TextView go = text("说一句", 13, TEXT, true);
            go.setGravity(Gravity.CENTER);
            go.setPadding(dp(18), dp(9), dp(18), dp(9));
            GradientDrawable gg = new GradientDrawable();
            gg.setColor(Color.parseColor("#1E6FD9"));
            gg.setCornerRadius(dp(11));
            go.setBackground(gg);
            go.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { input.requestFocus(); }
            });
            empty.addView(t1);
            empty.addView(t2);
            empty.addView(go);
            bubbles.addView(empty);
            return;
        }
        for (JSONObject o : msgs) {
            bubbles.addView(bubbleRow(o));
        }
        if (typing) {
            TextView t = text("助手正在输入…", 11, DIM, false);
            t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));
            t.setPadding(dp(6), dp(8), dp(6), dp(2));
            bubbles.addView(t);
        }
        scroll.post(new Runnable() {
            @Override public void run() {
                if (atBottom) {
                    scroll.fullScroll(View.FOCUS_DOWN);
                    jump.setVisibility(View.GONE);
                } else {
                    jump.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private View bubbleRow(final JSONObject o) {
        final boolean ai = ChatStore.ROLE_AI.equals(o.optString("role"));
        final boolean sys = ChatStore.ROLE_SYSTEM.equals(o.optString("role"));
        final String body = o.optString("text", "");
        final String status = o.optString("status", ChatStore.ST_SENT);
        final String err = o.optString("err", "");
        final long id = o.optLong("id", 0);

        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(ai || sys ? Gravity.START : Gravity.END);
        line.setPadding(dp(2), dp(3), dp(2), dp(3));

        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setGravity(ai || sys ? Gravity.START : Gravity.END);

        TextView who = text(ai ? "助手" : (sys ? "系统" : "我"), 9, DIM, false);
        who.setPadding(dp(4), 0, dp(4), dp(2));
        stack.addView(who);

        TextView bubble = text(body, 14, TEXT, false);
        bubble.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable g = new GradientDrawable();
        g.setColor(ai ? BUBBLE_AI : (sys ? Color.parseColor("#2A2320") : BUBBLE_ME));
        g.setCornerRadius(dp(14));
        bubble.setBackground(g);
        bubble.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.78));
        bubble.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copyText(body); }
        });
        bubble.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                if (ai || sys) {
                    copyText(body);
                } else {
                    ChatEngine.retry(ChatActivity.this, id);
                    lastSnapshot = "";
                    refresh();
                    Toast.makeText(ChatActivity.this, "已重发", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
        stack.addView(bubble);

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(ai || sys ? Gravity.START : Gravity.END);
        meta.setPadding(dp(4), dp(2), dp(4), 0);

        TextView tm = text(fmtTime(o.optLong("ts", 0)), 9, DIM, false);
        meta.addView(tm);

        if (!ai && !sys) {
            TextView st;
            int color;
            if (ChatStore.ST_PENDING.equals(status)) {
                st = text("  ⏳ 等待应答", 9, AMBER, false);
                color = AMBER;
            } else if (ChatStore.ST_ERROR.equals(status)) {
                st = text("  ⚠ " + (err.isEmpty() ? "发送失败" : err) + " · 点按重试", 9, RED, false);
                color = RED;
                st.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        ChatEngine.retry(ChatActivity.this, id);
                        lastSnapshot = "";
                        refresh();
                    }
                });
            } else {
                st = text("  ✓ 已回复", 9, GREEN, false);
                color = GREEN;
            }
            st.setTextColor(color);
            meta.addView(st);
        }
        stack.addView(meta);

        line.addView(stack);
        return line;
    }

    // ---------------- 动作 ----------------

    private void sendMessage() {
        String t = input.getText().toString().trim();
        if (t.isEmpty()) return;
        if (t.length() > 2000) {
            Toast.makeText(this, "消息太长（上限 2000 字）", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ChatConfig.brainReady(this)) {
            Toast.makeText(this, "当前应答模式未配置好，去 ⚙ 里填", Toast.LENGTH_LONG).show();
        }
        long id = ChatStore.append(this, ChatStore.CONV_MAIN, ChatStore.ROLE_USER,
                t, ChatStore.ST_PENDING, 0);
        input.setText("");
        atBottom = true;
        lastSnapshot = "";
        refresh();
        scrollToBottom();
        ChatEngine.dispatch(this, id);
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

    private void showSettings() {
        final JSONObject cfg = ChatConfig.load(this);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(6), dp(16), dp(6));

        final RadioGroup rg = new RadioGroup(this);
        final RadioButton rbRelay = new RadioButton(this);
        rbRelay.setId(101);
        rbRelay.setText("中继（我在这里回复）");
        rbRelay.setTextColor(TEXT);
        final RadioButton rbOa = new RadioButton(this);
        rbOa.setId(102);
        rbOa.setText("OpenAI 兼容接口");
        rbOa.setTextColor(TEXT);
        final RadioButton rbAb = new RadioButton(this);
        rbAb.setId(103);
        rbAb.setText("AstrBot（本机 6185）");
        rbAb.setTextColor(TEXT);
        rg.addView(rbRelay);
        rg.addView(rbOa);
        rg.addView(rbAb);

        String mode = cfg.optString("mode", ChatConfig.MODE_RELAY);
        if (ChatConfig.MODE_OPENAI.equals(mode)) rg.check(102);
        else if (ChatConfig.MODE_ASTRBOT.equals(mode)) rg.check(103);
        else rg.check(101);
        box.addView(grow("应答模式", 12, DIM));
        box.addView(rg);

        final EditText eBase = field("Base URL，例如 https://api.openai.com/v1",
                cfg.optString("base_url", ""), false);
        final EditText eKey = field("API Key", cfg.optString("api_key", ""), true);
        final EditText eModel = field("模型", cfg.optString("model", "gpt-4o-mini"), false);
        final EditText eSys = field("系统提示词", cfg.optString("system_prompt", ""), false);
        eSys.setMaxLines(3);

        final EditText eAbUrl = field("AstrBot 地址",
                cfg.optString("astrbot_url", "http://127.0.0.1:6185"), false);
        final EditText eAbUser = field("AstrBot 账号", cfg.optString("astrbot_user", "astrbot"), false);
        final EditText eAbPass = field("AstrBot 密码", cfg.optString("astrbot_pass", ""), true);
        final EditText eAbSess = field("AstrBot 会话 ID",
                cfg.optString("astrbot_session", "dsh-desk"), false);

        box.addView(grow("OpenAI 兼容", 12, DIM));
        box.addView(eBase);
        box.addView(eKey);
        box.addView(eModel);
        box.addView(eSys);

        box.addView(grow("AstrBot", 12, DIM));
        box.addView(eAbUrl);
        box.addView(eAbUser);
        box.addView(eAbPass);
        box.addView(eAbSess);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("应答设置")
                .setView(sv)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try {
                            JSONObject out = ChatConfig.load(ChatActivity.this);
                            int id = rg.getCheckedRadioButtonId();
                            out.put("mode", id == 102 ? ChatConfig.MODE_OPENAI
                                    : id == 103 ? ChatConfig.MODE_ASTRBOT
                                    : ChatConfig.MODE_RELAY);
                            out.put("base_url", eBase.getText().toString().trim());
                            out.put("api_key", eKey.getText().toString().trim());
                            out.put("model", eModel.getText().toString().trim());
                            out.put("system_prompt", eSys.getText().toString().trim());
                            out.put("astrbot_url", eAbUrl.getText().toString().trim());
                            out.put("astrbot_user", eAbUser.getText().toString().trim());
                            out.put("astrbot_pass", eAbPass.getText().toString().trim());
                            out.put("astrbot_session", eAbSess.getText().toString().trim());
                            ChatConfig.save(ChatActivity.this, out);
                            lastSnapshot = "";
                            refresh();
                            Toast.makeText(ChatActivity.this, "已保存", Toast.LENGTH_SHORT).show();
                        } catch (Throwable t) {
                            Toast.makeText(ChatActivity.this, "保存失败: " + t, Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private EditText field(String hint, String value, boolean password) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(DIM);
        e.setTextColor(TEXT);
        e.setTextSize(13);
        e.setText(value);
        e.setSingleLine(!hint.contains("提示词"));
        if (password) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        return e;
    }

    private TextView grow(String s, int sp, int color) {
        TextView t = text(s, sp, color, true);
        t.setPadding(0, dp(10), 0, dp(2));
        return t;
    }

    @Override
    protected void onResume() {
        super.onResume();
        ChatStore.expireStale(this, STALE_MS);
        ChatStore.markRead(this);
        lastSnapshot = "";
        refresh();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void scrollToBottom() {
        atBottom = true;
        if (jump != null) jump.setVisibility(View.GONE);
        if (scroll != null) {
            scroll.post(new Runnable() {
                @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
            });
        }
    }

    private boolean isAtBottom() {
        View ch = scroll.getChildAt(0);
        if (ch == null) return true;
        return scroll.getScrollY() + scroll.getHeight() >= ch.getMeasuredHeight() - dp(48);
    }

    private void copyText(String s) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("msg", s));
            }
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private static String fmtTime(long ts) {
        if (ts <= 0) return "";
        java.util.Calendar now = java.util.Calendar.getInstance();
        java.util.Calendar m = java.util.Calendar.getInstance();
        m.setTimeInMillis(ts);
        String hm = String.format(java.util.Locale.CHINA, "%02d:%02d",
                m.get(java.util.Calendar.HOUR_OF_DAY), m.get(java.util.Calendar.MINUTE));
        if (now.get(java.util.Calendar.YEAR) == m.get(java.util.Calendar.YEAR)
                && now.get(java.util.Calendar.DAY_OF_YEAR) == m.get(java.util.Calendar.DAY_OF_YEAR)) {
            return hm;
        }
        return String.format(java.util.Locale.CHINA, "%d月%d日 %s",
                m.get(java.util.Calendar.MONTH) + 1, m.get(java.util.Calendar.DAY_OF_MONTH), hm);
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
