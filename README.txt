TAMILCASTPLAY 1.5 — channels & Hindi
====================================
* Adding a channel-list link (.m3u / .m3u8 / .pls) to a playlist now adds ALL its
  channels (11,000+ for iptv-org) so they show in the Library and on the TV home.
  Old playlists that contain just the link: tap "📥 Channels" next to it (Library).
* Phone Remote tab > Queue: search box for the channels playing on the TV,
  and "💾 Save as playlist".
* Player: new 📺 button next to 🔊 in the control bar = channel list with live search.
  With a physical remote on channel lists:
    OK            = open channel list (type to search, ▼ into the list, OK to play)
    0-9           = type a channel number (e.g. 1 2 3 = channel 123)
    CH+ / CH-     = next / previous channel
    Guide / TV key = channel list
* Languages: English, தமிழ், हिन्दी — TV: ⚙ Settings > Language; phone page: the
  language button at the top switches த → अ → EN.

TAMILCASTPLAY 1.4 — search & bulk delete
========================================
TV home:
* Open a playlist > "🔍 Search channel / name" (works on every playlist).
* "🗑 Select & delete…" > tick channels, or "Select all" > Delete.
  After a search, it lists only the search results (e.g. delete all "Radio" channels).
* Home screen > "🗑 Select & delete playlists…" > tick or "Select all" > Delete.
Phone page (Library tab):
* Search box on every playlist.
* ☑ = select mode: tick channels, "Select all" (whole playlist, or all search
  results), untick any you want to keep, then "🗑 Delete (n)".
* Top card "Playlists": tick playlists or "Select all" > Delete.
Every delete asks for confirmation.

TAMILCASTPLAY 1.3 — IPTV channel lists
======================================
* .m3u / .m3u8 / .pls channel lists (e.g. https://iptv-org.github.io/iptv/index.m3u,
  11,000+ channels) now open as a channel list instead of jumping back to Home.
  Normal .m3u8 video streams still play directly.
* Channel mode: a dead channel shows a message instead of closing or skipping.
  OK = retry, CH+ / CH- = next / previous channel, ▼ then ☰ = searchable channel list.
* Search box for big playlists on the TV home screen, in the player and on the phone page.
* Phone page loads big playlists 100-200 at a time ("Show more") — no more freezing.
* Playlists are saved one file each in the background — the app stays fast with huge lists.
* Channels that need a special user-agent / referrer (EXTVLCOPT) now play.

TAMILCASTPLAY 1.2 — smoother & easier to navigate
=================================================
* Phone remote is much faster: heavy blur effects removed, fewer screen redraws,
  no 300 ms tap delay, tabs switch the moment you touch them.
* No more automatic zooming: pinch/double-tap zoom and phone font-scaling are
  disabled on the remote page; buttons no longer "grow" on phones.
* 🏠 Home everywhere: phone remote header, player top corner (⌂) and player menu,
  browser toolbar, Local files (first row). Phone Back button: other tab -> Remote tab -> exit.

TAMILCASTPLAY 1.1 — what's new
==============================
1. FIXED SIGNING KEY (do this once, before pushing):
   GitHub repo > Settings > Secrets and variables > Actions > New repository secret.
   Add the 4 secrets listed in GITHUB_SECRETS.txt (KEYSTORE_B64, STORE_PASSWORD,
   KEY_ALIAS, KEY_PASSWORD). Builds are then signed with YOUR key, so every new
   APK installs over the previous one. Back up tamilcastplay.jks + GITHUB_SECRETS.txt.
   Never put them in the repo (.gitignore already blocks them).
   With the key, the build makes "release" APKs: TamilCastPlay-armeabi-v7a-release.apk …
   First install of a key-signed build: uninstall the old TamilCastPlay once.
2. REMOTE PIN: the TV shows a 4-digit PIN (home screen + ⚙ Settings > Remote PIN).
   Each phone/browser enters it once. New PIN = forget all phones. Can be turned off.
3. SHARE TO TV: in any phone app (YouTube link, browser, Telegram, WhatsApp, Files,
   Gallery) tap Share > "Play on TV". Links, magnets, videos, songs, .torrent files.
