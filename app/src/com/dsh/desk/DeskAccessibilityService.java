package com.dsh.desk;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 无障碍服务：UI 自动化（点击/滑动/输入/读节点/截图）。
 * 由系统绑定，开关在「设置 → 辅助功能 → 服务器桌面」。
 */
public class DeskAccessibilityService extends AccessibilityService {

    static volatile DeskAccessibilityService INSTANCE;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (INSTANCE == this) INSTANCE = null;
        super.onDestroy();
    }

    public static boolean ready() {
        return INSTANCE != null;
    }

    // ---------------- 手势 ----------------

    boolean tap(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        return gesture(p, 0, 1);
    }

    boolean longPress(float x, float y, long ms) {
        Path p = new Path();
        p.moveTo(x, y);
        return gesture(p, 0, Math.max(300, ms));
    }

    boolean swipe(float x1, float y1, float x2, float y2, long ms) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        return gesture(p, 0, Math.max(50, ms));
    }

    private boolean gesture(Path path, long startMs, long durationMs) {
        try {
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, startMs, durationMs);
            GestureDescription g = new GestureDescription.Builder().addStroke(stroke).build();
            return dispatchGesture(g, null, null);
        } catch (Throwable e) {
            return false;
        }
    }

    // ---------------- 全局操作 ----------------

    boolean global(String action) {
        int code;
        String a = action == null ? "" : action.toLowerCase();
        switch (a) {
            case "back": code = GLOBAL_ACTION_BACK; break;
            case "home": code = GLOBAL_ACTION_HOME; break;
            case "recents": code = GLOBAL_ACTION_RECENTS; break;
            case "notif":
            case "notifications": code = GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quick":
            case "quicksettings": code = GLOBAL_ACTION_QUICK_SETTINGS; break;
            default: return false;
        }
        return performGlobalAction(code);
    }

    // ---------------- 文本输入 ----------------

    boolean setText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (target == null || !target.isEditable()) {
            target = findFirstEditable(root);
        }
        if (target == null) return false;
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }

    private AccessibilityNodeInfo findFirstEditable(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int visited = 0;
        while (!q.isEmpty() && visited < 3000) {
            AccessibilityNodeInfo n = q.poll();
            visited++;
            if (n == null) continue;
            if (n.isEditable()) return n;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return null;
    }

    // ---------------- 按文字点击 ----------------

    boolean clickText(String target) {
        if (target == null || target.isEmpty()) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int visited = 0;
        while (!q.isEmpty() && visited < 5000) {
            AccessibilityNodeInfo n = q.poll();
            visited++;
            if (n == null) continue;
            String t = n.getText() == null ? "" : n.getText().toString();
            String d = n.getContentDescription() == null ? "" : n.getContentDescription().toString();
            if (t.contains(target) || d.contains(target)) {
                AccessibilityNodeInfo c = n;
                int hops = 0;
                while (c != null && !c.isClickable() && hops < 6) {
                    c = c.getParent();
                    hops++;
                }
                if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return false;
    }

    // ---------------- 节点树 ----------------

    JSONObject dumpJson(int maxDepth) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            JSONObject o = new JSONObject();
            if (root == null) {
                o.put("ok", false);
                o.put("error", "no active window");
                return o;
            }
            int[] budget = new int[]{3000};
            JSONObject tree = nodeJson(root, 0, Math.max(4, maxDepth), budget);
            o.put("ok", true);
            o.put("package", root.getPackageName() == null ? "" : root.getPackageName().toString());
            o.put("tree", tree == null ? JSONObject.NULL : tree);
            return o;
        } catch (Throwable e) {
            JSONObject o = new JSONObject();
            try {
                o.put("ok", false);
                o.put("error", String.valueOf(e.getMessage()));
            } catch (Throwable ignored) {
            }
            return o;
        }
    }

    private JSONObject nodeJson(AccessibilityNodeInfo n, int depth, int maxDepth, int[] budget) {
        if (n == null || depth > maxDepth || budget[0] <= 0) return null;
        budget[0]--;
        JSONObject o = new JSONObject();
        try {
            o.put("cls", n.getClassName() == null ? "" : n.getClassName().toString());
            o.put("text", n.getText() == null ? JSONObject.NULL : n.getText().toString());
            o.put("desc", n.getContentDescription() == null ? JSONObject.NULL : n.getContentDescription().toString());
            o.put("id", n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName());
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            o.put("bounds", new JSONArray().put(r.left).put(r.top).put(r.right).put(r.bottom));
            o.put("clickable", n.isClickable());
            o.put("editable", n.isEditable());
            o.put("focused", n.isFocused());
            if (n.getChildCount() > 0) {
                JSONArray kids = new JSONArray();
                for (int i = 0; i < n.getChildCount(); i++) {
                    JSONObject c = nodeJson(n.getChild(i), depth + 1, maxDepth, budget);
                    if (c != null) kids.put(c);
                    if (budget[0] <= 0) break;
                }
                if (kids.length() > 0) o.put("children", kids);
            }
        } catch (Throwable ignored) {
        }
        return o;
    }

    // ---------------- 截图 ----------------

    String screenshotBase64(int maxWidth) {
        if (Build.VERSION.SDK_INT < 30) return null;
        final CountDownLatch latch = new CountDownLatch(1);
        final Bitmap[] out = new Bitmap[1];
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult result) {
                            try {
                                HardwareBuffer hb = result.getHardwareBuffer();
                                Bitmap b = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                                if (b != null) {
                                    out[0] = b.copy(Bitmap.Config.ARGB_8888, false);
                                }
                                hb.close();
                            } catch (Throwable ignored) {
                            } finally {
                                latch.countDown();
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            latch.countDown();
                        }
                    });
            latch.await(8, TimeUnit.SECONDS);
        } catch (Throwable e) {
            return null;
        }
        Bitmap b = out[0];
        if (b == null) return null;
        try {
            if (maxWidth > 0 && b.getWidth() > maxWidth) {
                int h = Math.max(1, b.getHeight() * maxWidth / b.getWidth());
                Bitmap scaled = Bitmap.createScaledBitmap(b, maxWidth, h, true);
                b.recycle();
                b = scaled;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 70, bos);
            b.recycle();
            return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable e) {
            return null;
        }
    }
}
