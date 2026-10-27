ZH PLAY 4.0.1 — screen cast fixed (black screen / phone hanging)
===============================================================
* Removed the 60 fps cap added in 3.9.2: on some phones it broke the video encoder
  (black picture on the TV / PC, phone hanging). The TV and the Linux PC (1.0.1+) show
  frames as they arrive, so there is no delay without the cap.
* Safer casting: if the encoder refuses the low-delay settings, ZH Play retries with
  basic settings; if no picture comes within 4 s it restarts the encoder in safe mode
  instead of hanging; the screen capture is detached before an encoder restart.

ZH PLAY 4.0 — Present mode (PDF slides on the TV / Linux PC)
===========================================================
Phone remote → tile "Present" → choose a PDF (PowerPoint / Google Slides: Share → PDF).
* The TV (or Linux PC 1.1+) downloads it from the phone and draws every slide at its own
  screen resolution: sharp small text, no video blur, no notifications on the big screen.
* Phone = clicker: big current slide + next slide, ◀ Previous / Next ▶, phone volume keys
  (down = next, up = previous), slide strip to jump, red laser pointer (drag on the slide),
  Blank screen, First slide, Other PDF, timer, End.
* The phone screen may turn off — the slides stay on the TV. TV remote ←/→ works too.

ZH PLAY 3.9.2 — screen cast without delay on fast phones
=======================================================
* Cast screen is capped at 60 frames a second (Android 10+). 90 / 120 Hz phones sent up to
  120 frames a second; the PC / TV fell behind and the picture lagged 3–4 s and more.

ZH PLAY 3.9.1 — browser background play really works
====================================================
* Videos / songs in the browser keep playing when you switch apps, lock the screen or
  open another screen: Android's web engine paused video whenever its window was hidden —
  the browser view now stays "visible" to the engine while Background play is on.
* The browser no longer disappears when you come back through the ZH Play icon: it now
  runs as its own task (shown separately in Recents as "ZH Play Browser"). Links sent to
  an open browser open in a new tab.
* EQ in the browser opens as a panel over the page (ON/OFF, presets, preamp + 10 bands,
  bass / treble, curve) — the video stays on screen and keeps playing; changes are heard
  live. "Full screen" button opens the complete equalizer.

ZH PLAY 3.9 — DJ mixer, song editor, screen cast to the TV
=========================================================
DJ mixer (phone: board tile "DJ mixer", or Features)
* Two decks: scrolling waveform, auto BPM (measured exact on test tracks), play, CDJ-style
  cue, 4 hot cues (hold = clear), beat loops 1/2/4/8, pitch ±8 % / ±16 % (hold the % label),
  nudge −/+ (hold), SYNC (matches tempo AND lines up the beats).
* Mixer: trim, 3-band EQ high / mid / low with kill, one-knob filter (left low-pass, right
  high-pass), beat-synced echo, channel faders, constant-power crossfader, master,
  Auto-fade over 8 bars. ● REC records the mix to Music / ZH Play.
* Tempo works like a turntable (pitch follows tempo); no key lock yet.
Song editor (board tile "Song editor", or Features)
* Multi-track, non-destructive (the songs on the phone never change), zoomable timeline
  with waveforms; modes Select / Move / Scroll; Split, Cut, Copy, Paste, Delete, Trim,
  Silence, Fade in / out, Amplify, Normalize, Reverse; per-track mute / solo / volume /
  rename / delete (tap the track name); Undo / Redo; Export to M4A.
Cast screen (board tile "Cast screen")
* Shows the whole phone screen on the TV, live (H.264 over Wi-Fi), turns with the phone;
  the phone's sound too on Android 10+ (apps that block recording stay silent).
  720p by default, 1080p option. Stop from the notification or the tile; Back on the TV.

ZH PLAY 3.8 — browser: background play, history & bookmarks, fullscreen lock, page EQ
=====================================================================================
* Background play (like Brave): videos / songs keep playing with the screen locked or
  another app open; notification + lock-screen controls (−10 s, play / pause, +10 s,
  close). Pages are told they stay visible, so YouTube and others don't pause themselves.
  Menu → "Background play" On / Off.
* Restore tabs: open tabs come back after closing the app (menu → "Restore tabs").
* History & bookmarks manager: site icon, page title, site name and time; grouped by
  Today / Yesterday / date; search; ⋮ = open, open in new tab, copy link, bookmark,
  delete; clear last hour / today / 7 days / everything. Bookmarks: add, edit, remove.
