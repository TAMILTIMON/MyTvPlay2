ZH PLAY 9.0.2 — Phone ↔ TV encryption back ON (as in 9.0)
=========================================================
* The lag seen after 9.0 was a sync delay, not the encryption. 9.0.1 (on GitHub) had switched it off;
  9.0.2 switches it back on: ZhCrypt.ENABLED = true, the TV refuses unencrypted sign-in hand-overs again,
  and pairing over plain Wi-Fi gets a 6-hour pass. Same behaviour as 9.0.
* Secrets on the device stay encrypted (Android Keystore).

ZH PLAY 9.0.1 — Phone ↔ TV encryption switched off (speed back to normal)
=========================================================================
* 9.0's encrypted phone ↔ TV channel made each command take an extra internal round trip on the TV,
  which lagged the remote on TV boxes. It is OFF (ZhCrypt.ENABLED = false): commands, key presses,
  thumbnails, uploads, screen cast and the web remote go direct exactly as before 9.0.
* Pairing gives the lasting key again (no 6-hour pass); the TV accepts sign-in hand-overs directly again.
* KEPT (no effect on speed): secrets stored encrypted on the device (Android Keystore).

ZH PLAY 9.0 — Encrypted phone ↔ TV · secrets encrypted on the device
====================================================================
PHONE ↔ TV (local Wi-Fi), ZhCrypt:
* Phone and TV agree a fresh key with ECDH (EC P-256) — the key itself is never sent — and every
  command, answer, typed text, key press, thumbnail and sign-in hand-over is AES-256-GCM encrypted.
  Replayed or altered messages are refused. Tested: a snooper sees no token, text, e-mail or command.
* Who may control the TV is still the pairing key / same Google account — now sent inside the
  encryption. The PIN pairing itself runs encrypted. No re-pairing needed (the key is agreed automatically;
  a restarted TV is re-keyed by itself).
* Plain connections kept for speed (file upload, screen cast, web-page remote) use short-lived passes
  ("tickets"): one-time for upload / cast, 6 hours for the web remote — never the lasting key.
* Pairing over plain Wi-Fi (browser / older app) now gets a 6-hour pass, not the lasting key.
* The TV refuses sign-in hand-overs (/tvlogin, /seedr/login) that arrive unencrypted.
* Video streams are not encrypted by this layer (speed) — Seedr / Drive / YouTube already use HTTPS.
* An older TV still works (plain) and the phone shows "Update ZH Play on the TV" once.
ON THE DEVICE:
* Firebase and Seedr sign-in tokens, pairing keys, the admin's Firebase key and the GitHub token are
  stored encrypted with Android Keystore (hardware-backed). Old readable values are encrypted on first use.
* Nothing removed. Update BOTH phone and TV.

ZH PLAY 8.8 — Pictures for notifications & broadcasts · Netflix-style inbox
==========================================================================
* PICTURES, no extra setup: YouTube links → the video's thumbnail and Google Drive videos → Drive's
  preview, automatically. Any other link: admin panel → "🖼 Choose image" (gallery, shrunk to ~40 KB,
  kept in Firebase pub/thumbs) or a picture link. Live preview in the admin panel.
* Used in: 🔔 Notifications (hero + cards), phone notifications (big picture when the image is a web link),
  the TV home screen broadcast card, and the broadcast hero.
* 🔔 NOTIFICATIONS is Netflix-style: a big picture hero for the live / upcoming broadcast, sideways rows
  of picture cards ("New", "Earlier") that grow when focused with the TV remote, and a detail sheet
  (picture, message, ▶ Play / Close).
* Less data: apps now sync only the small parts of pub (scroller, broadcast, inbox, blocks); each
  picture downloads once and is cached on the device.
* Nothing removed.

ZH PLAY 8.7 — Android TV home screen row · 🔔 Notifications inbox
=================================================================
* TV HOME SCREEN (Android 8+ TVs whose launcher shows "Watch Next / Continue watching"): ZH Play adds
  your unfinished movies (with progress; select = resume) and the admin's broadcast ("🔴 LIVE: …" /
  "📅 … at 8:00 PM"; select = watch). Updates after watching and when a broadcast starts / ends;
  finished movies and ended broadcasts are removed. Android's own TV provider (TvContract), no library.
* 🔔 NOTIFICATIONS (phone board, TV home list, TV ✨ All features): the live / upcoming broadcast on top,
  then every announcement the admin sent (newest first, unread marked, link opens / plays). Missed
  notifications are never lost. The admin panel saves each notification it sends to pub/inbox
  (latest 50 kept). The tile / row shows "N new" or "🔴 LIVE now".
* Nothing removed.

ZH PLAY 8.6 — Ended broadcasts can't be replayed · Seedr on the TV
=================================================================
* BROADCAST VIDEOS are never saved to Recent / Continue watching. When the admin ends a broadcast
  (or its time frame is over), any saved copies and positions are removed, people still watching see
  "The broadcast has ended" and the player stops, and the link refuses to play afterwards. Only items
  that came from a broadcast (titled 🔴) are affected — the same movie from your own Seedr plays normally.
* SEEDR ON THE TV: "☁ Seedr — your cloud videos" in the TV home list (unless the admin switched Seedr
  off); also on the TV's ✨ All features board. On the TV a file's menu says "Play".
* No typing on the TV: phone → Seedr → ⚙ → "Sign in the TV with this account" (new TV route
  /seedr/login, paired / same-account phones only). Or "Sign in with a code" on the TV.
* Nothing removed.

ZH PLAY 8.5 — Manage users (sign out / remove / block) · sign-in diagnostics
============================================================================
* ADMIN → Users → a user → Manage: "Sign out user" (open apps go back to sign-in within a minute),
  "Remove user" (sign out + delete their record + delete their ZH Play cloud account with the imported
  Firebase key; they may sign in again), Block now also by e-mail (holds after a new sign-in).
* A removed / disabled cloud account sends that app back to the sign-in screen at once.
* SIGN-IN ON A PROBLEM PHONE: an instant "cancelled" answer is treated as a failure (not as the user
  closing the sheet); the app retries with Google's second sign-in method, then shows why — with this
  install's SHA-1, where it was installed from (Amazon Appstore installs are signed by Amazon), and fixes.
* Firebase rules: the admin may write "members" (needed to remove users) — see admin/ADMIN_GUIDE.md.
* Nothing removed.

ZH PLAY 8.4.1 — Pushed / premiere links play in ZH Player (Seedr, Google Drive, Dropbox)
=======================================================================================
* Seedr links (…/Movie.mkv?st=…&e=…) were not recognised because of the part after the file name, and
  Google Drive share links are pages — both opened in the browser, which downloaded them.
