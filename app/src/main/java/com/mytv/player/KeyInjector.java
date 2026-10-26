package com.mytv.player;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Tiny key server started through ADB as the "shell" user (same idea as scrcpy):
 *   CLASSPATH=<this apk> app_process / com.mytv.player.KeyInjector
 * It may inject key presses into ANY app instantly. Listens only on 127.0.0.1 (this TV).
 * Commands, one per line: "key <code>", "text <url-encoded>", "ime", "ping".
 */
public final class KeyInjector {
    public static final int PORT = 5577;
    private static Object im;
    private static Method inject;
    private static KeyCharacterMap kcm;

    public static void main(String[] args) throws Exception {
        Object mgr;
        try {
            mgr = Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null); // Android 14+
        } catch (Throwable t) {
            mgr = Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null);
        }
        im = mgr;
        inject = mgr.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
        kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        ServerSocket ss = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"));
        while (true) {
            final Socket s = ss.accept();
            new Thread(() -> serve(s)).start();
        }
    }

    private static void serve(Socket s) {
        try {
            s.setTcpNoDelay(true);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            OutputStream out = s.getOutputStream();
            String line;
            while ((line = in.readLine()) != null) {
                String r = "ok";
                try {
                    if (line.startsWith("key ")) press(Integer.parseInt(line.substring(4).trim()));
                    else if (line.startsWith("text ")) type(java.net.URLDecoder.decode(line.substring(5), "UTF-8"));
                    else if (line.equals("ime")) r = imeShown() ? "1" : "0";
                    else if (line.equals("ping")) r = "pong";
                } catch (Throwable t) {
                    r = "err " + t;
                }
                out.write((r + "\n").getBytes("UTF-8"));
                out.flush();
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void send(KeyEvent e) throws Exception {
        inject.invoke(im, e, 0);   // 0 = async
    }

    static void press(int code) throws Exception {
        long now = SystemClock.uptimeMillis();
        send(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
        send(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD));
    }

    static void type(String t) throws Exception {
        KeyEvent[] ev = kcm.getEvents(t.toCharArray());
        if (ev != null) {
            for (KeyEvent e : ev) send(KeyEvent.changeTimeRepeat(e, SystemClock.uptimeMillis(), 0));
            return;
        }
        // characters the virtual keyboard can't type (Tamil, Hindi, emoji): one at a time via the shell
        for (int i = 0; i < t.length(); i++) {
            String c = t.substring(i, i + 1);
            KeyEvent[] one = kcm.getEvents(new char[]{c.charAt(0)});
            if (one != null) for (KeyEvent e : one) send(KeyEvent.changeTimeRepeat(e, SystemClock.uptimeMillis(), 0));
        }
    }

    /** Is an on-screen keyboard open on the TV (a text box is being edited)? */
    static boolean imeShown() throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"dumpsys", "input_method"});
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String l;
        boolean shown = false;
        while ((l = r.readLine()) != null) {
            if (l.contains("mInputShown=true") || l.contains("mIsInputViewShown=true")) {
                shown = true;
                break;
            }
        }
        p.destroy();
        return shown;
    }
}