* Fullscreen video: 🔒 lock (touches and Back are ignored until unlocked), turns to
  landscape like a player, EQ button.
* Page equalizer: a floating EQ button appears when a page plays video or audio; it opens
  the same Poweramp-style equalizer as the music player and applies it live to the page.
  Some sites block this for their videos — the app says so and the video plays normally.

ZH PLAY 3.7 — Karaoke master + waveform zoom
===========================================
Karaoke master (Features → Karaoke master): replaces the old 30-second tool.
* The song is read once; three waveforms: ORIGINAL · VOICE (taken out) · MUSIC
  (karaoke). Voice + Music always add back up to the original.
* Listen live: Original · Karaoke · Voice only · Mix, and a big "Karaoke ON / OFF" button
  to compare while it plays. Tap the waveform to jump; −5 s / +5 s.
* Filters & adjustments, applied live: vocal removal %, vocal range from / to (keeps bass
  and cymbals in the music), centre focus, music bass / treble EQ, stereo width, guide voice
  for Mix, volume. Presets: Standard, Strong, Gentle, Male voice, Female voice, Keep backing
  vocals, Keep bass & drums.
* Save the WHOLE song as Karaoke, Voice only or Mix (karaoke + quiet guide voice), M4A,
  in Music / ZH Play.
* Engine: frequency-domain (FFT) centre extraction — measured on a test mix: centre vocal
  −73…−94 dB, panned instruments, bass and hi-hat unchanged (0 dB). Real songs: works best
  on studio stereo mixes; reverb on the voice, mono and live recordings leave some voice —
  use "Voice only" to check what is taken out.
Waveform zoom (1×–40×) with scrolling and a time ruler on Karaoke, Cutter and Splitter.

ZH PLAY 3.6 — Poweramp-style equalizer + Features menu (phone music)
====================================================================
Equalizer (Music → Now playing → Equalizer, or Features → Equalizer)
* Tabs like Poweramp: Equalizer · Tone · Effects. ON/OFF, presets (20 built-in incl.
  Tamil melody, Kuthu / Folk, Car, Headphones, Podcast…) + your own saved presets.
* 10 bands 31 Hz – 16 kHz (±15 dB) + preamp (±12 dB), glowing sliders (double-tap = 0),
  response curve over a LIVE spectrum (the moving bars — Android asks for the
  "microphone" permission for this; nothing is recorded).
* Tone: big Bass and Treble knobs (−100…+100 %), bass boost, left/right balance.
* Effects: limiter (no distortion from strong boosts), stereo width, loudness, reverb
  (rooms / halls / plate), tempo 0.5×–2×.
* Android 9+: real 10-band engine (DynamicsProcessing) with preamp + limiter.
  Older phones: the basic equalizer (bands mapped automatically).
Features menu (✨ button in Music, ⋮ in Now playing, board tile "Features")
* Video → Audio: MP4 / MKV soundtrack → M4A (copied without quality loss when AAC).
* Cutter: waveform, drag start / end handles, ±0.1 s, play preview, "start/end = here",
  fade in / out.
* Splitter: markers on the waveform ("Split here", every 1 / 5 min, into 2 / 3).
* Merger: add songs, reorder, join.
* Karaoke maker: removes the centre vocal, keeps panned instruments and (optionally)
  bass & drums; strength 0–100 %; 30 s preview.
* MP3 stays MP3 for cut / split / merge (frame-exact, no quality loss, correct length
  header). Other results are M4A (AAC) — Android has no MP3 encoder.
* Results are saved in Music / ZH Play (appear in Music → Folders).

ZH PLAY 3.5 — complete music player (phone)
==========================================
(Built on 3.3 — the 3.4 TV/Mobile split was removed; one APK as before.)
Library tabs: Songs · Albums · Artists · Genres · Folders · Playlists (all 8 view modes).
  Inside an album / artist / … / playlist: ‹ back, Shuffle, Play all.
Playlists: + New playlist, add songs (hold a song or a whole album → Add to playlist…),
  remove, move up / down, rename, delete. Built-in: ♥ Favourites, Recently played,
  Most played.
Hold a song: Play next · Add to queue · Add to playlist · ♥ · Remove / Move (in a
  playlist) · Edit song info · Details · Play on the TV.
Now playing: ♥, Equalizer, Lyrics, Play on TV, ⋮ (speed, sleep timer, add to playlist,
  edit info). Hold a song in "Up next": play now, move, remove, add to playlist.
