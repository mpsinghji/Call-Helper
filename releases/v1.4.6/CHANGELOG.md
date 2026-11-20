# Changelog - Call Handler v1.4.6

**Release Date:** 2026-11-20  
**Version:** 1.4.6  
**VersionCode:** 13  
**Package ID:** `com.callhandler.service`  

---

## What's Changed in v1.4.6

### 1. In-App Update Checking Resilience & Defect Fix
- **Root Cause Fix:** Resolved "Unable to check for updates" issue caused by GitHub REST API unauthenticated rate limits (HTTP 403 / 429) on shared IP addresses.
- **Resilient Web Redirect Fallback:** Added automatic fallback to GitHub's canonical web release redirect endpoint (`https://github.com/mpsinghji/Call-Helper/releases/latest`), which resolves latest release tags via HTTP 302 with zero rate limiting.
- **ETag Conditional Requests:** Implemented HTTP ETag caching (`If-None-Match: <etag>`), enabling HTTP 304 (Not Modified) responses that consume negligible network bandwidth and preserve rate limit quotas.
- **Draft & Pre-Release Filtering:** Added explicit filtering to exclude draft and pre-release GitHub releases from in-app update prompts.
- **Synthesized Fallback Asset URL:** If release metadata is missing an explicit `.apk` asset in the payload, UpdateManager automatically synthesizes the standard release asset URL (`CallHelper-$tagName.apk`).

### 2. User Experience & UI Error Handling
- **Categorized Error Feedback:** Replaced generic "Unable to check for updates" dialog with user-friendly, specific error categories (`NO_NETWORK`, `TIMEOUT`, `RATE_LIMITED`, `NOT_FOUND`, `SERVER_ERROR`, `MALFORMED_DATA`).
- **Interactive Retry:** Added "Retry" action button on manual update check failure, allowing users to re-test immediately without navigating away.
- **Non-Blocking Background Checks:** Automatic startup update checks fail silently without interrupting the user.
- **Concurrency & State Safety:** Switched `isCheckingUpdate` to thread-safe `AtomicBoolean` guaranteed to clear in `finally` blocks, preventing the update UI from becoming stuck in a loading state.

### 3. Comprehensive Test Coverage
- Added automated unit test suite covering 16 scenarios:
  1. Successful GitHub API response (v1.4.5)
  2. Newer release available (v2.0.0)
  3. App already up to date
  4. Prerelease filtering (`prerelease: true`)
  5. Draft filtering (`draft: true`)
  6. Malformed JSON handling
  7. Missing tag_name handling
  8. Missing APK asset fallback
  9. HTTP 403 fallback to web redirect
  10. HTTP 404 Not Found handling
  11. HTTP 429 Rate Limit handling
  12. HTTP 500 Server Error handling
  13. SocketTimeoutException handling
  14. UnknownHostException handling
  15. Preference disabled skips automatic check
  16. Manual check allowed when automatic check is disabled
  17. Additional tests for ETag 304, tag extraction, and semver comparison.

### 4. Preservation of Core Functionality
- Preserved user-visible Accessibility Service label: **"Caller Identification Service"**.
- Preserved all permissions, audio routing (Bluetooth SCO), voice recognition commands, and background services.
- Supports seamless direct update (`adb install -r`) over version 1.4.5 (`versionCode 12`) and earlier versions.
