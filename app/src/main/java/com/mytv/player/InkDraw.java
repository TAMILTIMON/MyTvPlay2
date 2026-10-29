package com.mytv.player;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Drawing of whiteboard / slide ink, shared by the phone and the TV so both look the same.
 * Element (JSON, slide coordinates 0…1): {id, pg, t: pen|hl|line|arrow|rect|oval|tri|ruler|text, c: "#RRGGBB",
 * w: width in 1/1000 of the page height, p: [x1, y1, x2, y2, …], s: text, z: text size (1/1000 of page height)}.
 */
public final class InkDraw {
    private InkDraw() {
    }

    /** page → screen. */
    public interface Map {
        float[] at(float fx, float fy);
    }

    public static final String[] PAPERS = {"white", "chalk", "grid", "lined", "dots", "graph"};
    public static final String[] PAPER_NAMES = {"White", "Chalkboard", "Grid", "Lined", "Dotted", "Graph paper"};
    /** Ruler: the page is 32 cm wide (a sensible on-screen ruler for teaching). */
    public static final float PAGE_CM = 32f;

    /** The paper of a whiteboard page, drawn into the page's screen rectangle. */
    public static void paper(Canvas c, String style, RectF r, float pageH) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean chalk = "chalk".equals(style);
        p.setColor(chalk ? 0xFF1F3A2E : Color.WHITE);
        c.drawRect(r, p);
        float unit = r.height() / 18f;                             // 18 squares from top to bottom
        p.setStrokeWidth(Math.max(1f, r.height() / 900f));
        switch (style == null ? "" : style) {
            case "grid":
            case "graph":
                p.setColor(0xFFCBD5E1);
                float step = "graph".equals(style) ? unit / 5f : unit;
                int i = 0;
                for (float x = r.left; x <= r.right + 0.5f; x += step, i++) {
                    p.setColor("graph".equals(style) && i % 5 == 0 ? 0xFF94A3B8 : 0xFFE2E8F0);
                    c.drawLine(x, r.top, x, r.bottom, p);
                }
                i = 0;
                for (float y = r.top; y <= r.bottom + 0.5f; y += step, i++) {
                    p.setColor("graph".equals(style) && i % 5 == 0 ? 0xFF94A3B8 : 0xFFE2E8F0);
                    c.drawLine(r.left, y, r.right, y, p);
                }
                break;
            case "lined":
                p.setColor(0xFFBFDBFE);
                for (float y = r.top + unit * 1.5f; y <= r.bottom; y += unit) c.drawLine(r.left, y, r.right, y, p);
                p.setColor(0xFFFCA5A5);
                c.drawLine(r.left + unit * 2.2f, r.top, r.left + unit * 2.2f, r.bottom, p);
                break;
            case "dots":
                p.setColor(0xFF94A3B8);
                float d = Math.max(1.5f, r.height() / 400f);
                for (float x = r.left + unit; x < r.right; x += unit)
                    for (float y = r.top + unit; y < r.bottom; y += unit) c.drawCircle(x, y, d, p);
                break;
            default:
                break;
        }
    }

    /** One ink element. scale = page height in screen pixels (widths and text follow the zoom). */
    public static void draw(Canvas c, JSONObject e, Map m, float pageH) {
        String t = e.optString("t", "pen");
        int col;
        try {
            col = Color.parseColor(e.optString("c", "#EF4444"));
        } catch (Exception x) {
            col = 0xFFEF4444;
        }
        float w = Math.max(1.5f, (float) e.optDouble("w", 4) / 1000f * pageH);
        JSONArray p = e.optJSONArray("p");
        Paint s = new Paint(Paint.ANTI_ALIAS_FLAG);
        s.setStyle(Paint.Style.STROKE);
        s.setStrokeCap(Paint.Cap.ROUND);
        s.setStrokeJoin(Paint.Join.ROUND);
        s.setStrokeWidth(w);
        s.setColor(col);
        if ("hl".equals(t)) {
            s.setAlpha(95);
            s.setStrokeCap(Paint.Cap.SQUARE);
        }
        if ("text".equals(t)) {
            if (p == null || p.length() < 2) return;
            float[] at = m.at((float) p.optDouble(0), (float) p.optDouble(1));
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setColor(col);
            tp.setTextSize(Math.max(8f, (float) e.optDouble("z", 50) / 1000f * pageH));
            tp.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
            float y = at[1];
            for (String line : e.optString("s", "").split("\n")) {
                c.drawText(line, at[0], y, tp);
                y += tp.getTextSize() * 1.25f;
            }
            return;
        }
        if (p == null || p.length() < 2) return;
        float[] a = m.at((float) p.optDouble(0), (float) p.optDouble(1));
        float[] b = p.length() >= 4 ? m.at((float) p.optDouble(p.length() - 2), (float) p.optDouble(p.length() - 1)) : a;
        switch (t) {
            case "line":
                c.drawLine(a[0], a[1], b[0], b[1], s);
                break;
            case "arrow": {
                c.drawLine(a[0], a[1], b[0], b[1], s);
                double ang = Math.atan2(b[1] - a[1], b[0] - a[0]);
                float head = Math.max(w * 4, pageH * 0.025f);
                Path h = new Path();
                h.moveTo(b[0], b[1]);
                h.lineTo((float) (b[0] - head * Math.cos(ang - 0.45)), (float) (b[1] - head * Math.sin(ang - 0.45)));
                h.moveTo(b[0], b[1]);
                h.lineTo((float) (b[0] - head * Math.cos(ang + 0.45)), (float) (b[1] - head * Math.sin(ang + 0.45)));
                c.drawPath(h, s);
                break;
            }
            case "rect":
                c.drawRect(Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]), Math.max(a[1], b[1]), s);
                break;
            case "oval":
                c.drawOval(new RectF(Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]), Math.max(a[1], b[1])), s);
                break;
            case "tri": {
                Path tr = new Path();
                tr.moveTo((a[0] + b[0]) / 2, Math.min(a[1], b[1]));
                tr.lineTo(Math.max(a[0], b[0]), Math.max(a[1], b[1]));
                tr.lineTo(Math.min(a[0], b[0]), Math.max(a[1], b[1]));
                tr.close();
                c.drawPath(tr, s);
                break;
            }
            case "ruler":
                ruler(c, e, a, b, s, pageH);
                break;
            default: {                                              // pen / highlighter
                Path path = new Path();
                path.moveTo(a[0], a[1]);
                for (int k = 2; k + 1 < p.length(); k += 2) {
                    float[] q = m.at((float) p.optDouble(k), (float) p.optDouble(k + 1));
                    path.lineTo(q[0], q[1]);
                }
                if (p.length() == 2) path.lineTo(a[0] + 0.1f, a[1]);
                c.drawPath(path, s);
            }
        }
    }

    /** A measured line: ticks every cm and "12.5 cm · 30°" in the middle. */
    private static void ruler(Canvas c, JSONObject e, float[] a, float[] b, Paint s, float pageH) {
        c.drawLine(a[0], a[1], b[0], b[1], s);
        JSONArray p = e.optJSONArray("p");
        double dxP = (p.optDouble(2) - p.optDouble(0)), dyP = (p.optDouble(3) - p.optDouble(1)) * 9.0 / 16.0;   // page 16:9
        double cm = Math.hypot(dxP, dyP) * PAGE_CM;
        double ang = Math.toDegrees(Math.atan2(-(b[1] - a[1]), b[0] - a[0]));
        if (Math.abs(ang) < 0.5) ang = 0;                    // no "-0°"
        float len = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
        if (len < 1) return;
        float ux = (b[0] - a[0]) / len, uy = (b[1] - a[1]) / len, nx = -uy, ny = ux;
        Paint tk = new Paint(s);
        tk.setStrokeWidth(Math.max(1f, s.getStrokeWidth() / 2));
        int n = (int) Math.floor(cm);
        for (int i = 0; i <= n && i <= 200; i++) {
            float f = (float) (i / cm) * len, h = pageH * (i % 5 == 0 ? 0.018f : 0.010f);
            c.drawLine(a[0] + ux * f, a[1] + uy * f, a[0] + ux * f + nx * h, a[1] + uy * f + ny * h, tk);
        }
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(s.getColor());
        tp.setTextSize(Math.max(10f, pageH * 0.035f));
        tp.setTextAlign(Paint.Align.CENTER);
        String label = String.format(Locale.US, "%.1f cm · %.0f°", cm, ang);
        c.drawText(label, (a[0] + b[0]) / 2 - nx * pageH * 0.03f, (a[1] + b[1]) / 2 - ny * pageH * 0.03f, tp);
    }

    /** Distance (in page fractions) from a point to an element — for the eraser. */
    public static double distance(JSONObject e, float x, float y) {
        JSONArray p = e.optJSONArray("p");
        if (p == null || p.length() < 2) return 9;
        String t = e.optString("t");
        if ("text".equals(t)) {
            double dx = x - p.optDouble(0), dy = y - p.optDouble(1);
            return dx >= -0.01 && dx < 0.3 && dy > -0.08 && dy < 0.02 ? 0 : 9;
        }
        if (!"pen".equals(t) && !"hl".equals(t) && p.length() >= 4) {
            float x1 = (float) p.optDouble(0), y1 = (float) p.optDouble(1), x2 = (float) p.optDouble(p.length() - 2), y2 = (float) p.optDouble(p.length() - 1);
            if ("rect".equals(t) || "oval".equals(t) || "tri".equals(t)) {
                boolean in = x >= Math.min(x1, x2) - 0.01 && x <= Math.max(x1, x2) + 0.01 && y >= Math.min(y1, y2) - 0.01 && y <= Math.max(y1, y2) + 0.01;
                return in ? 0 : 9;
            }
            return seg(x, y, x1, y1, x2, y2);
        }
        double best = 9;
        for (int k = 0; k + 3 < p.length(); k += 2) {
            best = Math.min(best, seg(x, y, (float) p.optDouble(k), (float) p.optDouble(k + 1), (float) p.optDouble(k + 2), (float) p.optDouble(k + 3)));
        }
        if (p.length() == 2) best = Math.hypot(x - p.optDouble(0), y - p.optDouble(1));
        return best;
    }

    private static double seg(float px, float py, float x1, float y1, float x2, float y2) {
        double dx = x2 - x1, dy = y2 - y1, l = dx * dx + dy * dy;
        double t = l == 0 ? 0 : Math.max(0, Math.min(1, ((px - x1) * dx + (py - y1) * dy) / l));
        return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
    }
}
