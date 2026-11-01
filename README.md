# Party Chat

A personal Android app that wraps Xbox's web party chat and fixes the two things
the official app and mobile browsers get wrong:

1. **Mic keeps working in the background.** A "microphone" foreground service (with a
   notification, like Discord during calls) lets your voice keep sending with the app
   minimized or the screen off.
2. **Uses your headset.** Voice is routed to Bluetooth > wired > USB headset, falling back
   to the phone speaker (or earpiece; toggle in the menu). It re-checks every few seconds
   and when you connect/disconnect a headset.