Equalizer: on/off, 12 presets (incl. Tamil melody, Kuthu / Folk, Vocal), 5 bands
  (bass … treble), bass boost, surround (3D), loudness, left/right balance. Saved.
Lyrics: synced lines highlighted while the song plays (LRCLIB, free); saved for
  offline. Plain lyrics when only those exist.
Sleep timer: 5–90 min with a gentle fade-out, or "at the end of this song".
Speed: 0.5× – 2× (audiobooks, lectures).
Edit song info: title / artist / album / genre, saved in ZH Play (file not changed).
Bluetooth: works with headphones / car / speakers incl. their buttons; pauses when they
  disconnect. Background + lock screen as before. Fully offline (lyrics need internet once).
Accessibility: all icon buttons are named for TalkBack; text follows the phone font size.
Errors: "Can't play … unsupported, damaged or deleted — playing the next song."

ZH PLAY 3.3 — new name and logo (was TamilCastPlay)
==================================================
* The app is now called "ZH Play" everywhere you can see it (launcher, TV home, menus,
  messages, notifications, share menu, browser page, Google Assistant: "play … on ZH Play").
* New ZH logo: app icon (Android shaped / round icons), Android TV banner, opening
  animation, first screen and TV home header, phone widget, notifications, browser page
  (header, opening animation, browser tab icon). Shown on a white badge so the black
  "H" stays visible on dark screens.
* The internal app ID (com.tamil.castplay) and signing key are unchanged on purpose:
  this update installs over the old app and keeps all playlists, settings, profiles and
  downloads; auto-update keeps working. Old "TamilCastPlay-backup" files are still found.
* If your TV / phone still shows the old icon right after updating, restart the launcher
  or the device once (launchers cache icons).

TAMILCASTPLAY 3.2 — card style + view modes for phone Videos / Music
====================================================================
* View button (grid icon, top right) like Windows Explorer: Extra large icons, Large
  icons, Medium icons, Small icons, List, Details, Tiles, Content. Remembered separately
  for Music and Videos. Default: Large icons (cards).
* Cards: big cover (square for songs, 16:9 for videos), duration badge, cast button,
  title and artist / folder.
* Real covers: the picture stored inside the song file (MP3 / M4A / FLAC) is used when
  Android has none; songs without any cover get a colourful card with the first letter.
* Hold any song or video (or the cast-less tile) = Details sheet: big cover, artist,
  album, composer, year, genre, track, duration, bitrate, sample rate, format, size,
  folder, date added, file name; video: resolution (4K / 1080p / 720p …) and frame rate.
  Buttons: Play here · Play on the TV.

TAMILCASTPLAY 3.1 — new browser + Shield ad blocker, phone music fix
====================================================================
FIXED: in Videos / Music on the phone, tapping a song or video now plays it HERE on the
phone (only the small cast icon sends it to the TV). Cause: a button inside a list row
stops Android from passing the row tap. Music player stays vertical (portrait).
Browser (Chrome / Brave / Edge style)
* Rounded address bar with the site's lock / host, Shield button with the number of
  blocked ads, thin loading bar, suggestions from history and bookmarks.
* Phone: bottom toolbar (back, forward, home, tabs, menu). TV: top toolbar.
* Tab grid with page previews; private tabs; find in page; desktop site; bookmarks;
  history; clear browsing data; send to TV.
* New-tab page: search box, shortcut tiles, recent sites, "ads & trackers blocked".
Shield (Brave-style, on by default, per-site switch)
* Blocks ad and tracker servers: built-in list + EasyList, EasyPrivacy and Peter
  Lowe's list (downloaded and refreshed weekly). Hides empty ad boxes.
* YouTube in the browser: video ads are skipped automatically (muted + fast-forwarded,
  skip button pressed) and banner ads hidden. Best effort — YouTube changes often.
* Does NOT affect the official YouTube app (no app can do that).

TAMILCASTPLAY 3.0.1 — phone plays on the phone
==============================================
* Share → TamilCastPlay (from Gallery, WhatsApp, Files …) now asks:
  "Play here on this phone" or "Play on the TV". The share entry is named
  "TamilCastPlay" (was "Play on TV"). Cast to the TV later with the cast button.
* Songs opened or shared from other apps open the music player (spinning disc,
  background play, lock-screen controls).
* Audio played in the video player (phone and TV) shows the spinning disc with the
  song's cover and artist instead of a black screen.

