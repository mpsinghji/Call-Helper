# Changelog - Call Handler v1.4.4

**Release Date:** 2026-11-20  
**Version:** 1.4.4  
**VersionCode:** 11  
**Package ID:** `com.callhandler.service`  

---

## What's Changed in v1.4.4

### 1. Accessibility Service Label Corrected
- **Fixed:** The Truecaller accessibility observer service label in Android Accessibility Settings previously displayed the legacy name `VCA Truecaller Debug`.
- **Updated:** Updated `@string/truecaller_a11y_label` in `strings.xml` to `Call Handler — Truecaller Debug`, ensuring the modern app name "Call Handler" is consistently displayed in Android Accessibility system menus.
- **Cleaned:** Eliminated legacy "VCA" strings and references across application resources.

### 2. Functional "Check update" Setting
- **Added:** Added a visible, functional `CheckBoxPreference` labelled `Check update` to the Settings tab (`preferences.xml`).
- **Persisted:** Persists setting in `SharedPreferences` under key `pref_check_updates_enabled` (defaults to `true`).
- **Connected:** Wired to `MainActivity.kt` and `SettingsManager.kt`. When disabled, automatic startup checks against GitHub Releases are completely skipped, eliminating unnecessary background network traffic and avoiding misleading update prompts.
- **Manual Control Retained:** Users can still check for updates manually on demand via the Tools tab button without being blocked by the automatic check toggle.
- **Resilient:** Safe handling for network dropouts, offline usage, and GitHub API errors.

### 3. Regression Review & Full Feature Preservation
- Verified 100% preservation of all existing application features:
  - Hands-free incoming call announcements via Bluetooth SCO.
  - Cellular (GSM) and VoIP (WhatsApp, Instagram, etc.) incoming call detection.
  - Truecaller accessibility observer integration and overlay caller-ID extraction.
  - Offline local contacts lookup and spam policy handling (normal, warning, block).
  - Speech recognizer voice commands: Answer, Reject, Speaker, Silent.
  - Foreground service lifecycle, notification listener service, and active call overlay.
  - Full backward compatibility and in-place update support (`adb install -r`) over previous production installations (`v1.4.3`).

### 4. Automated Testing & Verification
- Added `SettingsManagerTest` covering default preferences, persistence, and toggle behavior.
- Added `AccessibilityConfigTest` validating manifest declarations, preference screen structures, and absence of legacy name tokens.
- All unit tests pass with zero regressions.
