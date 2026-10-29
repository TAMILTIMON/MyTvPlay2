package com.mytv.player;

import android.app.Activity;
import android.content.Intent;
import android.view.View;

import java.util.LinkedHashMap;

/** The feature tiles shared by the phone's start screen and the "All features" screen (phone AND TV). */
final class FeatureBoard {
    private FeatureBoard() {
    }

    /** Adds the common tiles. tv = running on a TV (phone-only tiles are left out). */
    static void add(final Activity a, LinkedHashMap<String, View> t, boolean tv) {
        t.put("browser", UiKit.tile(a, "search", T.t("Browser"), T.t(tv ? "search & play" : "search & send videos to TV"), 0xFF22C55E,
                v -> a.startActivity(new Intent(a, BrowserActivity.class))));
        if (!tv) t.put("player", UiKit.tile(a, "smartphone", T.t("Use this phone"), T.t("as a player"), 0xFFF97316,
                v -> a.startActivity(new Intent(a, MainActivity.class).putExtra(MainActivity.EXTRA_PLAYER_HOME, true))));
        t.put("videos", UiKit.tile(a, "film", T.t("Videos"), T.t(tv ? "on this TV" : "on this phone"), 0xFF0EA5E9,
                v -> a.startActivity(new Intent(a, PhoneLibraryActivity.class))));
        t.put("music", UiKit.tile(a, "list-music", T.t("Music"), T.t(tv ? "on this TV" : "on this phone"), 0xFFD946EF,
                v -> a.startActivity(new Intent(a, PhoneLibraryActivity.class).putExtra(PhoneLibraryActivity.EXTRA_AUDIO, true))));
        t.put("features", UiKit.tile(a, "wand-sparkles", T.t("Features"), T.t("cutter, karaoke, converter…"), 0xFFF59E0B,
                v -> a.startActivity(new Intent(a, ToolsActivity.class))));
        t.put("dj", UiKit.tile(a, "disc-3", T.t("DJ mixer"), T.t("two decks, sync, effects"), 0xFFEC4899,
                v -> a.startActivity(new Intent(a, DjActivity.class))));
        t.put("editor", UiKit.tile(a, "audio-waveform", T.t("Song editor"), T.t("tracks, cut, effects"), 0xFF6366F1,
                v -> a.startActivity(new Intent(a, EditorActivity.class))));
        t.put("board", UiKit.tile(a, "presentation", T.t("Whiteboard"), T.t("draw, shapes, rulers, formulas"), 0xFF10B981,
                v -> a.startActivity(new Intent(a, PresentActivity.class).putExtra(PresentActivity.EXTRA_BOARD, true))));
        if (!tv) t.put("present", UiKit.tile(a, "presentation", T.t("Present"), T.t("PDF slides on the TV"), 0xFF8B5CF6, v -> {
            if (Store.getStr("last_tv", "").isEmpty()) UiKit.makeToast(a, T.t("Connect to a TV first"), android.widget.Toast.LENGTH_SHORT).show();
            else a.startActivity(new Intent(a, PresentActivity.class));
        }));
        t.put("vpn", UiKit.tile(a, "lock", "VPN", T.t("WireGuard / Proton free"), 0xFF10B981, v -> WgVpn.panel(a)));
        t.put("eq", UiKit.tile(a, "sliders-vertical", T.t("Equalizer"), T.t("for the music player"), 0xFF22D3EE,
                v -> a.startActivity(new Intent(a, PowerEqActivity.class))));
        t.put("updates", UiKit.tile(a, "refresh-cw", T.t("Updates"), Updater.installedName(a), 0xFF8B5CF6, v -> Updater.menu(a)));
        t.put("settings", UiKit.tile(a, "settings", T.t("Settings"), T.t("language, theme, …"), 0xFF64748B, v -> SettingsDialog.show(a)));
    }
}