TAMILCASTPLAY 3.0 — Videos & Music on the phone
==============================================
Phone app: new board tiles "Videos" and "Music" (also on the first screen).
* Finds every video / song on the phone (internal storage, SD card, WhatsApp,
  Telegram, downloads …) through Android's media library. Search, sort, folder chips.
* Videos: thumbnail grid with duration; tap = play on the phone (gestures, subtitles,
  everything of the player). Small cast icon on each video = play it on the TV.
  Inside the phone player: new cast button in the top bar = continue on the TV at the
  SAME second (the rest of the list follows).
* Music: list with album art; tap = new music player — round album art in a glass ring
  that turns while playing, shuffle / previous / play / next / repeat (one / all),
  seek bar, "Up next" queue. Keeps playing in the background with notification and
  lock-screen controls; pauses for calls and when headphones are unplugged.
  Mini player at the bottom of the music list. Cast icon = continue on the TV.
* Android asks once for permission to read videos / music.

TAMILCASTPLAY 2.9.2 — YouTube from the assistant really opens / plays
=====================================================================
* Android blocks apps in the background from opening other apps, so "play … on YouTube"
  silently did nothing. Now YouTube (and other apps) are opened through the ADB full
  remote, which is always allowed — or directly when "Display over other apps" is on.
  If neither is possible the assistant says so instead of pretending.
* "YouTube Ilaiyaraaja songs" / "… podu" / "play …" = finds the first matching video and
  PLAYS it in YouTube. "search youtube …" / "thedu" = shows YouTube's search results.
* Opening apps from the phone / assistant also works while TamilCastPlay is in the
  background (ADB).
* Failed steps are shown with ⚠ and the reason.

TAMILCASTPLAY 2.9.1 — torrent playback fixes
============================================
* A finished torrent movie now plays straight from storage (smooth, no peers, no
  1→100 % re-check), from Continue watching, Recent and Downloads.
* If the download finishes while you watch, playback switches to the stored file at
  the same second ("Download finished — now playing from storage").
* "Keep this movie?" is asked only at the REAL end of the movie. If a stream stops
  early (data not ready / network blip) the player simply continues from the same spot
  instead of asking and closing.
* Part-downloaded torrents remember their progress (resume data, saved every minute and
  when you stop) — no more re-checking from 1 % on the next play.
* The stream reader waits for data to be written to storage instead of giving up.

TAMILCASTPLAY 2.9 — AI assistant (bring your own AI)
===================================================
Phone app > Board > "AI assistant" (also Voice > "Ask the AI assistant").
* Ask in Tamil, English, Hindi or Tanglish: "Sun TV podu, volume kammi pannu",
  "open Netflix and turn off in 30 minutes", "YouTube Ilaiyaraaja songs", "pause".
  The AI only picks from the app's own actions (play channel, YouTube / TV search, open
  app, volume, TV volume, play/pause/next/prev/stop, seek, sleep timer, keys, typing,
  recap, translate, power). Without AI (or if the AI is unsure) a Tamil / Tanglish /
  English keyword backup still handles simple commands.
* What did I miss? — 3-line summary of the last 10 minutes, from the subtitles,
  in your app language.
* Translate subtitles — the loaded subtitle becomes Tamil / Hindi / English /
  Malayalam / Telugu / Kannada and is shown on the TV right away.
  (Recap and translation need a subtitle file: load one or use Find subtitles online.)
AI settings (in the assistant panel): choose a preset — OpenRouter, Groq, Together,
Google Gemini, Anthropic Claude, OpenAI, Ollama or LM Studio on your PC (free) — or
Custom; enter the API key and the model name (e.g. gemma-3-12b-it); press Test.
Privacy: the key is encrypted with the phone's Android Keystore and sent only to the
address you chose. Only the request text, the current title, app and channel names
(or subtitle lines for recap / translation) are sent. The TV never sees the key.

