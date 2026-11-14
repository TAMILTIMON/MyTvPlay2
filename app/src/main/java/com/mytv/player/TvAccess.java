package com.mytv.player;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;

/**
 * Optional (switched on once in TV Settings > Accessibility): lets the phone remote press
 * arrows / OK / Back / Home / Recents in ANY app on the TV, like the Google TV remote.
 */
public class TvAccess extends AccessibilityService {
    public static volatile TvAccess inst;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        inst = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        inst = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        inst = null;
        super.onDestroy();
    }

    /** When focus lands on a text box in any app (e.g. a search field) → the phone opens its keyboard. */
    public static volatile long editFocusAt;
    /** True while that text box is still the one focused (unlike editFocusAt, doesn't go stale while the
     *  person is still mid-sentence — e.g. the YouTube search box speech can take several seconds). */
    public static volatile boolean editFocused;
    /** The focused text box takes numbers only (PIN / parental lock / channel number). */
    public static volatile boolean editNumeric;

    static boolean isNumberType(int t) {
        int cls = t & android.text.InputType.TYPE_MASK_CLASS;
        return cls == android.text.InputType.TYPE_CLASS_NUMBER || cls == android.text.InputType.TYPE_CLASS_PHONE;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        try {
            if (e.getEventType() == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                AccessibilityNodeInfo n = e.getSource();
                if (n != null && n.isEditable()) {
                    editFocusAt = System.currentTimeMillis();
                    editFocused = true;
                    editNumeric = android.os.Build.VERSION.SDK_INT >= 19 && isNumberType(n.getInputType());
                }
            } else if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                editFocused = false;        // left that screen/app — the old field is no longer in front
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onInterrupt() {
    }

    public static boolean on() {
        return inst != null;
    }

    /** Returns true when handled. */
    public boolean key(String k) {
        switch (k) {
            case "back": return performGlobalAction(GLOBAL_ACTION_BACK);
            case "home": return performGlobalAction(GLOBAL_ACTION_HOME);
            case "recents": return performGlobalAction(GLOBAL_ACTION_RECENTS);
            case "notifications": return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
            default: break;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            switch (k) {
                case "up": return performGlobalAction(GLOBAL_ACTION_DPAD_UP);
                case "down": return performGlobalAction(GLOBAL_ACTION_DPAD_DOWN);
                case "left": return performGlobalAction(GLOBAL_ACTION_DPAD_LEFT);
                case "right": return performGlobalAction(GLOBAL_ACTION_DPAD_RIGHT);
                case "ok": return performGlobalAction(GLOBAL_ACTION_DPAD_CENTER);
                default: return false;
            }
        }
        // older TVs: move the focus ourselves
        switch (k) {
            case "up": return move(View.FOCUS_UP);
            case "down": return move(View.FOCUS_DOWN);
            case "left": return move(View.FOCUS_LEFT);
            case "right": return move(View.FOCUS_RIGHT);
            case "ok": return click();
            default: return false;
        }
    }

    private AccessibilityNodeInfo focused() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f == null) f = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        return f;
    }

    private AccessibilityNodeInfo firstFocusable() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int guard = 0;
        while (!q.isEmpty() && guard++ < 2000) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            if (n.isFocusable() && n.isVisibleToUser()) return n;
            for (int i = 0; i < n.getChildCount(); i++) q.add(n.getChild(i));
        }
        return null;
    }

    private boolean move(int dir) {
        AccessibilityNodeInfo f = focused();
        if (f == null) {
            AccessibilityNodeInfo first = firstFocusable();
            return first != null && first.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        }
        AccessibilityNodeInfo next = f.focusSearch(dir);
        return next != null && next.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
    }

    private boolean click() {
        AccessibilityNodeInfo n = focused();
        int guard = 0;
        while (n != null && guard++ < 6) {
            if (n.isClickable()) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            n = n.getParent();
        }
        return false;
    }

    /** Inserts text at the cursor of the focused text box in one step (instant, any length). */
    public boolean insert(String text) {
        try {
            AccessibilityNodeInfo f = focused();
            if (f == null || !f.isEditable()) return false;
            CharSequence cur = f.getText();
            boolean hint = android.os.Build.VERSION.SDK_INT >= 26 && f.isShowingHintText();
            String base = cur == null || hint ? "" : cur.toString();
            int s = f.getTextSelectionStart(), e = f.getTextSelectionEnd();
            if (s < 0 || s > base.length()) s = base.length();
            if (e < s || e > base.length()) e = s;
            Bundle b = new Bundle();
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, base.substring(0, s) + text + base.substring(e));
            if (!f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) return false;
            Bundle sel = new Bundle();
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, s + text.length());
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, s + text.length());
            f.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Puts text into the focused search / text box of any app (replacing what's there; cursor at the end). */
    public boolean type(String text) {
        AccessibilityNodeInfo f = focused();
        if (f == null || !f.isEditable()) return false;
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        if (!f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) return false;
        try {
            Bundle sel = new Bundle();
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length());
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length());
            f.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
        } catch (Throwable ignored) {
        }
        return true;
    }

    /** The text in the focused box right now ("" for an empty box showing its hint, and never a password). */
    public String currentText() {
        try {
            AccessibilityNodeInfo f = focused();
            if (f == null || !f.isEditable() || f.isPassword()) return "";
            CharSequence cur = f.getText();
            boolean hint = android.os.Build.VERSION.SDK_INT >= 26 && f.isShowingHintText();
            return cur == null || hint ? "" : cur.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}
