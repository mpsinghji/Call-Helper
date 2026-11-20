# Changelog - Call Handler v1.4.5

**Release Date:** 2026-11-20  
**Version:** 1.4.5  
**VersionCode:** 12  
**Package ID:** `com.callhandler.service`  

---

## What's Changed in v1.4.5

### 1. Accessibility Service Label Renamed
- **Updated:** Changed the accessibility service label (`@string/truecaller_a11y_label`) in `strings.xml` to **"Caller Identification Service"**.
- **Privacy & Cleanliness:** Removed all references to the application name ("Call Handler"), legacy prefix ("VCA"), and "Truecaller Debug" from the user-facing accessibility service label in Android Accessibility Settings.
- **Service Continuity:** The service declaration, permissions, and accessibility configuration remain 100% intact and functional.

### 2. Full Regression Verification & In-Place Update
- Preserved all existing capabilities:
  - Hands-free incoming call announcements via Bluetooth SCO.
  - Cellular (GSM) and VoIP (WhatsApp, Instagram, etc.) incoming call detection.
  - Caller-ID overlay extraction and notification listening.
  - Offline local contacts lookup and spam policy handling.
  - Offline speech recognizer voice commands: Answer, Reject, Speaker, Silent.
  - Persistent "Check update" setting in Settings tab.
- Supports direct in-place update (`adb install -r`) over version 1.4.4 (`versionCode 11`) and earlier.
