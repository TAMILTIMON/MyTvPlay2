package com.mytv.player;

import android.app.Activity;
import android.app.SearchManager;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * "Hey Google, play Sun TV on TamilCastPlay": Assistant hands over only the spoken words.
 * Finds the best matching channel / video in your playlists and plays it (with its playlist, so CH+ works).
 */
public class VoiceSearchActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Store.init(this);
        String q = getIntent().getStringExtra(SearchManager.QUERY);
        if (q == null) q = getIntent().getStringExtra(MediaStore.EXTRA_MEDIA_TITLE);
        play(q == null ? "" : q.trim());
        finish();
    }

    private void play(String q) {
        if (q.isEmpty()) {
            // "play TamilCastPlay": continue the last video
            List<Store.Watch> h = Store.history();
            if (!h.isEmpty()) {
                List<Store.Item> one = new java.util.ArrayList<>();
                one.add(new Store.Item(h.get(0).t, h.get(0).u, ""));
                Remote.play(this, one, 0);
                return;
            }
            Remote.openHome(this);
            return;
        }
        String found = TvSearch.channel(this, q);
        if (found != null) {
            UiKit.makeToast(this, found, Toast.LENGTH_SHORT).show();
            return;
        }
        String ql = q.toLowerCase(Locale.ROOT).replaceAll("\\b(channel|tv|play|on|the)\\b", " ").replaceAll("\\s+", " ").trim();
        if (ql.isEmpty()) ql = q.toLowerCase(Locale.ROOT);
        String bestPl = null;
        int bestIdx = -1, bestScore = 0;
        java.util.Set<String> favs = Store.favs();
        for (String pl : Store.names()) {
            if (!Family.playlistAllowed(pl)) continue;
            List<Store.Item> items = Store.items(pl);
            for (int i = 0; i < items.size(); i++) {
                String t = items.get(i).t.toLowerCase(Locale.ROOT);
                int sc = t.equals(ql) ? 100 : t.startsWith(ql) ? 80 : t.contains(" " + ql) ? 70 : t.contains(ql) ? 60 : 0;
                if (sc == 0) {
                    // every spoken word inside the title
                    boolean all = true;
                    for (String w : ql.split(" ")) if (!w.isEmpty() && !t.contains(w)) all = false;
                    if (all) sc = 50;
                }
                if (sc == 0) continue;
                if (favs.contains(items.get(i).u)) sc += 15;
                if (sc > bestScore) {
                    bestScore = sc;
                    bestPl = pl;
                    bestIdx = i;
                }
            }
        }
        if (bestPl != null) {
            Remote.play(this, Store.items(bestPl), bestIdx);
            UiKit.makeToast(this, "▶ " + Store.items(bestPl).get(bestIdx).t, Toast.LENGTH_SHORT).show();
        } else {
            UiKit.makeToast(this, T.t("No channel found") + ": " + q, Toast.LENGTH_LONG).show();
            Remote.openHome(this);
        }
    }
}