* Links.java: Drive (/file/d/…, open?id=, uc?id=) → direct stream (file must be "Anyone with the link");
  Dropbox ?dl=0 → raw; Seedr / tokened links recognised; links without an extension are checked with the
  server (headers only) before deciding. Used for notification links and premieres.
* Admin "Send notification": Open in Auto / ZH Player / Browser.
* IMPORTANT: Links.java must be in the repo — push the whole zip (it was missing on GitHub).

ZH PLAY 8.4.1 — Pushed video links play inside ZH Play
=====================================================
* Links without a file extension (download pages, Google Drive, Dropbox, short links) went to the
  browser, which downloaded them. Now: Drive / Dropbox share links become direct video links; for
  other unknown links ZH Play asks the server what it is (headers only) and plays videos, songs and
  streams in its own player. Web pages and other files still open in the browser.
* Admin → Send notification → "Open the link in": Auto / ZH Play player / Browser.
* Premiere and .m3u8 broadcasts also use the direct form of Drive / Dropbox links.

ZH PLAY 8.4 — Redesigned admin panel · ZH Play-style popups
===========================================================
* ADMIN PANEL is now a dashboard: profile card, stat cards (users / today / week / TVs) and feature
  cards, each opening its own page: Users · Broadcast · News scroller · Send notification · Live monitor
  · Push setup. Back goes to the previous page.
* USERS: search, filter chips (All / Active today / Phones / TVs), cards with avatar, online dot,
  device and version. A user's page: menus grouped (Media, Tools, Cloud & network, TV & remote) with
  on/off switches, "All on", Block (asks first), Save.
* BROADCAST: status pill (LIVE / SCHEDULED / NOT LIVE), type chips with an explanation, labelled
  fields, date/time button; Save / Save + notify / Start now / End now (confirm cards).
* SCROLLER and NOTIFICATION pages show a live preview of what users will see.
* POPUPS: ZH Play's own banners (slide in, success / error / info) and confirm / message cards instead
  of Android's plain toasts and dialogs; the admin's announcement shows as a styled card; a push that
  arrives while ZH Play is open slides in as a banner (tap opens it); system notifications show the
  full message with the ZH Play logo and colour.
* Nothing removed (GitHub sending stays under Push setup → Advanced).

