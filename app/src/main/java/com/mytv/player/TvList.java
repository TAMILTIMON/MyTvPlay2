package com.mytv.player;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** The TVs this phone has connected to before, so you can reopen, check or remove one without re-pairing. */
final class TvList {
    private TvList() {
    }

    private static final String KEY = "tv_list";

    static List<JSONObject> all() {
        List<JSONObject> l = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Store.getStr(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) l.add(a.getJSONObject(i));
        } catch (Exception ignored) {
        }
        return l;
    }

    private static void save(List<JSONObject> l) {
        Store.putStr(KEY, new JSONArray(l).toString());
    }

    /** Adds this TV, or updates its name/ip/mac if it's already in the list (matched by mac, else ip). */
    static void upsert(String name, String ip, String mac) {
        try {
            List<JSONObject> l = all();
            for (JSONObject o : l) {
                boolean same = (mac != null && !mac.isEmpty() && mac.equals(o.optString("mac")))
                        || (o.optString("mac", "").isEmpty() && ip.equals(o.optString("ip")));
                if (same) {
                    o.put("name", name).put("ip", ip);
                    if (mac != null && !mac.isEmpty()) o.put("mac", mac);
                    o.put("last", System.currentTimeMillis());
                    save(l);
                    return;
                }
            }
            byte[] r = new byte[6];
            new SecureRandom().nextBytes(r);
            StringBuilder id = new StringBuilder();
            for (byte b : r) id.append(String.format("%02x", b));
            JSONObject n = new JSONObject().put("id", id.toString()).put("name", name).put("ip", ip)
                    .put("mac", mac == null ? "" : mac).put("last", System.currentTimeMillis());
            l.add(0, n);
            save(l);
        } catch (Exception ignored) {
        }
    }

    static void remove(String id) {
        List<JSONObject> l = all();
        l.removeIf(o -> id.equals(o.optString("id")));
        save(l);
    }
}
