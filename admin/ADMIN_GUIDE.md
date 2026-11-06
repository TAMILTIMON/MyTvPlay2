# ZH Play — admin guide (Google sign-in + control from GitHub)

Every ZH Play reads **`admin/access.json`** from this repo (refreshed every 15 minutes, and at once from
Account → *Reload admin settings*). Edit it on github.com — even from your phone — and commit.
A typo is safe: invalid JSON is ignored and apps keep the last good copy.

## One-time Google Cloud setup (free, ~15 min)

1. Open **https://console.cloud.google.com** → top bar → **New project** → name it `ZH Play` → Create.
2. **Google Auth Platform → Branding** (older menus: *APIs & Services → OAuth consent screen*):
   app name `ZH Play`, your e-mail as support + developer contact → Save.
3. **Audience**: User type **External** → **Publish app** (status *In production*).
   (In *Testing* only listed test users can sign in.) No extra scopes are needed.
4. **Clients → Create client → Android**:
   - Package name: `com.tamil.castplay`
   - SHA-1: the fingerprint of the key GitHub signs with. On your PC, in the folder with `tamilcastplay.jks`:
     ```
     keytool -list -v -keystore tamilcastplay.jks -alias tamilcastplay
     ```
     (it asks for the store password) → copy the line `SHA1: AA:BB:…`.
   → Create. (Nothing to copy from this one — Google just needs it to exist.)
5. **Clients → Create client → Web application** → name `ZH Play web` → Create →
   copy the **Client ID** (`…….apps.googleusercontent.com`).
6. Put that Client ID in `access.json`:
   ```json
   "web_client_id": "1234567890-abc….apps.googleusercontent.com",
   ```
   Commit. From now on sign-in is required — after **1 day** for people already using the app
   (or set `"login_from": "2026-11-10"` for a fixed date).

Until `web_client_id` is set, nobody is asked to sign in and nobody can be locked out.

## The fields

| field | meaning |
|---|---|
| `mode` | `"anyone"` = every Google account · `"invite_only"` = only `allowed` + `admins` |
| `admins` / `allowed` / `blocked` | scrambled e-mails (see below) |
| `features_off` | switch off for everyone, e.g. `["torrents","vpn","seedr","dj_mixer","storage"]` (tile names) |
| `user_features_off` | for one person: `{"<scrambled e-mail>": ["vpn"]}` |
| `min_build` | builds below this number must update first (the number after the last dot of the version, e.g. 7.5.1.**215**) |
| `message` | a note shown once in every app (change the text to show a new one) |
| `maintenance` | `true` = the app shows "Back soon" to everyone except admins; text in `maintenance_message` |
| `login_from` | `"YYYY-MM-DD"` — when sign-in becomes required (default: 1 day after the update first runs) |

Hard switches: `torrents` (all magnet / torrent playing), `vpn`, `seedr`. Any tile name hides that tile.

## Scrambled e-mails

Addresses are never stored readable in this public repo: the app uses
`sha256("zhplay:" + email in lower case)`. Get them in the app: **Account tile → 🔑 My scrambled e-mail**,
or as admin **🛡 Scramble an e-mail** (type anyone's address) → Copy → paste into the list with quotes.

You are already admin: `tamilarasan5333@gmail.com` → `e4940009d4dd03532e683fa3eb0c803e8b563715fff668323f2ddb5572bdcf4e`.

## TV sign-in

The TV shows a QR code. Scan it with the phone camera → open the link → ZH Play on the phone (signed in)
signs the TV in with the same account. A phone already paired with the TV's remote PIN also gets a
"Sign in this TV" prompt. A TV can also sign in with Google itself if it has a Google account.

Note: this protects against normal users, not against someone who modifies the APK.