TAMILCASTPLAY 2.8 — keyboard + voice search
==========================================
* Big "Keyboard" and "Voice" buttons under the remote (phone app).
* Keyboard opens the live typing box any time — needed for apps with their own
  letter grid (YouTube on TV has its own keyboard, so Android doesn't report it).
  It still opens by itself when Android's keyboard shows, or (with Accessibility on)
  when focus lands on a text box.
* Voice (phone speech recognition, English / தமிழ் / हिन्दी — long-press to change):
  - a TV text box is open → the words are typed into it,
  - otherwise choose: Search on YouTube, Search the whole TV (Google TV search),
    Play in TamilCastPlay (best matching channel / video), Type into the TV.
* Browser page: Keyboard + Search buttons (voice needs the phone app: browsers only
  allow the microphone on https pages).

TAMILCASTPLAY 2.7.1 — remote as fast as possible
================================================
* Keys travel over one always-open "key line" (TV port 8081) instead of a web request
  per press — sent instantly, in order (tested: 40 keys in ~21 ms).
* On the TV, keys and the "is the keyboard open?" check use separate connections to the
  key helper, so a key never waits behind that check.
* Slow TV checks (keyboard open, what other apps play) now run in the background; the
  status the phone asks for is answered instantly.
* Hold an arrow on the D-pad = repeats (fast scrolling), phone app and browser page.

TAMILCASTPLAY 2.7 — instant keys in every app, live typing, icon fix
====================================================================
* Instant keys outside TamilCastPlay (YouTube, Netflix, launcher …): with the 🔌 ADB
  full remote on, the TV starts a tiny key helper (same method as scrcpy) and presses
  keys directly — a few milliseconds instead of ~0.5–1 s. Falls back automatically.
* Live typing like the Google TV remote: when a text box / keyboard opens on the TV,
  the phone (app and browser page) shows "Type on the TV"; every letter appears on the
  TV as you type, backspace deletes, ✓ / Enter sends. Close ✕ hides it.
* Icons: fixed a drawing bug that bent multi-part icons (the ✕ looked like a strange
  mark). Panel close buttons are now a clear red ✕.

TAMILCASTPLAY 2.6.1 — crash fix
===============================
* FIXED: the app closed whenever a message bubble appeared (volume / brightness
  sliders on the phone, ADB connect, many others) — "StackOverflowError in
  UiKit.makeToast". The build now refuses to compile if this ever comes back.
* ADB connect shows a clear "Look at the TV, press OK on 'Allow USB debugging?'"
  screen while waiting, then the result.

TAMILCASTPLAY 2.6 — no more emojis, professional icons everywhere
=================================================================
* Every menu and dialog (Settings, player menu, Family, Network, Browser …) now shows
  Lucide icons in front of the options instead of emojis.
* TV player controls are Lucide icon buttons (play, ±10 s, channels, audio, subtitles,
  picture, equalizer, repeat, shuffle, menu, home, settings, rotate, lock).
* Buttons, messages (toasts / on-screen info), notification and widget text never show
  emojis; normal symbols like → ✓ ★ stay.
* First screen (phone): TVs as cards with icons; Scan again / Enter TV address /
  Browser / Use this phone are board tiles.
* Browser page: any emoji (also in lists and messages added later) becomes a Lucide
  icon automatically.

TAMILCASTPLAY 2.5 — phone volume buttons, widget, Google Assistant
==================================================================
* Phone volume buttons control the TV while the remote is open. Choose under the
  remote: "Phone volume buttons: TV volume / Player volume / Off".
  With the TV notification active they also work on the lock screen / in other apps
  (Android "remote volume", like Chromecast; a few phones limit this).
* Home-screen widget (long-press the phone home screen > Widgets > TamilCastPlay):
  title, ±10 s, play/pause, TV volume − / mute / +, power. Tap the title to open the remote.
* Google Assistant (no Google sign-in needed; only the spoken words reach the app):
  "Hey Google, play Sun TV on TamilCastPlay" -> finds the channel / video in your
  playlists (favourites first) and plays it with its playlist.
  "Hey Google, play TamilCastPlay" -> continues the last video.
  While TamilCastPlay plays on the TV: "Hey Google, pause / resume / next / previous /
  rewind 30 seconds / stop" (the player now reports to Android as a media player,
  which also makes remote media keys work better).

TAMILCASTPLAY 2.4 — new design
==============================
Phone app + browser page, same layout:
* Opening animation.
* Mini player at the top: title + play/pause; the arrow opens / closes the timeline
  and controls (prev, -10 s, +10 s, next, stop, bookmarks, screenshot, audio only,
  repeat, shuffle).
* Remote card: D-pad or Touchpad (swipe = arrows, tap = OK, hold = back, like
  Google TV). Volume (left) and brightness (right) are vertical swipe sliders that
  go green → yellow → red. Keys: back, home, recent apps, player menu, POWER,
  TV volume, play/pause for any app, keyboard.
* Board: tiles that open their own panel (slides up, close with ✕ or Back):
  Live TV screen, Channels & queue, Apps, Cast, Audio & subtitles, Picture & sound,
  Playlists, My TV, Share this phone, Power, Colours, Switch TV.