4. CONTINUE WATCHING: row with pictures + progress on the TV home and phone Remote tab.
   Torrent episodes restart their torrent automatically. Long-press to remove.
5. TAMIL UI: ⚙ Settings > Language / மொழி > தமிழ் (app restarts).
   Phone page: tap the த button at the top.
6. BROWSER: TV home > "Browser", phone app > Cast > Browser, or send a page to the
   TV browser from the phone. Search with Google, bookmarks (☆), and
   🎬 shows video/stream links found on the page; long-press a link to play it,
   send it to the TV, queue it or save it. TV remote: arrows move a pointer,
   OK clicks, hold OK = long-press, Menu = address bar, Back = previous page.
   (Netflix/Hotstar etc. pages are DRM-protected: use their apps from the Apps row.)

TAMILCASTPLAY 1.0 notes (app id: com.tamil.castplay)
=============================================
Installs as a NEW app next to the old "My TV Player" (com.mytv.player).
Playlists/settings from the old app are not copied. Remove the old one with:
   adb uninstall com.mytv.player      (or TV Settings > Apps > My TV Player > Uninstall)

MY TV PLAYER 3.1 — what's new
=============================
* No laptop needed for permissions. On first start the TV app asks:
  "Allow the phone to start videos" -> Allow -> switch TamilCastPlay ON -> Back.
  Also in ⚙ Settings (display over other apps, all-files access), and as a
  row on the home screen until it is allowed. Some TVs hide this screen; then
  the TV shows the manual path: Settings > Apps > Special app access.
* APPS hub: Netflix, JioHotstar, SonyLIV, Spotify, YouTube, Prime Video, ZEE5…
  as tiles on the home screen and in the phone app (Remote tab). Tap to open the
  official app — log in once in each app, it stays logged in. "＋ Add apps"
  lets you add any installed app or get missing ones from the Play Store.

MY TV PLAYER 3.0
================
* New glass design (purple/pink) on the TV, the phone app and the browser page.
* Dark and Light theme everywhere (TV: ⚙ Settings or the ☀/🌙 button; phone: ☀/🌙 at the top).
* ONE apk for TV and phone. Install the same apk on your phone:
    - it scans the Wi-Fi and finds your TVs by itself (no typing IPs),
    - it opens the last TV automatically, 📺 button switches between TVs,
    - "Cast" tab > "Play videos / songs from this phone" streams phone files
      straight to the TV (no copying, starts instantly; keep the app open),
    - "Open a .torrent file" works like a magnet link,
    - "Use this phone as a player" makes the phone itself a player.
* Several TVs: install on each TV, give each a name (⚙ Settings > Device name).
* Equalizer sliders are now vertical (up/down) on TV, phone and browser.
* .torrent files: upload from browser/phone, links ending in .torrent, or open
  a .torrent file on the TV with "Open with TamilCastPlay".
* TV player now has on-screen buttons and a ⚙ settings button in the corner:
  press ▼ to reach the buttons (◀ ▶ to move, OK to press, Back to hide),
  press ▲ to jump to the ⚙ corner button. Phones: tap the video.
* Home screen has ⚙ Settings in the top corner (theme, name, picture fit,
  aspect, equalizer, volume, subtitles, decoder, buffer…).
* FIXED: "Stretch" and "Fill screen" now always cover the whole TV.
  (Cause: the old version re-applied the automatic aspect ratio every time a
  video started, which undid the stretch. The app now works out the size itself.)
  If you still see a thin black border, it is the TV's own picture size: set
  the TV's picture/aspect setting to "Full", "Just Scan" or "Screen fit".

BUILD IT WITH GITHUB (free)
==============================================

BUILD
1. Sign in at github.com. Open your MyTvPlay2 repository (or make a new
   Public one named MyTvPlay2).
2. Unzip TamilCastPlay.zip on your PC. Open the unzipped folder, select
   EVERYTHING inside (including the .github folder) and drag it into
   "Add file > Upload files". Replace the old files. Press "Commit changes".
3. Open the "Actions" tab. Wait 5-15 minutes for a green tick.
   A red cross means the build failed: open it, copy the error, send it to Claude.
