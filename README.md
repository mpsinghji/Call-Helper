# CallHandlerService

An intelligent incoming-call assistant for Android (minSdk 26 / Android 8.0).
Announces callers over Bluetooth or the phone speaker, listens for voice
commands on cellular calls, integrates Truecaller caller-ID updates, and
manages audio routing without disrupting the user's experience.

## Features

- Announces the caller's name in Bluetooth earphones when connected; optional
  loudspeaker fallback.
- Ducks the ringtone while speaking, then restores it.
- Repeats announcements at a configurable interval, up to a configurable
  maximum, while the phone rings.
- Voice commands for cellular calls: Answer, Reject, Silent, Speaker,
  Volume Up, Volume Down.
- Announces WhatsApp voice/video calls (voice commands intentionally disabled:
  Android provides no public API to answer WhatsApp calls).
- Waits ~2.5 s for Truecaller to identify unknown callers before the first
  announcement; later identifications update subsequent announcements.
- Filters out non-call Truecaller notifications (profile views, promotions,
  backups, Premium offers, statistics…).
- Stops all announcements immediately when the call is answered, rejected,
  missed, or ends — including manual answer of WhatsApp calls.
- Handles Bluetooth connect/disconnect mid-ring: falls back to speaker and
  returns to Bluetooth automatically on the next announcement.

## Project layout

```
app/src/main/java/com/callhandler/service/
├── App.kt                                — notification channel setup
├── core/
│   ├── CallHandlerService.kt             — foreground orchestrator
│   ├── CallStateMachine.kt               — IDLE → RINGING → ANSWERED/ENDED
│   ├── CallStateReceiver.kt              — PHONE_STATE broadcasts
│   ├── CallModels.kt                     — CallSource, CallerIdentity, CallState
│   └── TelecomHelper.kt                  — answer / reject via TelecomManager
├── audio/
│   ├── AudioRouter.kt                    — BT/speaker selection, ring ducking
│   └── AnnouncementManager.kt            — TTS with audio focus
├── voice/
│   ├── VoiceCommand.kt                   — phrase → command mapping
│   └── VoiceCommandManager.kt            — SpeechRecognizer loop
├── identity/
│   ├── CallerIdentityManager.kt          — contacts → Truecaller → unknown
│   └── CallNotificationListener.kt       — Truecaller + WhatsApp notifications
├── settings/SettingsManager.kt           — typed SharedPreferences access
└── ui/MainActivity.kt                    — permissions + settings UI
```

## Required setup on device

1. Grant runtime permissions from the main screen (phone, call log, contacts,
   microphone, Bluetooth on Android 12+, notifications on Android 13+).
2. Enable **Notification access** for the app (needed for Truecaller
   identification and WhatsApp call detection).
3. Recommended: exclude the app from battery optimization so announcements
   are not delayed.

## Platform caveats (important)

- **Reject on Android 8.0/8.1**: `TelecomManager.endCall()` requires API 28.
  On API 26–27 the "Reject" voice command falls back to silencing the ringer.
- **Voice recognition during ringing** competes with the ringtone and is
  best-effort; recognition quality varies by device and recognizer engine.
  Announcement gaps are when commands are most reliably heard.
- **Notification text heuristics**: Truecaller and WhatsApp notification
  formats change between app versions; the keyword filters in
  `CallNotificationListener` may need updating over time.
- **OEM restrictions**: some vendors (Xiaomi, Oppo, etc.) aggressively kill
  background receivers/services; users may need to whitelist the app.
- `EXTRA_INCOMING_NUMBER` requires `READ_CALL_LOG` on API 29+ and arrives in
  a second PHONE_STATE broadcast; the service handles both broadcasts.

## Build

Open in Android Studio (AGP 8.5, Kotlin 2.0, JDK 17) and run, or:

```
./gradlew :app:assembleDebug
```
