# Call Handler (Call-Helper)

[![Release APK](https://github.com/mpsinghji/Call-Helper/actions/workflows/release.yml/badge.svg)](https://github.com/mpsinghji/Call-Helper/actions/workflows/release.yml)
![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-brightgreen)
![Language](https://img.shields.io/badge/Language-Kotlin%202.0-blue)
![Privacy](https://img.shields.io/badge/Privacy-100%25%20On--Device-success)

> An intelligent, hands-free incoming call assistant for Android. Announces cellular and VoIP callers over Bluetooth earphones or speaker, responds to voice commands, extracts real-time Caller-ID from contacts and Truecaller, and ensures you never have to look at your phone while driving, cycling, or working.

---

## 📖 About

**Call Handler** is designed for hands-free safety and convenience. Whether you are commuting, exercising, or multitasking, Call Handler bridges your phone's incoming call events to your Bluetooth headset:

- **Hands-Free Caller Announcements**: Speaks who is calling through your connected Bluetooth headset or earbuds before you decide to reach for your phone.
- **Voice Control**: Respond naturally with voice commands ("Answer", "Reject", "Silent", "Speaker") without taking your hands off the steering wheel or handlebars.
- **Unified Cellular & VoIP Coverage**: Works for standard GSM phone calls as well as popular VoIP messaging apps (WhatsApp, Telegram, Instagram, etc.).
- **Truecaller & Contacts Integration**: Matches unknown numbers in real-time by waiting for Truecaller's identification overlay and reading your local contacts.
- **Privacy First**: 100% on-device operation. No voice recordings, contacts, or call logs are transmitted off your phone.

### GitHub Repository About Section Suggestion
If configuring your GitHub repository settings, the following metadata is recommended:
- **Description**: `Intelligent Android call assistant announcing GSM & VoIP calls over Bluetooth with offline voice control, Truecaller caller-ID, and false-positive filtering.`
- **Topics**: `android`, `kotlin`, `bluetooth-audio`, `tts`, `caller-id`, `truecaller`, `voip`, `hands-free`, `voice-commands`, `accessibility-service`

---

## ✨ Features

### 🎧 Bluetooth Audio & Smart TTS
- **Bluetooth Earphone Routing**: Automatically routes caller announcements through Bluetooth SCO / Headset profile.
- **Loudspeaker Fallback**: Automatically falls back to the phone loudspeaker if no Bluetooth device is connected (configurable).
- **Ringtone Ducking**: Temporarily lowers (ducks) the incoming ringtone volume while speaking the announcement, then restores it between intervals.
- **Periodic Announcements**: Repeats caller announcements at configurable intervals (e.g., every 4 seconds) up to a preset maximum count.
- **Dynamic Connection Handling**: Seamlessly handles Bluetooth connect/disconnect events mid-ring.

### 🔍 Smart Caller Identification
- **Local Contacts Match**: Instant zero-latency lookup for saved address book contacts.
- **Android 10+ Call Screening Role**: Enables reliable caller number retrieval on modern Android versions where broadcast receivers are restricted.
- **Truecaller Screen & Notification Scraping**: Waits a brief configurable window (~2.5s) for Truecaller to identify unknown numbers, dynamically updating announcements.
- **False-Positive & Onboarding Filter**: Custom parser filters out Truecaller UI artifacts (e.g., "Get started", "Protect your family from scams", "Skip", promotional banners, and non-call notifications) to guarantee accurate caller names.

### 📞 VoIP Call Direction Detection
- **WhatsApp & Messaging Apps**: Announces incoming voice and video calls from WhatsApp, Telegram, Instagram, and more.
- **Direction & State Filtering**: Smart classification engine differentiates incoming calls from user-initiated outgoing calls ("Calling...", "Connecting..."), active call timers, and ongoing call notifications. Outgoing calls are strictly silenced.

### 🎙️ Hands-Free Voice Commands
- **Offline Speech Recognition**: Runs locally using Android's speech recognition engine during cellular ringing.
- **Supported Commands**:
  - `Answer` / `Pick up` / `Accept`
  - `Reject` / `Decline` / `Hang up`
  - `Silent` / `Mute`
  - `Speaker` / `Hands-free`
  - `Volume Up` / `Volume Down`

### 🔬 Diagnostics & Developer Tools
- **Bluetooth SCO Tester**: Built-in tool in the app to test audio routing and volume levels through your connected headset.
- **Truecaller Debug Console**: Real-time inspection screen displaying accessibility event streams and node hierarchy dumps for verifying screen parsing.

---

## 🚀 Automated CI/CD & Releases

This repository includes a fully automated **GitHub Actions CI/CD workflow** (`.github/workflows/release.yml`):

- **Trigger on Push**: Every push to the `main` branch (or tag push `v*`) automatically initiates a release build.
- **Automated Testing**: Runs unit test suites (`TruecallerParserTest`, `VoipCallDetectorTest`) before packaging.
- **Automated Version Tagging**: Automatically creates semantic tags (`v1.0.<run_number>` or custom tag).
- **GitHub Release Publishing**: Publishes a new release with auto-generated release notes and attaches the ready-to-install `CallHelper-v<tag>.apk`.

---

## 📁 Project Architecture

```
app/src/main/java/com/callhandler/service/
├── App.kt                                — Application class & notification channel setup
├── core/
│   ├── CallHandlerService.kt             — Foreground service orchestrator
│   ├── CallStateMachine.kt               — IDLE → RINGING → ANSWERED/ENDED state machine
│   ├── CallStateReceiver.kt              — Broadcast receiver for cellular phone states
│   ├── CallModels.kt                     — Data models (CallSource, CallerIdentity, CallState)
│   └── TelecomHelper.kt                  — TelecomManager integration (answer/reject calls)
├── audio/
│   ├── AudioRouter.kt                    — Bluetooth SCO / speaker routing & ringtone ducking
│   └── AnnouncementManager.kt            — Text-to-Speech synthesis with audio focus
├── voice/
│   ├── VoiceCommand.kt                   — Voice command grammar & intent definitions
│   └── VoiceCommandManager.kt            — SpeechRecognizer loop & audio overlay
├── identity/
│   ├── CallerIdentityManager.kt          — Multi-tier resolution (Contacts → Truecaller → Unknown)
│   ├── CallNotificationListener.kt       — Notification listener for Truecaller & VoIP apps
│   ├── IncomingCallScreeningService.kt   — Android 10+ CallScreeningService for reliable caller ID
│   ├── TruecallerParser.kt               — Accessibility node parser with false-positive filtering
│   └── VoipCallDetector.kt               — VoIP direction detection (incoming vs outgoing)
├── debug/
│   ├── DebugConsoleActivity.kt           — Live accessibility inspection console
│   └── TruecallerAccessibilityService.kt — Accessibility service capturing overlay UI
├── settings/
│   └── SettingsManager.kt                — SharedPreferences settings wrapper
└── ui/
    ├── MainActivity.kt                   — ViewPager2 host with bottom navigation
    ├── PermissionsFragment.kt            — Step-by-step permission setup & status cards
    ├── ToolsFragment.kt                  — Bluetooth audio test, diagnostics, & About card
    └── SettingsFragment.kt               — Preference configuration screen
```

---

## 📱 Device Setup & Permissions

To enable all features, the app requires specific permissions:

1. **Core Permissions**: Phone State, Call Log, Contacts, Microphone, and Bluetooth Connect (Android 12+).
2. **Notification Access**: Required for Truecaller notification parsing and VoIP incoming call detection.
3. **Display Over Other Apps (Overlay)**: Required for persistent microphone access and in-call status overlay on Android 14+.
4. **Call Screening Role (Android 10+)**: Recommended for reliable incoming number resolution when system broadcast restrictions apply.
5. **Battery Optimization**: Exclude Call Handler from battery optimization so background services and receivers remain responsive.

---

## 🛠️ Local Build Instructions

### Prerequisites
- Android Studio Ladybug or newer
- Android SDK 34
- JDK 17
- Gradle 8.5+

### Build APK
```bash
# Run unit tests
./gradlew testDebugUnitTest

# Assemble debug APK
./gradlew assembleDebug

# Output APK path:
# app/build/outputs/apk/debug/app-debug.apk
```

---

## 📄 License & Privacy

- **100% Local Processing**: No call metadata, contacts, audio, or speech transcripts are collected or sent to any server.
- Released for personal safety and open-source utility.