4. Open the finished run > "Artifacts" > TamilCastPlay-apk. Unzip it. Inside:
      TamilCastPlay-armeabi-v7a-debug.apk   (most TVs, 32-bit)
      TamilCastPlay-arm64-v8a-debug.apk     (64-bit TVs)
      TamilCastPlay-universal-debug.apk     (works on all, bigger)
   Not sure? Run:  adb shell getprop ro.product.cpu.abi
5. Install on the TV:
      adb connect 192.168.1.33:5555
      adb install -r TamilCastPlay-armeabi-v7a-debug.apk
   Install on the phone: copy TamilCastPlay-universal-debug.apk (or arm64) to the phone
   and open it (allow "install unknown apps"). Updates from 2.0 install over it.

ONE-TIME PERMISSIONS (run from your PC, recommended)
   Phone can start videos even when the app is closed:
      adb shell appops set com.tamil.castplay SYSTEM_ALERT_WINDOW allow
   See all files on USB/storage (including subtitle files):
      adb shell appops set com.tamil.castplay MANAGE_EXTERNAL_STORAGE allow

PHONE REMOTE
   Open the address shown on the TV, e.g. http://192.168.1.33:8080, on your
   phone (same Wi-Fi). Add it to your home screen for one-tap access.
   Tabs:
   - Remote: D-pad / OK / Back / Menu / Home, seek bar, play/pause, +-10/30 s,
     next/previous, volume up to 200% (boost), mute, queue.
   - Audio & Subs: choose audio track, choose/turn off subtitles, upload a
     subtitle file or paste a subtitle link (.srt .ass .vtt .zip .gz),
     subtitle delay, audio delay (lip-sync), subtitle size/background/encoding.
   - Picture & Sound: aspect ratio (16:9, 4:3, 21:9, 2.35, 2.39, 1.85, 18:9,
     16:10, 5:4, 1:1, 3:2, 32:9), fit (best fit / fill-crop / stretch /
     original), zoom/crop presets to remove black bars, equalizer with presets
     and 10 bands, speed, sleep timer, decoder, buffer size.
   - Links: paste any link, stream or MAGNET and press "Play on TV", add it to
     the queue, or save it to a playlist. Import M3U playlists.
   - Playlists: play, reorder, delete.
   Shortcut: http://TV-IP:8080/?url=LINK  sends LINK straight to the TV.
   The phone page keeps working while a video plays and after TV reboot.

TV REMOTE KEYS WHILE PLAYING
   OK = pause/play          Left/Right = -10/+10 s (hold = 30 s steps)
   Down or Menu = options   Up = show/hide info
   CH+/CH- (or media next/prev) = next/previous video
   0-9 = jump to 0%-90%     Back = leave
   Red = fit, Green = audio track, Yellow = subtitles, Blue = zoom (if your remote has them)

MAGNET / TORRENT LINKS
   Getting the torrent info takes 10-60 s, then it plays while downloading.
   Torrents with several videos become a playlist. Data is stored in the app's
   cache (on a USB drive if one has more space) and deleted when you stop or
   leave the player. Popular torrents (many seeders) stream best.
   Only play content you have the right to watch.

FORMATS
   Uses the VLC engine: mkv mp4 avi wmv flv mov ts m2ts vob iso webm rmvb 3gp
   ..., audio mp3 flac aac ac3 dts opus wav ..., streams http https hls(m3u8)
   dash rtsp rtmp udp. Other apps can use "Open with TamilCastPlay".
   Subtitle files next to a local video with the same name load automatically.

IF THE BUILD FAILS WITH "Could not find org.videolan.android:libvlc-all:3.6.2"
   In app/build.gradle change 3.6.2 to 3.6.0 (or 3.5.1) and commit again.

Add to the tv-detox home screen:
   adb shell am start -n com.ansub.tvhome/.MainActivity --es apps org.videolan.vlc,in.startv.hotstar,com.netflix.ninja,com.mxtech.videoplayer.ad,com.mxtech.videoplayer.television,com.tamil.castplay
To remove the app:  adb uninstall com.tamil.castplay
