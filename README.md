# Party Chat

A personal Android app that wraps Xbox's web party chat and fixes the two things
the official app and mobile browsers get wrong:

1. **Mic keeps working in the background.** A "microphone" foreground service (with a
   notification, like Discord during calls) lets your voice keep sending with the app
   minimized or the screen off.
2. **Uses your headset.** Voice is routed to Bluetooth > wired > USB headset, falling back
   to the phone speaker (or earpiece; toggle in the menu). It re-checks every few seconds
   and when you connect/disconnect a headset.

## Build it — option A: on your phone, no PC (GitHub)
1. Make a free GitHub account, create a new **private** repository
   (tick "Add a README" so it isn't empty).
2. In the repo: **Add file → Create new file**, name it `.github/workflows/build.yml`,
   paste in the contents of that file from this project, and commit.
3. **Add file → Upload files** → upload `PartyChat.zip` (this whole project, zipped) → commit.
4. Open the repo's **Actions** tab. "Build APK" runs automatically (~3–5 min); if the first
   run (from step 2) failed, ignore it — the run after uploading the zip is the one that counts.
5. Open the repo's **Releases** → "Party Chat (latest build)" → tap **PartyChat.apk**.
6. Install it (allow "Install unknown apps" for your browser when asked).

## Build it — option B: Android Studio on your PC
1. Open this folder in Android Studio (File → Open), let Gradle sync.
2. Build → Build App Bundle(s) / APK(s) → Build APK(s).
3. APK is at `app/build/outputs/apk/debug/app-debug.apk`. Copy it to your phone and install,
   or plug your phone in with USB debugging on and press Run.

## First run
1. Allow **Microphone**, **Nearby devices** (needed for Bluetooth headsets) and **Notifications**.
2. Sign in to your Microsoft account and open party chat the same way you do in your browser.
3. Menu (⋮) → **Set this page as home** so the app opens straight there next time.
4. Menu → **Battery optimization settings** → set Party Chat to "Unrestricted"/"Don't optimize"
   (Samsung, Xiaomi, OnePlus etc. kill background apps aggressively otherwise).

## Menu
- **Desktop site** — try this if the mobile site doesn't show party chat.
- **Speaker when no headset** — off = earpiece instead of loudspeaker.
- **Fix audio routing now** — also in the notification as "Fix audio".
- **Leave & close** — also "Leave" in the notification, or swipe the app away.

Back button minimizes instead of closing, so you won't drop out by accident.

## Notes
- Uses Microsoft's own web client; nothing is reverse-engineered.
- Signed with an included personal key (`app/partychat.keystore`), so new builds
  install over old ones. Keep the repo private.
