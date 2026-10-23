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

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
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

    /** Puts text into the focused search / text box of any app. */
    public boolean type(String text) {
        AccessibilityNodeInfo f = focused();
        if (f == null || !f.isEditable()) return false;
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }
}