* Live TV screen: see the TV on the phone (~1 picture / s) with a touchpad under it
  (needs the 🔌 Full remote ADB; protected apps like Netflix show black).
* Power: sleep / screen off, wake, power key, restart the TV, restart the app.
* Professional Lucide icons everywhere (ISC licence, see assets/LICENSE-lucide.txt).
Network folders (TV)
* Add a server with type + port: SMB, FTP, FTPS, SFTP, NFS, HTTP/WebDAV.
* Phones with "Share this phone" on appear automatically: the TV browses the phone's
  videos / music and plays them directly (read-only, turn off any time).

TAMILCASTPLAY 2.3.2 — other apps' videos on low-RAM TVs
=======================================================
Low-memory TVs (adb shell getprop ro.config.low_ram = true) block notification access
for every app, so "Show other apps' videos" could not be switched on. Now, when the
🔌 Full remote (ADB) is connected, TamilCastPlay reads what YouTube / Netflix / … are
playing through ADB instead: app name, title, play / pause state and position, with
⏯ play/pause, ⏩ ⏪ (fast-forward / rewind), ⏭ ⏮ next / previous and ⏹ stop.
(The total length and jumping to an exact time are not available this way.)

TAMILCASTPLAY 2.3.1 — 🗣 Clear dialogue
======================================
"Night mode" is now "Clear dialogue" with levels Off / Light / Medium / Strong.
It works ALL the time while on (not only at night): loud background music and fight
scenes are squeezed, voices are lifted (voice frequencies up, booming bass down).
Works together with your own equalizer. Set it once: ☰ menu > 🗣 Clear dialogue,
phone app 🎚 tab, or browser page Picture/EQ. Old "Night mode On" = Medium.

TAMILCASTPLAY 2.3 — player extras, Library, Network folders, Family
===================================================================
Player (☰ menu, phone Remote tab, browser page)
* Preview pictures above the timeline while you seek (drag, ◀ ▶ on the bar, or
  swipe sideways) — local / USB / web videos.
* 🌙 Night mode (loud scenes softer, dialogue clear) and 📊 Volume levelling
  between channels / videos.
