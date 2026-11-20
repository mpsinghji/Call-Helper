# Build Information - Call Handler v1.4.5

## Artifact Specifications

- **Application Name:** Call Handler
- **Package ID:** `com.callhandler.service`
- **Version Name:** `1.4.5`
- **Version Code:** `12`
- **Min SDK:** 26 (Android 8.0 Oreo)
- **Target SDK:** 34 (Android 14 Upside Down Cake)
- **Compile SDK:** 34
- **Release Date:** November 20, 2026

## Build Environment & Toolchain

- **Build System:** Gradle 8.2
- **Android Gradle Plugin (AGP):** 8.2.2
- **Kotlin Standard Library:** 1.9.22
- **Java Development Kit:** JDK 17 (Eclipse Temurin)
- **Host OS (CI Runner):** Ubuntu 22.04 LTS (GitHub Actions `ubuntu-latest`)

## Code Signing & Key Information

- **Key Store:** Permanent release keystore (`release.jks`)
- **Key Alias:** `callhelper`
- **Key Algorithm:** RSA 2048-bit
- **Signature Schemes:** APK Signature Scheme v2 & v3
- **Signer Certificate SHA-256 Fingerprint:**
  ```text
  23414c2ab95cddc0e1818a73fd00867b2578233462543585aa900e2090491c39
  ```

## Accessibility Service Configuration

- **Service Class:** `com.callhandler.service.debug.TruecallerAccessibilityService`
- **Service Label:** `Caller Identification Service`
- **Permission:** `android.permission.BIND_ACCESSIBILITY_SERVICE`
