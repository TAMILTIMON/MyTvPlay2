MY TV PLAYER 3.0 — what's new
=============================
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
  a .torrent file on the TV with "Open with My TV Player".
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
1. Sign in at github.com. Open your MyTVPlayer repository (or make a new
   Public one named MyTVPlayer).
2. Unzip MyTVPlayer.zip on your PC. Open the unzipped folder, select
   EVERYTHING inside (including the .github folder) and drag it into
   "Add file > Upload files". Replace the old files. Press "Commit changes".
3. Open the "Actions" tab. Wait 5-15 minutes for a green tick.
   A red cross means the build failed: open it, copy the error, send it to Claude.
4. Open the finished run > "Artifacts" > MyTVPlayer-apk. Unzip it. Inside:
      app-armeabi-v7a-debug.apk   (most TVs, 32-bit)
      app-arm64-v8a-debug.apk     (64-bit TVs)
      app-universal-debug.apk     (works on all, bigger)
   Not sure? Run:  adb shell getprop ro.product.cpu.abi
5. Install on the TV:
      adb connect 192.168.1.33:5555
      adb install -r app-armeabi-v7a-debug.apk
   Install on the phone: copy app-universal-debug.apk (or arm64) to the phone
   and open it (allow "install unknown apps"). Updates from 2.0 install over it.

ONE-TIME PERMISSIONS (run from your PC, recommended)
   Phone can start videos even when the app is closed:
      adb shell appops set com.mytv.player SYSTEM_ALERT_WINDOW allow
   See all files on USB/storage (including subtitle files):
      adb shell appops set com.mytv.player MANAGE_EXTERNAL_STORAGE allow

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
   dash rtsp rtmp udp. Other apps can use "Open with My TV Player".
   Subtitle files next to a local video with the same name load automatically.

IF THE BUILD FAILS WITH "Could not find org.videolan.android:libvlc-all:3.6.2"
   In app/build.gradle change 3.6.2 to 3.6.0 (or 3.5.1) and commit again.

Add to the tv-detox home screen:
   adb shell am start -n com.ansub.tvhome/.MainActivity --es apps org.videolan.vlc,in.startv.hotstar,com.netflix.ninja,com.mxtech.videoplayer.ad,com.mxtech.videoplayer.television,com.mytv.player
To remove the app:  adb uninstall com.mytv.player
