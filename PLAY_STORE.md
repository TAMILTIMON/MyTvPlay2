# Publishing ZH Play on Google Play

Every build now makes two variants from the same code — all features are in both:

| | **full** (APKs) | **play** (AAB) |
|---|---|---|
| For | installing yourself (TV, phone) | uploading to Google Play |
| File | `ZHPlay-*-release.apk` (Releases / Actions "ZHPlay-apk") | `ZHPlay-play-<n>.aab` (Releases / Actions "ZHPlay-aab") |
| Targets | Android 14 (API 34), as before | Android 15 (API 35), as Play requires |
| Updates | auto-update from GitHub | Google Play updates it ("Check for updates" opens the Play page) |
| Layout on Android 15 | unchanged | unchanged (opts out of the forced edge-to-edge layout) |

## First upload
1. Play Console → Create app → package name **com.tamil.castplay** (fixed forever).
2. Release → Testing → **Internal testing** → Create release → upload `ZHPlay-play-<n>.aab`.
3. **Play App Signing**: keep "Google manages the app signing key". Your GitHub key becomes the
   **upload key** (Play Console shows it; nothing to change in GitHub).
4. Fill in the app content pages (below), then roll out to internal testers → closed → production.
   (New personal developer accounts must run a closed test with testers before production — Play Console shows the current rule.)

## Questions Play Console will ask about this app
- **Accessibility service** (TV: "control other apps from the phone"): Permissions declaration form —
  explain it lets the phone remote press keys / type in other apps on the TV, only when the user turns it on.
  Play reviews this strictly; a short video of the feature helps.
- **All files access** (MANAGE_EXTERNAL_STORAGE): declaration — playing videos from USB drives / any folder on the TV.
- **Foreground services**: mediaPlayback (music, browser background play), mediaProjection (Cast screen),
  specialUse (phone remote connection) — describe each and attach a short video.
- **Microphone** (RECORD_AUDIO): only for the equalizer's live spectrum and the phone's sound in Cast screen; nothing is recorded.
- **Display over other apps**, **notification access**: explain they are for the TV remote features.
- **Data safety**: the app talks to your own TV / PC on the local network; AI keys and subtitles keys stay on the device;
  optional internet: IPTV lists, subtitles search, AI provider you chose, YouTube search.
- **Privacy policy URL** (required): a simple page stating the above (a GitHub page works).
- **Content rating**, target audience, ads (none).

- **VPN (VpnService)**: Play requires the VPN declaration form — the app's VPN uses the user's own
  WireGuard profile (no ZH Play servers); describe that, it is not the app's main purpose.
- **Camera / location**: only for websites the user allows in Site settings (browser).

## Things Play may object to (features are kept — decide before publishing)
- **YouTube ad skipping in the browser** — YouTube's terms forbid it; Play can reject or remove apps that block
  another service's ads. You may want it switched off by default in the Play build.
- **Torrent streaming** — allowed on Play in general, but piracy-looking listings, screenshots or default sources get rejected.
- **Accessibility service** — the most common rejection reason for remote-control apps.
