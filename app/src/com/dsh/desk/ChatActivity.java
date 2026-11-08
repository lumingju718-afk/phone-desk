package com.dsh.desk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 桌面（唯一界面）：左右滑动切换两页
 *   第 0 页：服务器状态（电量/内存/存储/运行时长/网络/服务）
 *   第 1 页：消息（人机对话）
 *
 * 状态机：发出去 pending(等待应答) → 引擎回复后 sent(已回复) / error(失败，可重试)
 * 应答引擎可切换：中继（助手在本会话回复）/ OpenAI 兼容 / AstrBot
 */
public class ChatActivity extends Activity {

    private static final int BG = Color.parseColor("#05080A");
    private static final int TEXT = Color.parseColor("#C6FFD2");
    private static final int DIM = Color.parseColor("#2E7D4F");
    private static final int BLUE = Color.parseColor("#4DD0FF");
    private static final int AMBER = Color.parseColor("#FFD166");
    private static final int RED = Color.parseColor("#FF5555");
    private static final int GREEN = Color.parseColor("#33FF66");
    private static final int BUBBLE_AI = Color.parseColor("#0A1410");
    private static final int BUBBLE_ME = Color.parseColor("#0A1410");

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

    // 桌面分页（linux / 状态 / 消息）
    private static final int PAGE_LINUX = 0, PAGE_STATUS = 1, PAGE_CHAT = 2, PAGE_COUNT = 3;
    private View[] pages = new View[PAGE_COUNT];
    private LinearLayout dots;
    private StatusPane statusPane;
    private LinuxPane linuxPane;
    private GeckoAgentPane agentPane;
    private int page = PAGE_STATUS;
    private float pageOffset = 0f;
    private float downX, downY;
    private boolean draggingPage = false;
    private int pageWidth = 1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildDesk());
        startExecService();
        ui.post(refreshTick);
    }

    // ---------------- 桌面（三页左右滑：linux / 状态 / 消息） ----------------

    private View buildDesk() {
        pages[PAGE_LINUX] = buildLinuxPage();
        pages[PAGE_STATUS] = buildStatusPage();
        pages[PAGE_CHAT] = buildAgentPage();

        final FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(BG);
        for (int i = 0; i < PAGE_COUNT; i++) {
            stage.addView(pages[i], new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        dlp.bottomMargin = dp(6);
        dots.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { goPage((page + 1) % PAGE_COUNT, true); }
        });
        stage.addView(dots, dlp);
        buildDots();

        stage.post(new Runnable() {
            @Override public void run() {
                pageWidth = Math.max(1, stage.getWidth());
                settleTo(page);
            }
        });
        return stage;
    }

    private View buildLinuxPage() {
        linuxPane = new LinuxPane(this);
        return linuxPane.create();
    }

    private View buildAgentPage() {
        agentPane = new GeckoAgentPane(this);
        return agentPane.create();
    }

    private View buildStatusPage() {
        statusPane = new StatusPane(this);
        return statusPane.create();
    }

    private void buildDots() {
        if (dots == null) return;
        dots.removeAllViews();
        for (int i = 0; i < PAGE_COUNT; i++) {
            TextView d = new TextView(this);
            d.setText(i == page ? "\u25cf" : "\u25cb");
            d.setTextSize(10);
            d.setTextColor(i == page ? GREEN : DIM);
            d.setPadding(dp(5), dp(2), dp(5), dp(2));
            dots.addView(d);
        }
    }

    private void applyOffset(float offset) {
        pageOffset = offset;
        for (int i = 0; i < PAGE_COUNT; i++) {
            pages[i].setTranslationX(offset + i * pageWidth);
        }
    }

    private void dragOffset(float dx) {
        float w = pageWidth;
        float off = -page * w + dx;
        float min = -(PAGE_COUNT - 1) * w;
        if (off < min) off = min;
        if (off > 0f) off = 0f;
        applyOffset(off);
    }

    private void goPage(final int target, boolean animate) {
        final int dst = Math.max(0, Math.min(PAGE_COUNT - 1, target));
        if (dst == page) { settleTo(dst); return; }
        final float start = pageOffset;
        final float end = -dst * pageWidth;
        page = dst;
        if (!animate) {
            settleTo(dst);
        } else {
            final int steps = 12;
            ui.post(new Runnable() {
                int i = 0;
                @Override public void run() {
                    i++;
                    float t = Math.min(1f, i / (float) steps);
                    float e = 1 - (1 - t) * (1 - t);
                    applyOffset(start + (end - start) * e);
                    if (t < 1f) ui.postDelayed(this, 14);
                    else settleTo(dst);
                }
            });
        }
        onPageChanged(dst);
    }

    private void settleTo(int target) {
        page = target;
        applyOffset(-target * pageWidth);
        buildDots();
        onPageChanged(target);
    }

    private void onPageChanged(int p) {
        if (linuxPane != null) {
            if (p == PAGE_LINUX) linuxPane.start(); else linuxPane.stop();
        }
        if (statusPane != null) {
            if (p == PAGE_STATUS) statusPane.start(); else statusPane.stop();
        }
        if (agentPane != null) {
            if (p == PAGE_CHAT) agentPane.onShow(); else agentPane.onHide();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                draggingPage = false;
                break;
            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                if (!draggingPage && Math.abs(dx) > dp(16) && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                    draggingPage = true;
                }
                if (draggingPage) {
                    dragOffset(dx);
                    return true;
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (draggingPage) {
                    float dx = ev.getX() - downX;
                    float threshold = pageWidth / 5f;
                    int target = page;
                    if (ev.getActionMasked() != MotionEvent.ACTION_CANCEL) {
                        if (dx < -threshold) target = page + 1;
                        else if (dx > threshold) target = page - 1;
                    }
                    goPage(target, true);
                    draggingPage = false;
                    return true;
                }
                break;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    /** 用手机浏览器打开容器里的 DSH Web（地址由 start-dsh.sh 推到 /dshurl） */
    private void openDsh() {
        try {
            SharedPreferences sp = getSharedPreferences(ExecService.PREFS, MODE_PRIVATE);
            String u = sp.getString("dsh_url", "");
            if (u == null || u.isEmpty()) {
                Toast.makeText(this, "还没拿到 DSH 地址：去最左屏点 dsh 启动", Toast.LENGTH_LONG).show();
                return;
            }
            Intent it = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
        } catch (Throwable e) {
            Toast.makeText(this, "打开失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    private void startExecService() {
        try {
            Intent svc = new Intent(this, ExecService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
            else startService(svc);
        } catch (Throwable ignored) {
        }
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

        TextView back = text("[status]", 11, DIM, false);
        back.setPadding(dp(2), dp(4), dp(10), dp(4));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { goPage(PAGE_STATUS, true); }
        });
        head.addView(back);
        head.addView(text("root@desk:~#", 15, GREEN, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView dshBtn = text("[dsh]", 11, GREEN, true);
        dshBtn.setPadding(dp(8), dp(4), dp(2), dp(4));
        dshBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openDsh(); }
        });
        head.addView(dshBtn);
        TextView gear = text("⚙", 16, DIM, false);
        gear.setPadding(dp(10), dp(4), dp(10), dp(4));
        gear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSettings(); }
        });
        head.addView(gear);

        TextView clear = text("clear", 11, DIM, false);
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

        jump = text("[ ↓ new output ]", 11, TEXT, false);
        jump.setGravity(Gravity.CENTER);
        jump.setPadding(0, dp(5), 0, dp(5));
        GradientDrawable jg = new GradientDrawable();
        jg.setColor(Color.parseColor("#0E2A1A"));
        jg.setStroke(dp(1), DIM);
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

        TextView prompt = text("$", 15, GREEN, true);
        prompt.setPadding(dp(2), 0, dp(6), 0);
        row.addView(prompt);

        input = new EditText(this);
        input.setHint("type a command…");
        input.setHintTextColor(DIM);
        input.setTextColor(TEXT);
        input.setTextSize(14);
        input.setTypeface(Typeface.MONOSPACE);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setMaxLines(4);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        row.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView send = text("[enter]", 12, GREEN, true);
        send.setGravity(Gravity.CENTER);
        send.setPadding(dp(10), dp(9), dp(10), dp(9));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor("#0E2A1A"));
        g.setStroke(dp(1), GREEN);
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
        if (bubbles == null) return;   // 消息页不在页面上（例如换成了 WebView）时不刷新
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
        if (banner == null) return;
        String mode = ChatConfig.mode(this);
        String label;
        if (ChatConfig.MODE_OPENAI.equals(mode)) {
            label = "openai:" + ChatConfig.openaiModel(this);
        } else if (ChatConfig.MODE_ASTRBOT.equals(mode)) {
            label = "astrbot:local";
        } else {
            label = "relay:session";
        }
        StringBuilder sb = new StringBuilder("# mode=").append(label);
        if (pending > 0) sb.append("  pending=").append(pending);
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
            TextView t1 = text("# no messages in log", 13, DIM, false);
            t1.setGravity(Gravity.CENTER);
            TextView t2 = text("type below, then [enter]\n@ gear = relay / openai / astrbot",
                    11, DIM, false);
            t2.setGravity(Gravity.CENTER);
            t2.setPadding(0, dp(8), 0, dp(14));
            TextView go = text("$ say hi", 12, GREEN, true);
            go.setGravity(Gravity.CENTER);
            go.setPadding(dp(14), dp(8), dp(14), dp(8));
            GradientDrawable gg = new GradientDrawable();
            gg.setColor(Color.parseColor("#0E2A1A"));
            gg.setStroke(dp(1), GREEN);
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
        String lastDay = "";
        String prevRole = "";
        for (JSONObject o : msgs) {
            String day = dayLabel(o.optLong("ts", 0));
            if (!day.equals(lastDay)) {
                bubbles.addView(dateChip(day));
                lastDay = day;
                prevRole = "";
            }
            String role = o.optString("role");
            boolean grouped = role.equals(prevRole);
            bubbles.addView(bubbleRow(o, grouped));
            prevRole = role;
        }
        if (typing) {
            TextView t = text("... thinking", 11, DIM, false);
            t.setTypeface(Typeface.MONOSPACE, Typeface.ITALIC);
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

    private View bubbleRow(final JSONObject o, final boolean grouped) {
        final boolean ai = ChatStore.ROLE_AI.equals(o.optString("role"));
        final boolean sys = ChatStore.ROLE_SYSTEM.equals(o.optString("role"));
        final String body = o.optString("text", "");
        final String status = o.optString("status", ChatStore.ST_SENT);
        final String err = o.optString("err", "");
        final long id = o.optLong("id", 0);

        TextView tv = new TextView(this);
        tv.setTextSize(13);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(TEXT);
        tv.setLineSpacing(dp(2), 1f);
        tv.setPadding(dp(2), grouped ? dp(1) : dp(3), dp(2), dp(1));

        SpannableStringBuilder sb = new SpannableStringBuilder();
        int a = sb.length();
        sb.append("[").append(fmtTime(o.optLong("ts", 0))).append("] ");
        sb.setSpan(new ForegroundColorSpan(DIM), a, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        String tag = ai ? "ai  > " : (sys ? "sys > " : "you > ");
        int b = sb.length();
        sb.append(tag);
        sb.setSpan(new ForegroundColorSpan(ai ? BLUE : (sys ? AMBER : GREEN)),
                b, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new StyleSpan(Typeface.BOLD), b, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        int c = sb.length();
        sb.append(body);
        sb.setSpan(new ForegroundColorSpan(ai ? TEXT : Color.parseColor("#9CFFB0")),
                c, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new LeadingMarginSpan.Standard(0, dp(7)), c, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        if (!ai && !sys) {
            String tail;
            int col;
            if (ChatStore.ST_PENDING.equals(status)) {
                tail = "  [pending]";
                col = AMBER;
            } else if (ChatStore.ST_ERROR.equals(status)) {
                tail = "  [error: " + (err.isEmpty() ? "failed" : err) + "]";
                col = RED;
            } else {
                tail = "  [sent]";
                col = DIM;
            }
            int d = sb.length();
            sb.append(tail);
            sb.setSpan(new ForegroundColorSpan(col), d, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        tv.setText(sb);
        tv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copyText(body); }
        });
        tv.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                if (ai || sys) {
                    copyText(body);
                } else {
                    ChatEngine.retry(ChatActivity.this, id);
                    lastSnapshot = "";
                    refresh();
                    Toast.makeText(ChatActivity.this, "resending", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
        return tv;
    }

    // ---------------- 气泡内容（代码块 / 粗体 / 行内代码） ----------------

    private LinearLayout richBubble(String message, boolean ai, boolean sys) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable g = new GradientDrawable();
        g.setColor(ai ? BUBBLE_AI : (sys ? Color.parseColor("#2A2320") : BUBBLE_ME));
        g.setCornerRadius(dp(14));
        box.setBackground(g);
        box.setClickable(true);
        int maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.78);

        List<String> segs = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        boolean code = false;
        for (String line : message.split("\n", -1)) {
            if (line.trim().startsWith("```")) {
                if (buf.length() > 0) {
                    segs.add((code ? "c:" : "t:") + buf);
                    buf.setLength(0);
                }
                code = !code;
                continue;
            }
            buf.append(line).append('\n');
        }
        if (buf.length() > 0) segs.add((code ? "c:" : "t:") + buf);

        for (String seg : segs) {
            boolean isCode = seg.startsWith("c:");
            String content = seg.substring(2);
            if (isCode) content = content.trim();
            if (content.trim().isEmpty()) continue;
            TextView tv = new TextView(this);
            tv.setTextSize(isCode ? 12 : 14);
            tv.setTextColor(isCode ? Color.parseColor("#9BE3A8") : TEXT);
            tv.setMaxWidth(maxW);
            if (isCode) {
                tv.setText(content.trim());
                tv.setTypeface(Typeface.MONOSPACE);
                GradientDrawable cg = new GradientDrawable();
                cg.setColor(Color.parseColor("#0A0F14"));
                cg.setCornerRadius(dp(8));
                tv.setBackground(cg);
                tv.setPadding(dp(8), dp(6), dp(8), dp(6));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(4);
                lp.bottomMargin = dp(2);
                tv.setLayoutParams(lp);
            } else {
                tv.setText(inline(content.trim()));
            }
            box.addView(tv);
        }
        if (box.getChildCount() == 0) {
            TextView tv = text(message, 14, TEXT, false);
            tv.setMaxWidth(maxW);
            box.addView(tv);
        }
        return box;
    }

    /** 处理 **粗体** 与 `行内代码` */
    private CharSequence inline(String src) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        int i = 0;
        while (i < src.length()) {
            if (src.startsWith("**", i)) {
                int end = src.indexOf("**", i + 2);
                if (end > i + 2) {
                    int st = out.length();
                    out.append(src, i + 2, end);
                    out.setSpan(new StyleSpan(Typeface.BOLD), st, out.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }
            if (src.charAt(i) == '`') {
                int end = src.indexOf('`', i + 1);
                if (end > i + 1) {
                    int st = out.length();
                    out.append(src, i + 1, end);
                    out.setSpan(new TypefaceSpan("monospace"), st, out.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new BackgroundColorSpan(Color.parseColor("#0A0F14")),
                            st, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            out.append(src.charAt(i));
            i++;
        }
        return out;
    }

    private View dateChip(String label) {
        TextView t = text("+--- " + label + " ---+", 10, DIM, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(10), 0, dp(4));
        t.setMaxLines(1);
        return t;
    }

    private String dayLabel(long ts) {
        if (ts <= 0) return "";
        Calendar now = Calendar.getInstance();
        Calendar m = Calendar.getInstance();
        m.setTimeInMillis(ts);
        if (sameDay(now, m)) return "今天";
        Calendar y = (Calendar) now.clone();
        y.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(y, m)) return "昨天";
        if (now.get(Calendar.YEAR) == m.get(Calendar.YEAR)) {
            return String.format(Locale.CHINA, "%d月%d日",
                    m.get(Calendar.MONTH) + 1, m.get(Calendar.DAY_OF_MONTH));
        }
        return String.format(Locale.CHINA, "%d年%d月%d日",
                m.get(Calendar.YEAR), m.get(Calendar.MONTH) + 1, m.get(Calendar.DAY_OF_MONTH));
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
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
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (statusPane != null) statusPane.stop();
        if (agentPane != null) agentPane.destroy();
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
        t.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