ZH PLAY 8.3 — Push straight from the admin panel (no GitHub)
===========================================================
* Admin panel → Push setup → "Import Firebase key (.json)" once (stays only on the admin's phone).
  Then "Send a notification to everyone" (title, message, optional link) goes out directly: the key signs
  a Google login (RS256 JWT, verified against a real crypto library) → Firebase Cloud Messaging v1.
* "Save + notify all" and "▶ Start now" push directly too. GitHub is only an optional backup.
* Tapping a notification opens its link (video plays in ZH Play, YouTube in YouTube, pages in ZH
  Browser) or the live broadcast.

ZH PLAY 8.2 — Admin panel · broadcasts · news scroller · users & menus · instant push
====================================================================================
* ADMIN PANEL (Account → 📡, admins only): user count (today / week / TVs) with each user's device,
  version, last seen; per-user menu access and block; news scroller; broadcasts; live monitor; push setup.
* BROADCAST: YouTube Live · Premiere (any video file link, everyone in sync, late joiners jump to the
  current point) · Live stream (.m3u8). Schedule (start + length), Save / Save + notify all / Start now /
  End now. Apps show "📅 … live at …", then "🔴 LIVE NOW — tap to watch", and remind at start time.
* LIVE MONITOR: watching now, joined, peak, and who.
* NEWS SCROLLER on every home screen (phone + TV).
* INSTANT PUSH: Firebase Cloud Messaging via the GitHub workflow "Broadcast" (.github/workflows/
  broadcast.yml) — run it on GitHub or from the admin panel. Secret: FCM_SERVICE_ACCOUNT.
* Security: Firebase rules accept changes only from the admin e-mail (see admin/ADMIN_GUIDE.md).
* New library: firebase-messaging (BOM 33.5.1), set up from access.json (firebase_app_id).
* Nothing removed.

ZH PLAY 8.1.1 — ZH Play cloud: connect + link the TV, with visible errors
=========================================================================
* Accounts signed in before the cloud was set up had no cloud session (errors were hidden).
  "My TVs anywhere" now connects it right there (one Google tap); the account screen shows
  "Connect ZH Play cloud" and the Firebase error if it fails.
* The phone remote on the TV's Wi-Fi links a signed-in TV to the cloud by itself (once).
* Account → "ZH Play cloud status": set up? connected? device id, TV online since, last error.
* Empty "My TVs anywhere" explains how to link the TV.

ZH PLAY 8.1 — Same account = no PIN · control the TV from anywhere (Firebase)
=============================================================================
* SAME WI-FI: a phone signed in with the same Google account as the TV controls it without the PIN
  (the TV checks the phone's Firebase token — typing the e-mail can't fake it).
* FROM ANYWHERE: phone → your Firebase Realtime Database → TV. The TV listens all the time (live
  stream), runs each command on itself and answers. Phone home board → "My TVs anywhere", and the
  remote switches to the cloud by itself when the TV isn't on the phone's Wi-Fi. Commands older than
  2 minutes (TV was off) are dropped. Only the same account can read / write (database rules).
* Firebase settings come from admin/access.json (firebase_api_key, firebase_db_url) — no rebuild.
* admin/ADMIN_GUIDE.md: the sign-in clients must be in the Firebase project "zhplay".
* Plain Firebase REST — no new libraries. Nothing removed.

ZH PLAY 8.0.2 — TVs sign in right after the update
==================================================
* TVs no longer get the 1-day notice: once web_client_id is set in admin/access.json, every TV shows
  the QR sign-in screen straight away and stays locked until it is signed in. Phones unchanged
  (existing users: 1-day notice; new installs: at once).

ZH PLAY 8.0.1 — sign-in fixes
=============================
* TV: the QR sign-in screen closed itself after ~2 s during the 1-day notice period. It now stays
  until you sign in (QR / phone / Google on the TV) or press "Later".
* The notice now appears as soon as access.json has been downloaded (not only on the next screen).
* New installs must sign in at once; only people who already used ZH Play get the 1-day notice.

ZH PLAY 8.0 — Google sign-in · admin control from GitHub · TV sign-in by QR
===========================================================================
* SIGN IN WITH GOOGLE: anyone with a Google account (Android Credential Manager, "Sign in with Google").
  Phones: sign-in screen. TVs: a QR code — scan with the phone camera, open the link, ZH Play on the
  phone signs the TV in with the same account (or the paired phone remote offers "Sign in this TV",
  or sign in with Google on the TV itself).
* EXISTING USERS: 1 day of notice ("sign-in required from …" with Later), then required.
* ADMIN FROM GITHUB: admin/access.json in the repo (see admin/ADMIN_GUIDE.md) — anyone / invite-only,
  block people, switch features or tiles off (for everyone or one person), minimum build, a message to
  all apps, maintenance mode. E-mails are stored scrambled (sha256) so the public repo shows no address.
  Read every 15 min; a broken file is ignored. You are already the admin.
* SAFE START: nothing is locked until "web_client_id" (Google Cloud → Web client) is put in
  access.json — do the one-time setup in admin/ADMIN_GUIDE.md first.
* Account tile (phone, TV, remote boards): who is signed in, sign out, your scrambled e-mail;
  admins: scramble any e-mail, edit access.json on GitHub, reload now.
* A locked TV answers the phone only with status and sign-in until it is signed in.
* New libraries: androidx.credentials 1.3.0, googleid 1.1.1, zxing core 3.5.3. Nothing removed.

ZH PLAY 7.5.1 — Three APKs again (TVs with 32-bit Android can install)
=====================================================================
* Back to the build from before: ZHPlay-arm64-v8a-release.apk, ZHPlay-armeabi-v7a-release.apk and
  ZHPlay-universal-release.apk. The arm64-only APK of 7.5 could not install on TVs running 32-bit
  Android (common even with 64-bit chips). For the TV, install the universal (or armeabi-v7a) APK.
* The in-app updater picks the right one for each device, as before.
* Everything from 7.5 (Device mode Auto / TV / Phone, better TV detection, SD Card & USB row) stays.

ZH PLAY 7.5 — One universal APK · TV home always reachable
==========================================================
* BUILD: one APK for phones and TVs — ZHPlay-universal-arm64-release.apk (64-bit ARM only, no
  separate split files). The in-app updater finds it by "universal".
* TV HOME MISSING NAS / LOCAL FILES / LIBRARY: when Android doesn't report a device as a TV
  (many TV boxes, projectors and some Android TVs claim a touchscreen and no "leanback"), ZH Play
  showed the PHONE home there, which has none of those rows. Now:
  - detection also uses the TV flags of Android TV / Google TV / Fire TV,
  - "Device mode: Auto / TV / Phone" lets you force it (TV home list → ⚙ Device mode; and on a
    device without a SIM the phone home shows a "Use this device as the TV" tile),
  - the TV home list also gains "SD Card & USB".
* Nothing removed.

ZH PLAY 7.4 — Voice understands "play X in <app>"
=================================================
* Before: the phone mic offered only "Search on YouTube", so "play aura 10/10 song in YouTube" typed the
  whole sentence into YouTube's search; app names (Netflix, Spotify…) were ignored.
* Now the sentence is understood first (English / Tamil / Tanglish) and done at once:
  - "play aura 10/10 song in YouTube"      → plays the top match on YouTube (no search screen)
  - "play Leo songs on Spotify / YouTube Music" → starts playing there
  - "play Kanguva in Netflix / Prime / Hotstar / SonyLIV / ZEE5" → that app's search with the title
    typed (these apps don't let other apps start a title by name) — one tap to play
  - "open Hotstar", "Netflix open pannu", "open MX Player" → opens the app (any installed app by name)
  - "play Sun TV" (no app) → your ZH Play playlists first, else YouTube
  - questions and other phrases → the usual menu (AI, search, type…), now with the understood
    action as the first choice
* Works from the phone remote mic, the widget mic, the AI's no-key backup, and
  "Hey Google … ZH Play" (Assistant). New TV command: /voice. Nothing removed.
* Smart home (Google Home APIs) — later, after the Google Home Developer Console setup.

ZH PLAY 7.3 — Seedr in the remote · Exit (battery saver) · TV tiles back in reach
=================================================================================
* Phone remote board: new "Seedr" tile (cloud torrents; Play on the TV from there) and
  "Exit ZH Play" tile.
* EXIT ZH PLAY (phone home board and remote board): after asking, stops every ZH Play service
  (music, browser audio, screen casting, remote service, VPN, torrent downloads), removes all
  ZH Play notifications from the notification bar, closes every screen and ends the app. It
  stays closed (no restart at boot or by widgets) until you open ZH Play again.
* TV: Network folders (PC / NAS and phones sharing their videos), Local files (USB / TV
  storage), Library and My TV are now also tiles on the TV's "✨ All features" board (they were
  only rows in the TV's start list, which is unchanged). Network folders is on the phone board too.
* Nothing removed.

ZH PLAY 7.2 — Instant paste · 123 keypad · widget mic + app colours
===================================================================
* PASTE / TYPE ON THE TV IS INSTANT: text from the phone used to be typed on the TV one key press per
  character (a 600-character magnet took a long time). Now it is put into the text box in one step:
  ZH Play's own boxes directly at the cursor, other apps' boxes through the accessibility service
  (inserted at the cursor). The old key-by-key methods remain as fallbacks.
* 123 NUMBER PAD next to D-pad / Touchpad on the phone remote: 1-9, ⌫, 0, OK (PIN locks, channel
  numbers). Digits 0-9 now work on the TV (ZH Play screens; other apps with the ADB full remote).
  When the TV opens a number box (PIN / parental lock), the remote switches to 123 by itself and
  goes back to the previous pad when it closes (choosing 123 by hand keeps it).
* WIDGETS: 🎤 mic button on the remote widgets (medium / large / wide) and the player widget — opens
  the remote already listening: type on the TV, search YouTube / the TV, play in ZH Play, ask the AI.
* WIDGETS IN THE APP'S COLOURS: icons, title and mic follow the ZH Play theme and accent; from
  Android 12 the panel, keys and OK button too. They repaint when the theme changes.
* Nothing removed.

ZH PLAY 7.1.1 — 1080p plays smoothly like VLC
=============================================
* Cause: brightness above 100% (phone remote slider / TV ☀ menu go up to 150%, and the setting is
  saved) switched EVERY video to software decoding (VLC's picture filter needs CPU frames) and
  reloaded the video on each change. A TV box can decode 720p on the CPU but not 1080p / HEVC.
  VLC always decodes on the video chip — hence "VLC plays any clarity".
* Now the boost is done on the graphics chip: while brightness is above 100% the video draws into
  a TextureView with a colour matrix; at 100% or less it uses the normal SurfaceView (like VLC).
  Hardware decoding is never switched off by brightness, and changing it no longer reloads.
* Added VLC for Android's default --avcodec-skiploopfilter=1, so videos that do need software
  decoding (e.g. a codec the TV chip lacks) keep up like in VLC.
* If ⚙ Decoder is set to Software, the player says so once (that setting makes 1080p stutter).

ZH PLAY 7.1 — VLC-style streaming: resume anywhere, jump anywhere, save data
=============================================================================
RESUME
* Positions are remembered by what the video is, not the exact link. Seedr / download links
  carry tokens that change every time (…/Movie.mkv?st=…&e=…), so before, every new link
  started at 0:00. Now: same file name → same position (or the link minus token/expiry parts).
  Old saved positions still work. "Continue watching" keeps one entry per movie.

TORRENT / MAGNET STREAMING — "Save data" (on by default)
* Before: streaming a magnet downloaded the WHOLE movie in order, in the background. Jumping to
  1:00:00 had to compete with that download, and it used data for parts never watched.
* Now (like VLC): only ~32 MB ahead of where you're watching is downloaded (plus the file's first
  and last pieces, which players read first). Jumping releases the old spot at once, so the new
  spot gets all the speed. Nothing is fetched while you're not watching that part.
* "Keep this movie for later?" is only offered when the whole file is actually downloaded.
* Switch: Settings → 🧲 Torrent storage → 📶 Save data while streaming (On / Off = whole movie).

ONLINE LINKS
* The "continue watching" thumbnail no longer opens a stream a second time every 10 minutes
  during playback — only once when you leave.
* (7.0.1) one seek per timeline drag, no seek previews on streams, ±10 s taps combined, auto
  reconnect after a stream error.

ZH PLAY 7.0.1 — Streaming: seeking no longer stalls
===================================================
* Timeline: releasing it seeked TWICE (bug from the 5.9 player redesign) — each seek on a stream
  drops the buffer and reconnects, so back-to-back seeks stalled or failed. Now one seek.
* Seek preview thumbnails only for files on the device: on streams (Seedr, download links) they
  opened the video a second time and fought playback for bandwidth while seeking.
* ±10 s on streams: quick taps are gathered into ONE seek 0.45 s after the last tap
  (+10 +10 +10 = one jump of 30 s), with the target time shown at once. Local files unchanged.
* A stream error after it was playing (e.g. right after a seek) now reconnects at the spot you
  were going to (up to 4 tries) instead of "Cannot play".
* TV: OK on the timeline only seeks if the bar was actually moved.
* Torrent streaming engine unchanged (verified: recent changes there were tracker additions only).

ZH PLAY 7.0 — SD Card & USB file manager
========================================
* New "SD Card & USB" tile right after Seedr (phone board and TV Features; on a customised
  board new tiles now land next to the tile they follow, not at the end).
* Overview: phone storage, every SD card and USB drive (OTG / TV box) with free space bars;
  drives appear / disappear live when plugged in or removed. Quick access: Downloads,
  Movies, Music, Pictures, Camera, Documents.
* Browse with breadcrumbs; list or grid; thumbnails for pictures, video frames, album art;
  sort by name / date / size / type, ascending or descending; show hidden files; search.
* Multi-select (long-press): copy, move (cut), paste on any drive, delete, share, rename,
  properties (size counted in the background). Copy / move / delete run with a progress bar
  and Cancel; a cancelled copy leaves no half file; name clashes become "name (1).ext".
* Videos and songs play in ZH Play (the whole folder as a playlist); other files open with
  the right app ("Open with…" to choose). TV remote: ☰ / menu key opens a file's options.
* Asks for "All files access" with a clear card when it's missing.
* Seedr code unchanged.

ZH PLAY 6.9 — Send to Seedr adds directly (no website)
=====================================================
* Seedr moved adding to its REST API: POST https://www.seedr.cc/rest/torrent/magnet
  (field "magnet", Bearer sign-in token). The old oauth_test add_torrent now answers 404 for
  every link — that was the error all along. Confirmed by a current open-source Seedr client.
* ZH Play now adds there with the sign-in it already has: one tap on a magnet → added.
  No folder_id for the top folder (the REST route 404s on the old "-1").
* Not signed in? The Seedr screen opens, you sign in once, and the link is added right after.
* The seedr.cc website route stays only as a last resort if the REST call itself fails.
* Request verified on a local test server: path, Bearer header, form body, magnet intact.

ZH PLAY 6.8.1 — seedr.cc add: confirms or explains every time
=============================================================
* The seedr.cc add script now watches the site's own network calls and only says "Added" when
  seedr.cc really sent its add request (POST …/task) and answered OK.
* If the + click doesn't trigger the site it tries, in order: + / add controls → Enter key →
  form submit → nearby buttons, stopping as soon as the site sends its request.
* If seedr.cc refuses (free plan: 1 download at a time / no space) its exact answer is shown.
* If nothing triggers the site, a report shows the page, the box found and every step tried.
* Tested on mock pages (icon +, Enter-only, form-only, no reaction, signed out); the script
  embedded in the app is byte-identical to the tested one.

ZH PLAY 6.8 — Send to Seedr adds through seedr.cc itself
========================================================
* Found with DevTools: seedr.cc adds torrents with POST /api/v0.1/task (+ CSRF, website login).
  Seedr's app APIs can no longer add: the old add_torrent answers 404 for every link, and the
  token API (/api/v0.1/p/…) has no task or file routes (404 before auth).
* So when the app API refuses, ZH Play adds on seedr.cc inside ZH Browser: it fills the site's
  own "Paste link URL Here" box and presses its + button — the same request the site makes,
  with your website login (sign in to seedr.cc once in ZH Browser). The link is also copied.
* Works from: browser magnet tap → Send to Seedr, and the Seedr screen's ＋ (back in the
  Seedr screen it jumps to Transfers). Not signed in to the app? The website route still works.
* The old fallback never found the box (it looked for "magnet"/"torrent"; the box says
  "Paste link URL Here"). Tested on mock pages: icon "+", text "+", late-loading page,
  signed-out page; never touches search, Create Folder or clear.

ZH PLAY 6.7.1 — Seedr accepts hybrid (v1+v2) magnets
====================================================
* Magnets with two hashes (xt=urn:btih + xt=urn:btmh, BitTorrent v2 hybrids — common on 1TamilMV)
  were refused by Seedr's app API (404) though seedr.cc accepts them. ZH Play now retries with
  simpler forms of the same torrent: without the v2 hash → hash + name + 5 trackers → hash + name
  → bare hash. Stops early on definite answers (queue full, no space, already added).

ZH PLAY 6.7 — Browser tabs restored · Seedr rebuilt on Seedr's real protocol
============================================================================
BROWSER
* Fixed: after closing and reopening, all tabs were black and nothing was restored. The start-up
  code that restores the last tabs (or loads the home page) had been deleted by the 5.8-era
  "open links from other apps" change. Restored; a link opened from another app now opens as an
  extra tab beside your restored tabs.
* Tabs that haven't loaded yet can no longer overwrite (wipe) the saved tabs.
* Music → Links (YouTube listen): steps back only once the audio really plays.
* Magnet → Send to Seedr: shows Seedr's real reason if it fails; if you're not signed in, Seedr
  opens and adds the link right after you sign in. No more surprise extra seedr.cc tabs.

SEEDR (checked line by line against the reference client seedrcc 2.1.2)
* Play on this phone works (it now opens the player the same way the rest of the app does).
* Play on the TV with PIN / Wi-Fi error messages; Download to this phone (Downloads folder).
* A finished single-video torrent opens straight to its video.
* Username, e-mail and storage bar now show (they are under "account" in Seedr's reply).
* Rename uses Seedr's "rename" function (the old names didn't exist).
* "Sign in with a code" now really finishes (GET api/device/authorize, client seedr_xbmc).
* Sign-in stays alive: token renewal uses oauth_test/token.php (was a wrong URL).
* Adding explains Seedr's answer: queue full (free plan, put on wishlist), not enough space,
  unreadable magnet, already added. .torrent http links are downloaded and uploaded properly.
* Transfers tab refreshes every 5 s while open.

PAIRING CODE
* The TV home shows only the pair code (no IP). The code no longer changes silently after 30 min
  while the screen still showed the old one; it now changes each time the TV app restarts.

BUILD
* Every file now compile-checked at Java 8 against Android 34 + the real library sources before
  release (0 errors).

ZH PLAY 6.0 — Full Seedr integration: files, transfers, search, delete, rename, stream
======================================================================================
* Complete Seedr file manager (same layout as the Seedr app):
  - Files tab: browse folders, tap to stream/play, ⋮ menu for rename/delete
  - Transfers tab: live progress bar, speed, seeds/peers, delete active downloads
  - Search tab: search across your whole account
  - Header: username + storage quota bar
* Token setup: tries PAT directly as OAuth token first (many PATs work this way),
  then falls back to OAuth exchange. Shows clear error if scope is wrong.
* Magnet → Seedr: tap any magnet link in the browser → "Send to Seedr ✓" →
  API call with real error reporting + fallback to browser injection on failure
* Accessible from: Phone home board · Features screen · Browser menu · Magnet long-press
* Fixed: files not showing (list_contents field names corrected), token refresh auto-retry

ZH PLAY 5.9.7 — Seedr API: correct base URL from official docs
=============================================================
* Base URL fixed: https://www.seedr.cc/api/v0.1/p (from the official Seedr API docs)
  Previously using /api/v0.1/ without the /p — caused "access denied".
* User endpoint: /p/user (not /account).
* Both PAT and OAuth tokens supported automatically.

ZH PLAY 5.9.5 — Seedr API integration: cloud torrent manager inside ZH Play
==========================================================================
* Full Seedr file manager: browse your Seedr cloud, add magnets via API,
  stream/play files directly, live download progress, delete/rename.
* One-time setup: paste your Personal Access Token from
  seedr.cc/api/v0.1/console/tokens (choose "Read & Add Torrents" scope).
* Access via: Features → Seedr, or browser menu → Open Seedr, or
  long-press a magnet → Send to Seedr (✓ = API connected, instant add).
* Old browser-injection method kept as fallback when no token is saved.

ZH PLAY 5.9.4 — Trackers: auto-update from live community lists + latency ranking
==================================================================================
* TrackerList: fetches 3 community-maintained tracker sources in parallel on first run
  and every 24 hours in the background (ngosang/trackerslist best_ip + best, XIU2 best).
* Each tracker is probed with a real UDP/TCP ping (1.5 s timeout): only reachable trackers
  are used. They are ranked fastest-first and the top 50 are saved.
* Every magnet link now gets the full ranked list appended before playing — up to 50
  fresh high-uptime trackers vs the old hardcoded 14.
* If the network is offline, the last good ranked list is used; if there is no cached list
  yet, the hardcoded fallback is used — torrents always have trackers.
* Tracker list warms up at app start so it is ready before you click any magnet.

ZH PLAY 5.9.3 — Seedr magnet menu always shows; 14 extra trackers for faster torrents
=====================================================================================
* Long-press on a magnet link always shows the "Send to Seedr / Play / Copy" menu.
  Before, WebView returned UNKNOWN_TYPE for magnet: links (non-http scheme) on many torrent sites
  so the menu never appeared. Now a JS fallback reads the link from the tapped element.
* Send to Seedr now actually injects the magnet: waits 2 s for Seedr's React app to mount
  before running the injection (before, the injection ran before the page was ready).
* 14 extra public trackers (UDP + HTTPS) are added to every magnet link before playing —
  the engine announces to all of them at start and finds far more peers than with just the
  embedded ones. This is the main way to speed up torrents on a phone.

ZH PLAY 5.8 — Music Links: YouTube audio-only from browser now works
=====================================================================
* Fixed: tapping a YouTube link in Music → Links (or "Add to Music" from the browser) didn't
  play audio — because the browser was creating a new tab and applying AUDIO_JS to the old tabs
  (the new tab was never in audio mode when it loaded YouTube).
* Fix: EXTRA_LISTEN=true now loads the URL in the CURRENT tab (no new tab). b_audio is flagged
  and AUDIO_JS is applied in onPageFinished (the right moment — after YouTube's player is in the DOM).
  The browser then steps back automatically after 1.5 s so you land back in the Music screen.
* Other tabs are no longer accidentally switched to audio mode.

ZH PLAY 5.7.9 — Browser: text selection search, Seedr for magnets, open-as-browser
=================================================================================
* Text selection → "Search in new tab": select any text on a page → floating toolbar →
  🔍 Search in new tab → opens a search in ZH Browser (not external search app).
* Long-press magnet / torrent link → "Send to Seedr": opens seedr.cc (reuses an existing
  Seedr tab if one is open), pastes the magnet and clicks Add — stream or download in the cloud.
* handleOpenIntent: ZH Browser now handles ACTION_VIEW (http/https links from other apps),
  ACTION_WEB_SEARCH (voice / system search), PROCESS_TEXT (selected text from other apps).
  Other apps can now share/open links directly in ZH Browser.

ZH PLAY 5.7.8 — PiP simplified: no more video displacement
==========================================================
* Removed all DOM manipulation from PiP: no more CSS transform, no element moves, no fill/unfill.
  YouTube's video was shifting to the right because the JS getBoundingClientRect() conversion to
  screen coordinates was wrong (CSS px × density double-counted the WebView scaling).
* enterPipMode now just uses the customView (when YouTube is in its own fullscreen) or webBox
  (normal view) as the source rect. Android's compositor handles the rest correctly.
* All dead PIP_FILL_JS / PIP_UNFILL_JS code removed.

ZH PLAY 5.7.6 — PiP works like Brave: no DOM changes, JS source-rect hint
==========================================================================
* PiP in address bar button: appears (like Brave) whenever a video is playing.
* enterPipMode now works exactly like Brave: JS asks the playing video for its
  getBoundingClientRect(), converts to screen coordinates, and passes it to
  Android via setSourceRectHint + setAspectRatio. Android's compositor grabs
  that surface directly — no DOM changes, no CSS fighting YouTube's containers.
* When YouTube is in its own fullscreen (onShowCustomView), the customView surface
  is used directly — perfect quality, instant transition.
* All PIP_FILL_JS / PIP_UNFILL_JS DOM manipulation removed from the PiP path:
  it is no longer needed and was causing the black-video and displaced-video bugs.

ZH PLAY 5.7.5 — YouTube fullscreen fills screen; pinch-to-zoom works; PiP→fullscreen fixed
=========================================================================================
* YouTube fullscreen black bars: a CSS rule is injected that removes YouTube's max-height
  constraint only while fullscreen is active — the video now fills the full screen.
* Pinch-to-zoom: the ScaleGestureDetector is now actually attached to webBox (it was lost
  in a merge). CSS zoom is applied at the document root, which works inside YouTube's nested
  containers. Two-finger pinch anywhere in the browser zooms; double-tap resets.
* PiP → fullscreen: the player container is scaled with transform:scale() instead of moving
  the <video> element to <body> (which broke the controls and left it stranded in the middle
  of the screen when returning from PiP).

ZH PLAY 5.7.4 — YouTube PiP: video visible, pause works; pinch-to-zoom in browser
=============================================================================
* PiP YouTube video visible: the <video> element is moved directly to <body> (bypassing YouTube's
  clipping containers: overflow:hidden, clip-path, transform wrappers) as position:fixed at full
  screen. Restores exactly to its original parent on exit. Tested in a simulated YouTube DOM.
* Pause works after PiP: the 900 ms keep-alive guard now clears correctly when PiP ends, so
  manually pausing after returning works normally.
* Video fit modes replaced with pinch-to-zoom: a ScaleGestureDetector on the WebView container
  handles two-finger pinch anywhere in the browser (normal view, YouTube, all sites). The ⤢
  button in fullscreen resets zoom when zoomed (or shows a "Pinch to zoom" hint). CSS object-fit
  was removed — it fought with YouTube's CSS hierarchy and caused the black-video bug.

ZH PLAY 5.7.3 — YouTube keeps playing in PiP; video fit modes in fullscreen
=========================================================================
* YouTube PiP: blocked YouTube's internal "paused-mode" state machine (not just pause()). A
  MutationObserver catches the "paused-mode" class the moment YouTube applies it and reverses it;
  a 900 ms interval calls playVideo() and reapplies the visibility spoof; pauseVideo/stopVideo are
  overridden so the scheduler cannot switch state — the video stays playing.
  Tested: 6 scheduler attempts, 0 succeeded. After closing PiP everything is restored.
* Video fit in the browser fullscreen: a ⤢ button cycles 4 modes (same as the native player):
  Fit (contain), Fill/Stretch, Crop and Zoom 1.2×. Works on YouTube and any other site.

ZH PLAY 5.7.2 — TV: the remote's cursor is visible again in menus
================================================================
* Since 4.7 (the card-style menus) the highlight of the line under the remote's cursor was transparent,
  so on the TV you couldn't see which option you were on (e.g. Settings / player quality 480p ↔ 1080p).
  Now every list menu shows the line under the cursor as a tinted card with an accent outline, and a
  choice list opens on the option that is currently chosen. The TV's video / music grid too.

ZH PLAY 5.7.1 — new logo everywhere
==================================
* The new logo now also in: the logo badges (opening animation, headers, TV home — black badge instead
  of white), the home-screen widgets, the phone's web remote page and its tab icon, the browser's start page.

ZH PLAY 5.7 — new logo, Music links & YouTube listening, PiP fixed, VPN fixes
==========================================================================
(built on your Gemini changes: picture-in-picture, VPN button, IP rotation — kept)
* New logo and TV banner (தீ · ZH PLAY · more than remote): app icons, adaptive icon (fits every
  launcher shape), in-app logo, TV banner, the Linux app.
* Music → Links (new tab): add a song / radio address, an .m3u / .m3u8 playlist (from a link or a file)
  or a YouTube video. Links play in the music player; YouTube plays as AUDIO in the background
  (the browser in audio-only mode steps back by itself; the notification has play / pause + timeline).
  Hold a link: Play on TV · Rename · Remove. Browser: menu → "Add to Music", or long-press a media link.
* Music player: 🌙 sleep timer button on the player screen (it was hidden in the ⋮ menu).
* Browser, YouTube: the Videos button shows "YT" on video pages and offers Listen (audio only), Add to Music,
  Play on TV (exactly that video in the YouTube app), Picture in Picture. (No YouTube download: protected
  streams, against YouTube's terms — other sites' videos can be downloaded and converted as before.)
* Picture in Picture: works for YouTube (the video fills the window, 16:9; the page comes back afterwards);
  automatic PiP only for VIDEOS (not songs / audio mode) and can be switched off (menu → Automatic PiP).
* VPN: choosing a profile connects exactly that one; rotation tries each profile once and stops with a
  message instead of retrying forever; at least 1 minute between switches; no pop-up at every switch; the
  help explains that each switch reconnects briefly (use Android's Always-on + Block connections for none).
* Autoplay follows Site settings again (a global line overrode it).

ZH PLAY 5.6 — browser music in the notification like a music player
==================================================================
* YouTube (or any page) playing in the ZH Play browser now shows a full media notification:
  song title, artist and cover (as YouTube publishes them), play / pause, −10 s / +10 s, close,
  and a timeline that moves — drag it to jump. Also on the lock screen and in Android's media controls.
* Fixed: the notification stopped updating (or vanished) after leaving the app — every update
  re-started the service, which Android refuses from the background; updates now go straight to it.
* If notifications are off for ZH Play (Android 13+), the browser asks again the first time
  something plays, or offers to open the notification settings.

ZH PLAY 5.5 — Live TV screen without the "Start now?" question
=============================================================
* With the ADB full remote on, the live picture now comes straight through ADB like before — no
  question on the TV — but as smooth live video: Android's own "screenrecord" streams H.264 on a
  separate ADB connection (the remote's keys never wait for it), cut into frames for the phone.
  screenrecord's 3-minute limit is handled by starting it again at once.
* Without ADB (or if screenrecord can't stream on the TV), Android's screen capture is used, which asks
  "Start now?" on the TV.

ZH PLAY 5.4 — Live TV screen: smooth live video
=============================================
* Live TV screen is now real live video: the TV records its own screen with the hardware video
  encoder (like Cast screen, the other way round) and streams it to the phone — smooth (up to
  30 frames a second), low delay. Before, it was a full PNG screenshot through ADB each time,
  which can only manage about one picture a second.
* No ADB needed for it. Android asks once on the TV for screen capture ("Start now?"); with the
  ADB full remote on, ZH Play allows itself permanently (appops PROJECT_MEDIA) and nothing is asked.
  Or once from a PC: adb shell appops set com.tamil.castplay PROJECT_MEDIA allow
* The TV stops capturing as soon as the Live TV screen page is closed.
* Fallback: an older TV app or the Linux PC keeps the picture-about-once-a-second view (it says so).

ZH PLAY 5.3 — DJ: jog wheels (scratch), easier multi-song adding, files & folders
==============================================================================
* Jog wheel on both decks (like a DJ controller): the platter turns with the music (33⅓ rpm);
  touch the centre to scratch — the sound follows your finger, forwards and backwards, a resting
  finger holds the record; turn the outer ring to nudge the tempo (to line up beats). Smooth (no clicks).
* Adding songs: a full-screen picker — tick several (search, Select all, Clear) and the
  "Add N songs to deck A/B" button is always on screen (before, in landscape it fell off the bottom).
* Files… (several audio files from anywhere) and Folder… (all songs in a folder and its sub-folders)
  from the phone's storage — they still play next time.

ZH PLAY 5.2 — DJ mixer: song lists on both decks, Auto DJ
========================================================
* Each deck has its own song list (kept for next time): "＋ Add songs" picks several at once
  (search, select all shown). The list scrolls under the deck; ▶ marks the song on the deck;
  tap a song = load it; hold = play next, move up / down, move to the other deck, remove.
* Auto DJ (mixer, next to Auto-fade): plays the lists one song after another, A ⇄ B. About 30 s
  before the end it prepares the other deck's next song; at the end it brings it in on its first
  beat, in tempo (when both BPMs are known), with the 8-bar crossfade. If one side's list is
  empty, the next song comes from the other list. Switching it on with nothing playing starts deck A.

ZH PLAY 5.1 — one app, every option on the TV too; roll back to earlier versions
================================================================================
* "All features" on the TV home (and in the phone's player home): browser, videos, music, features,
  DJ mixer, song editor, whiteboard, VPN, equalizer, updates, settings — customizable like the boards
  (the TV keeps its own arrangement). The phone's start screen uses the same tiles.
* Updates (All features → Updates, or Settings): Check for updates · Earlier versions (roll back) ·
  Automatic updates On / Paused. Rolling back pauses automatic updates so it doesn't jump forward.
* How rolling back is possible: the self-installed app now has one fixed Android version code
  (2000000 — higher than all earlier builds, so they update to it normally); Android accepts an
  older build with the same code. Which build is newer comes from the build number in the version
  name. Rollback works to builds made from 5.1 on (older builds still have the old numbering).
  The Google Play build keeps increasing version codes (Play requires it).

ZH PLAY 5.0.1 — PDF slides / phone videos on the PC (and TV) fixed
=================================================================
* The phone told the PC / TV the wrong address of itself when mobile data or the VPN was on (a
  10.x address the PC can't reach), so PDF slides and phone videos didn't open — the whiteboard,
  which downloads nothing, worked. Now the phone uses the address that actually reaches the TV / PC.
* VPN: the home network stays direct (like WireGuard's "Exclude private IPs"), so the phone still
  reaches the TV / PC with the VPN on; the VPN's DNS still goes through the VPN.
* Present: if the TV / PC can't download the PDF, the phone says so (instead of nothing happening).

ZH PLAY 5.0 — whiteboard: full screen, circle menu, saved boards, PDF / Word export
==================================================================================
* The board / slide fills the screen. Tools are in a floating round button (drag it anywhere):
  tap it and the tools open in a circle — outer ring: Laser, Pen, Highlighter, Eraser, Shapes,
  Ruler, Text, Spotlight, Move, Undo, Redo, Clear; inner ring: 6 colours + size. The button shows
  the current tool in the current colour. The circle always fits the screen.
* Hidden side panel (tap ‹ at the right edge; hides itself after 6 s): pages with pictures,
  + / delete page, paper, all slides / go to, zoom, blank screen, rotation, save, open, share.
* ‹ 1 / 3 › at the bottom left changes slides / pages without opening anything.
* Saved boards: kept automatically while you draw (and when you leave); Open saved board shows
  title, date, pages, drawings — it opens on the TV too; hold to rename or delete; New board.
* Share / export: PDF, Word (.docx, one page per sheet, A4 landscape) or pictures (PNG) — share to any
  app or save in Downloads/ZH Play. Also for PDF slides with your drawings.

ZH PLAY 4.9 — browser: audio-only mode, sleep timer, fully dark
==============================================================
* Audio only (menu → Listening): videos on any page are hidden behind an "Audio mode" card while
  the sound keeps playing; YouTube switches to its lowest quality (saves data and battery).
  With Background play the screen can be off.
* Sleep timer (menu → Listening): 10 / 15 / 30 / 45 / 60 / 90 / 120 min or custom — the sound fades
  out over the last 10 s, playback stops and the browser closes. The menu shows the minutes left.
* Dark mode now also darkens the browser's own bars (address bar, toolbar, menus) and the phone's
  status and navigation bars; changing it re-draws the browser at once, keeping the tabs.

ZH PLAY 4.8 — phone board, browser dark mode, whiteboard & drawing tools, TV groups
===================================================================================
* Start screen ("This phone"): everything that works without a TV — Browser, Videos, Music,
  Features, DJ mixer, Song editor, Whiteboard, VPN, Equalizer, Settings, TV group … — with
  ✎ Customize (drag order, hide, size, sections), saved separately from the remote's board.
* Browser: dark mode for websites — Follow the phone / On / Off (menu → Customize).
* Whiteboard (no PDF needed): papers White, Chalkboard, Grid, Lined, Dotted, Graph; pages
  (add / delete; Next on the last page adds one); on the TV / PC / TV group, or on the phone alone.
* Drawing tools for whiteboard AND slides: Pen, Highlighter, Eraser, Shapes (line, arrow,
  rectangle, circle, triangle), Ruler (cm ticks, length, angle), Text with a math-symbol keypad
  and ready formulas; 6 colours, 3 sizes, undo / redo, clear page. Each page keeps its drawing.
* TV group: pair several TVs, choose them, "Control together" — play, keys, volume, present,
  search … go to every TV of the group; Cast screen streams to all of them (a slow TV skips
  to the next full picture instead of holding the others back).

ZH PLAY 4.7 — live zoom on the TV, address suggestions, new menu style
=====================================================================
* Present: the TV follows the pinch live (scales the picture at once, redraws it sharp when the
  fingers pause). Before, every in-between drawing was thrown away while the fingers moved, so the
  TV only changed after the pinch. The phone warns if the TV's ZH Play is too old for zoom.
* Browser: suggestions while typing in the address bar — history (sites you visit often first),
  bookmarks ★, open the typed address, search; inline completion ("you" → youtube.com, Enter takes
  it); ↖ puts a suggestion into the bar to edit.
* Menus in the settings style (like Brave): each line a rounded card with the title, the current
  value in grey underneath, switches for On / Off, red for delete / reset / clear, section headings
  (Settings: General · Picture & sound · Subtitles · Playback · Remote & permissions · Storage &
  updates · More). Browser menu: quick buttons + This page · Privacy & security · Customize.

ZH PLAY 4.6 — TV remote widget for the home screen
=================================================
* New widget "ZH Play · TV remote": control the TV without opening the app. Resizable —
  the layout follows the size: small = D-pad + OK · medium = + Back / Home / Menu + volume ·
  wide = D-pad beside the buttons · large = everything (+ ⟲10 / play-pause / 10⟳ / next, power).
  Tap the title / logo to open ZH Play. It shows (and follows) the TV you picked last.
* The old widget is now called "ZH Play · now playing" (title, ±10 s, play / pause, volume, power).

ZH PLAY 4.5 — YouTube ads, VPN profiles, board fix, complete Present mode
=======================================================================
* YouTube in the browser: ads are now removed before they are planned (the video's ad schedule
  is taken out — the uBlock Origin / Brave method), with skip / fast-forward as backup, and
  YouTube's "ad blockers are not allowed" pop-up is closed. Injected as early as possible.
* VPN: now covers the WHOLE PHONE by default (it covered only ZH Play before — that is why other
  apps still showed your own IP). Several profiles: import many .conf files at once, switch any
  time (reconnects), rename / delete. "Check my IP" shows what the internet sees.
* Board Customize: no more crash — drag is now a visual slide (the list is rebuilt after the
  touch); any problem shows a message instead of closing the app.
* Present mode, complete: pinch to zoom (the TV / PC redraws the zoomed part sharp — tiny text
  readable), two-finger pan, double-tap = whole slide, + / − / Fit; tools Laser · Pen (5 colours,
  clear) · Spotlight · Move; black / white screen; slide overview grid; go to slide №; timer
  (tap pause, hold reset); volume keys; landscape layout with rotation lock.

ZH PLAY 4.4 — Release C (with A + B): appearance, site settings, VPN, board customization
=======================================================================================
Browser appearance (menu → Appearance, or "✎ Customize" on the new-tab page)
* Background: 8 animated gradients or your own photo; darken, blur, glass / solid cards,
  animations, accent colour, clock & greeting, shortcuts / recent / blocked-ads on or off,
  search engine (Google, DuckDuckGo, Bing, Brave Search, Startpage).
Site settings (tap the lock in the address bar, or menu → Site settings)
* Per site: location, camera, microphone, notifications, motion sensors, file uploads, pop-ups,
  autoplay sound, JavaScript, cookies, ad blocker — Ask first / Allow / Block, remembered.
* New: websites can use the camera / microphone / location when you allow them; "choose file"
  upload buttons work; pop-ups are blocked (allowed sites open them in a new tab).
* Clear one site's cookies & storage; reset its permissions; list of all sites with own settings.
VPN (browser menu → VPN, or the VPN tile)
* WireGuard (official library). Import a profile (.conf file or paste) — e.g. Proton VPN's free
  plan (steps inside). Use it for only ZH Play or the whole phone. Status: server, data used.
Board customization (remote → BOARD → ✎ Customize)
* Drag tiles (≡) to any order, ↑ / ↓, hide / show, your own named sections, tile size small /
  medium / large / list, reset. New tiles of later versions appear at the end.

ZH PLAY 4.3 — Release B: all formats, DJ landscape, new song editor
==================================================================
* Video → Audio (and every audio tool: cutter, splitter, merger, karaoke, editor, DJ) reads ALL
  formats: when Android's own decoders can't (some AVI / WMV / FLV / RM codecs, AC3 / DTS sound …)
  the VLC engine inside the app reads the sound instead. Output stays M4A.
* DJ mixer: landscape layout Deck A | Mixer | Deck B; turning the phone keeps the music playing.
  Rotation button: Auto-rotate → Portrait → Landscape (remembered).
* Song editor redesign: bigger timeline, compact transport + zoom in one row, Select / Move / Scroll,
  tool tabs Edit · Effects · Tracks as an icon grid, slim import progress line, landscape layout
  (timeline left, tools right), rotation button; turning the phone keeps the project.
* Editor effects on the selected part (undo restores it): Echo, Reverb (small / medium / large),
  Bass boost, Treble boost, Low cut (rumble), High cut (hiss), Noise gate, Speed (0.5–2×, tape style),
  Remove vocals, Mono — plus Fade in / out, Amplify, Normalize, Reverse.

ZH PLAY 4.2 — Release A: browser downloads, torrent file picker, fixes
=====================================================================
Browser
* Downloads: any file a page offers (long-press a link → Download, or a page's download button),
  incl. files a page makes itself (blob: / data:). Saved in Downloads/ZH Play with the site's
  login cookies. Menu → Downloads: progress, open, delete, retry. Long-press images → Download.
* Torrents with several files: "Choose files" lists the videos / songs → play here, send to TV,
  add to TV queue, add to TV playlist, or save — one item per file ("magnet…#zhfile=N").
* "Add to TV playlist" — before, the phone browser saved into the phone's own list, so links never
  showed up on the TV. Also: Open in a new tab, Copy link.
* YouTube: "Skipping ad…" overlay with a spinner while an ad is being skipped.
AI assistant
* Voice in the AI panel always asks where the words go again (Ask the AI / YouTube / search the TV /
  ZH Play / type on the TV). An open "type on TV" box hidden behind the panel took them before.

ZH PLAY 4.1 — Google Play variant (AAB) + your normal APKs unchanged
===================================================================
* Two builds from the same code, every feature in both:
  full = the APKs you install (Android 14 target, GitHub auto-update) — exactly as before
  play = ZHPlay-play-<n>.aab for Google Play (Android 15 target, Play updates it)
* Build tools updated (Android Gradle Plugin 8.6.1, Gradle 8.10.2, SDK 35).
* See PLAY_STORE.md for the Play Console steps and declarations.

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
