# Voice Bubble (test build)

A small Android app that puts a dictation bubble on top of WhatsApp's green mic
(and Google Translate, Messages, Gmail, or any app you add).

- 🔵 **Tap**: start talking. Words appear live in the message box. Pauses don't stop it.
- 🔴 **Tap**: stop.
- 🟢 **Tap**: send (presses the app's own Send button).
- **Hold** while red/green: cancel and restore the box.
- **Hold** while blue, then drag: move it out of the way to type. Tap it to bring it back and
  talk; it also returns by itself after you send. Drop near the button to snap back; drop at the
  very bottom to hide until you reopen that app.
- **On/off**: Quick Settings tile, Pause in the notification, or the switch in the app.

## Install

1. Download `dist/VoiceBubble-0.5.apk` on the phone and open it. Allow "install unknown apps" if asked.
2. Open **Voice Bubble** and follow the checklist: accessibility on, microphone allowed.
   If accessibility is greyed out: App info → ⋮ → **Allow restricted settings**, then try again.

## Build

Needs JDK 17+ and the Android SDK (platform 35). `./gradlew assembleDebug`