* 🔖 Bookmarks: "Bookmark this scene" (or the remote's Bookmark key) and jump back
  later from "Bookmarks". Kept per video and per profile.
* 📸 Screenshot: saved to a USB drive (TamilCastPlay Screenshots folder), else
  Pictures/TamilCastPlay.
* 🎵 Audio only: picture off, sound keeps playing — also when you press Home or
  the phone screen turns off.
* 🎞 Auto frame-rate: switches the TV to the movie's rate (23.976 / 24 / 25 / 50 Hz)
  on TVs that offer those modes.
* ✓ Watched marks are set automatically when a video ends.
🎬 Library (TV home)
* Poster grid of movies from Downloads, USB drives and storage (videos over 40 MB).
  Posters: picture from the video, or real posters + rating + story with a free
  TMDB key (🔑 button). Sort: recently added / name / biggest. Show: all / not
  watched / watched / last 30 days. OK = details, Play / Resume, mark watched.
🖧 Network folders (TV home)
* Finds PCs and NAS on the Wi-Fi (Windows file sharing / SMB, DLNA), or add one:
  address like 192.168.1.10/Movies or \\PC\Movies, optional username / password.
  Browse folders and play without copying. Long-press a saved server to edit it.
👨‍👩‍👧 Family
* Profiles: profile button on the TV home (top bar). Each person has their own
  Continue watching, favourites, recent channels, positions, watched marks and
  bookmarks. "Who's watching?" appears at start when there is more than one.
* Kids profile: parents' PIN; locked playlists and apps are hidden / need the PIN;
  daily screen-time limit (player stops, "Parents: +30 min" with the PIN); leaving
  the kids profile needs the PIN. ⚙ Settings > Profiles & kids lock.

TAMILCASTPLAY 2.2 — big update
=============================
IPTV
* Channel list (📺 button / OK on live TV): channel LOGOS, 📂 CATEGORIES (from the
  list's groups: News, Movies, Kids…), ★ FAVOURITES (long-press / hold OK on a channel),
  🕘 RECENT channels. "Last channel" remote key jumps back to the previous channel.
* 🩺 Check channels: open a playlist on the TV home > "Check channels" — checks every
  channel in the background; then "Remove channels that don't work".
* Playlists: ✏ rename, 🔄 update channels from link, long-press a channel to edit
  name / link, ★ favourite, move, delete (TV, phone app and browser page).
* The iptv-org world list (https://iptv-org.github.io/iptv/index.m3u) is added
  automatically once on the TV as "IPTV · World (iptv-org)". Rename, edit or delete it
  like any playlist — once deleted it does not come back.
Player
* ⏩ "Skip intro" (first 3 minutes, OK or tap, skips 85 s) and "⏭ Next: … in 15 s"
  before the end of an episode (OK = play now).
* 🔎 Online subtitles: Subtitles menu > "Find subtitles online…" (TV, phone, browser).
  Languages and an optional free OpenSubtitles API key: "Online subtitle settings".
Torrents
* ⬇ Download for later (browser magnet links, phone Cast tab, browser page): full
  download in the background, optional "night only" (1–6 AM). Finished movies go to the
  "Downloads" playlist and play offline.
Remote
* Shows and controls videos playing in OTHER apps (YouTube, Netflix, Prime, Spotify…):
  title, time bar, play/pause, seek, next. One-time switch: TV ⚙ Settings > "Show other
  apps' videos on the remote" (switches on by itself when the 🔌 ADB full remote is on).
Browser
* Tabs (⧉), history 🕘, recent sites on the start page, bookmarks, clear browsing data
  (history / cache / cookies / tabs), desktop site, private mode.
* Magnet / .torrent links: asks "Play now" or "⬇ Download for later".
App
* ⬆ Auto-update: the TV checks your GitHub releases (every 12 h, or ⚙ Settings > Check
  for updates) and installs the new APK — no laptop. The GitHub build now publishes a
  release automatically (needs the 4 signing secrets). First time Android asks to allow
  "install unknown apps" for TamilCastPlay.
* 💾 Backup & restore: ⚙ Settings (TV Download folder / USB) or phone browser page
  (download / upload the backup file).

TAMILCASTPLAY 2.1 — 📊 My TV
===========================
TV home > "📊 My TV" (also phone remote: Playlist tab > My TV, and browser page: Library tab).
* Storage: used / free for internal, shared and every USB / SD drive, plus how much
  the saved torrents use.
* Memory (RAM): used / free, low-memory warning, how much TamilCastPlay uses.
* Processor: chip name, cores, max speed, live speed of each core, temperature.
* Screen: resolution, refresh rate, panel max, HDR types (HDR10, HLG, Dolby Vision).
* Video support: hardware H.264 / HEVC / VP9 / AV1 / Dolby Vision, Dolby & DTS audio.
* Network: Wi-Fi 2.4/5 GHz, signal, link speed or Ethernet, IP address.
* Tests: Internet speed (ping, download, upload, with "good for 4K/1080p/720p"),
  Storage speed (write/read MB/s), CPU score + RAM speed (Android does not report
  RAM MHz, so it is measured).
* 🧹 Free up memory: closes background apps (with the 🔌 ADB full remote it also
  stops all background apps and clears caches). 📋 Running apps (needs ADB): list
  with memory use, tap one to stop it.

TAMILCASTPLAY 2.0 — brightness, torrent cache, quality, full remote
====================================================================
* ☀ Brightness on the TV picture: phone remote (Remote tab, under Volume), browser
  page, and player ☰ menu. 10-99% dims the picture (works on every TV);
  101-150% brightens it with a picture filter (uses the software decoder).
* Torrent cache: data now stays on the TV. A hang / going back / playing again
  re-uses what is already downloaded (no extra internet), even for magnets
  (the torrent info is saved too). Auto-retries 3x when a torrent stalls.
  When a torrent movie ends: "Keep this movie on the TV?" Keep = "Downloads"
  playlist, plays offline. Delete = frees the space. Not-kept data is removed
  after 7 days or when free space drops under 2 GB.
  Storage: TV ⚙ Settings > Torrent storage, or phone Cast tab.
* 📶 Streaming quality limit (Best/1080/720/480/360): IPTV/HLS/DASH streams play
  the lower quality = less data. Torrents: when a torrent has several versions,
  the matching one is picked first (sizes shown). A single torrent file cannot be
  made smaller — the whole file must download.
* 🔌 Full remote for every app (ADB): real key presses in YouTube, Netflix,
  launcher, settings. TV: Developer options > USB/Network debugging ON, then
  ⚙ Settings > Full remote (or the 🔌 button on the phone) > Connect > on the TV
  tick "Always allow" > OK. Keys take ~0.5 s each this way.

TAMILCASTPLAY 1.9 — player keys fixed + colour palettes
=======================================================
Player (TV remote)
* OK now plays/pauses even when the timeline bar is selected.
* Back no longer closes the player by accident: 1st press hides the controls,
  then "Press Back again to exit". Stop key still exits straight away.
* Every rewind / forward key style works (Rewind, Fast-forward, Skip/Step back &
  forward): 10 s per press, 60 s when you hold it.
* If anything ever closes the app, the TV home shows the reason next time (Copy
  button) — please send it if that happens.
Colours
* ⚙ Settings > Colours & style: 10 palettes (Purple Night, Ocean Blue, Emerald,
  Sunset Orange, Ruby Red, Royal Gold, Pink Rose, Mono Grey, Teal & Amber,
  Midnight Blue) plus your own colours for: main colour, second colour,
  background, cards and top/bottom bars. "Reset custom colours" goes back.
* Phone app: Playlist tab > "TV colours" (changes the TV) and
  "This phone app's colours" (changes the phone app).
* Browser page follows the TV's colours (switch off in Library > TV settings).

TAMILCASTPLAY 1.8.1
===================
* "Control the whole TV" / permission buttons now try the TV-brand Settings screens
  (Android TV settings, phone settings, main Settings) and skip ones that only show
  "no app can handle this action". If nothing opens, the app shows the path and an
  adb command (Copy button). Manual path on most TVs:
     Settings > Device Preferences > Accessibility > TamilCastPlay phone remote > ON
  adb (PC or a phone adb app):
     adb shell settings put secure enabled_accessibility_services com.tamil.castplay/com.mytv.player.TvAccess
     adb shell settings put secure accessibility_enabled 1

TAMILCASTPLAY 1.8 — timeline, gestures, notification remote, whole-TV control
=============================================================================
Player
* Timeline you can use: drag it with a finger, or on the TV press ▼ (buttons) then ▲
  to reach the bar and use ◀ ▶ (10 s steps, it jumps when you stop pressing).
* Phone gestures like MX Player / VLC:
    left side up/down  = brightness
    right side up/down = volume (goes past 100% up to 200% boost)
    swipe sideways     = seek (shows +time and target time)
    double-tap left / right = -10 s / +10 s, double-tap middle = pause
Phone remote
* Notification bar + lock screen controls for the TV: -10s, play/pause, +10s, next, stop.
  Tap the notification to open the remote; swipe it away to close it.
  (Android 13+: allow notifications when the app asks.)
* Whole-TV control like the Google TV remote: arrows, OK, Back, Home, Recent apps (▢),
  TV volume 🔉 🔇 🔊, play/pause for any app (⏯), and "Type text" into search boxes.
  One-time switch on the TV: TamilCastPlay home > "🎮 Control the whole TV from the phone"
  (or ⚙ Settings) > choose "TamilCastPlay phone remote" > ON.
  TV volume and ⏯ work even without the switch.

TAMILCASTPLAY 1.7 — native phone remote
=======================================
* The phone app's remote is now a real Android screen (no web page inside the app):
  fits every phone size in portrait and landscape, normal keyboard for the PIN and
  search boxes, no zooming, no freezing, faster.
  Tabs: Remote · Subtitles · Equalizer · Cast · Playlist. Back = Remote tab.
  Playlists: tap 📂 to open with live search; long-press a channel for
  Play / Load all channels / Delete; "Select & delete…" with Select all.
  The browser page (http://TV-IP:8080) is unchanged; "🌐 Web version" is in the
  Playlist tab if you ever want it inside the app.
* Home screen and browser toolbars use icon buttons on phones so they fit.

TAMILCASTPLAY 1.6 — phone app fits the screen + player modes
=============================================================
Phone app remote:
* Fits every phone width: nothing spills off the side any more (the old rotated
  equalizer sliders were pushing the page wider than the screen).
* New equalizer sliders built for touch: drag up / down, they never overflow.
* Bottom tabs stay on one line in Tamil/Hindi; extra space at the bottom so the
  last buttons are never hidden behind the tab bar.
* Auto-hide: top and bottom bars slide away while you scroll down and come back
  when you scroll up (or tap the ☰ button). Turn it off in Library > This page.
* Smaller header on small phones.
Player:
* 🔁 Repeat: Off -> All (whole list) -> One (🔂 same video), and 🔀 Shuffle —
  buttons in the control bar, the ☰ menu, and the phone Remote tab.
* Phones: 🔄 rotation (Landscape -> Auto -> Portrait) and 🔒 screen lock in the top
  corner. While locked, touches and keys are ignored; tap the screen and press 🔓.

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
