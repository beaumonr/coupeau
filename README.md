# Voice Bubble (test build)

A small Android app that puts a dictation bubble on top of WhatsApp's green mic
(and Google Translate, Messages, Gmail, or any app you add).

- 🔵 **Tap**: start talking. Words appear live in the message box. Pauses don't stop it.
- 🔴 **Tap**: stop.
- 🟢 **Tap**: send (presses the app's own Send button).
- **Long-press** while red/green: cancel and restore the box.
- **Long-press** while blue: snap back to the app's button after dragging.
- **Drag**: move it (remembered per app). Drop at the very bottom: hide until you reopen that app.
- **On/off**: Quick Settings tile, Pause in the notification, or the switch in the app.

## Install

1. Download `dist/VoiceBubble-0.1.apk` on the phone and open it. Allow "install unknown apps" if asked.
2. Open **Voice Bubble** and follow the checklist: accessibility on, microphone allowed.
   If accessibility is greyed out: App info → ⋮ → **Allow restricted settings**, then try again.

## Build

Needs JDK 17+ and the Android SDK (platform 35). `./gradlew assembleDebug`
