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
        t.put("seedr", UiKit.tile(a, "cloud-download", "Seedr", T.t("Cloud torrent manager"), 0xFF3B82F6, v -> SeedrActivity.open(a)));
        t.put("storage", UiKit.tile(a, "sd-card", T.t("SD Card & USB"), T.t("files on SD card, USB, phone"), 0xFFF59E0B, v -> StorageActivity.open(a)));
        t.put("network", UiKit.tile(a, "network", T.t("Network folders"), T.t("PC / NAS, phones sharing videos"), 0xFF14B8A6,
                v -> a.startActivity(new Intent(a, NetworkActivity.class))));
        if (tv) {
            t.put("local_files", UiKit.tile(a, "usb", T.t("Local files"), T.t("USB drive / TV storage"), 0xFF0EA5E9,
                    v -> a.startActivity(new Intent(a, FileBrowserActivity.class))));
            t.put("library", UiKit.tile(a, "library", T.t("Library"), T.t("posters, watched ✓, sort"), 0xFF8B5CF6,
                    v -> a.startActivity(new Intent(a, LibraryActivity.class))));
            t.put("my_tv", UiKit.tile(a, "gauge", T.t("My TV"), T.t("storage, memory, speed test"), 0xFF06B6D4,
                    v -> a.startActivity(new Intent(a, TvInfoActivity.class))));
        }
        t.put("eq", UiKit.tile(a, "sliders-vertical", T.t("Equalizer"), T.t("for the music player"), 0xFF22D3EE,
                v -> a.startActivity(new Intent(a, PowerEqActivity.class))));
        t.put("updates", UiKit.tile(a, "refresh-cw", T.t("Updates"), Updater.installedName(a), 0xFF8B5CF6, v -> Updater.menu(a)));
        if (!tv) t.put("home_icons", UiKit.tile(a, "layout-grid", T.t("Home-screen icons"), T.t("ZH Browser, ZH VPN, ZH Music… as widgets"), 0xFFF59E0B,
                v -> ShortcutsActivity.open(a)));
        t.put("settings", UiKit.tile(a, "settings", T.t("Settings"), T.t("language, theme, …"), 0xFF64748B, v -> SettingsDialog.show(a)));
        if (!tv) t.put("exit", UiKit.tile(a, "power", T.t("Exit ZH Play"), T.t("close all, save battery"), 0xFFEF4444, v -> App.confirmExit(a)));
        int unread = InboxActivity.unread();
        boolean live = "live".equals(Broadcast.phase(Broadcast.current()));
        t.put("inbox", UiKit.tile(a, "bell", T.t("Notifications"),
                live ? "🔴 " + T.t("LIVE now") : unread > 0 ? unread + " " + T.t("new") : T.t("announcements & live"),
                0xFFF97316, v -> InboxActivity.open(a)));
        t.put("account", UiKit.tile(a, "user", Auth.signedIn() ? Auth.name() : T.t("Sign in"),
                Auth.signedIn() ? Auth.email() : T.t("Google account"), 0xFF6366F1, v -> AuthActivity.accountMenu(a)));
        Auth.filter(t);
    }
}
