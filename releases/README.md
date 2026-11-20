# Call Handler / VoiceCall Assistant Releases

This directory contains versioned documentation, build details, and cryptographic checksums for all official releases of **Call Handler** (`com.callhandler.service`).

## Directory Structure

```text
releases/
├── README.md
├── v1.4.4/
│   ├── CHANGELOG.md
│   ├── BUILD_INFO.md
│   └── SHA256SUMS.txt
```

## Official Downloads

Official signed release APKs are hosted on GitHub Releases:
- [Latest Release (v1.4.4)](https://github.com/mpsinghji/Call-Helper/releases/tag/v1.4.4)
- [All Releases](https://github.com/mpsinghji/Call-Helper/releases)

## Release History

| Version | VersionCode | Release Date | Highlights | Download |
|---------|-------------|--------------|------------|----------|
| **v1.4.4** | 11 | 2026-11-20 | Accessibility service label fix (removed legacy "VCA" name), added persistent "Check update" setting, full regression verification, production signed | [CallHelper-v1.4.4.apk](https://github.com/mpsinghji/Call-Helper/releases/download/v1.4.4/CallHelper-v1.4.4.apk) |
| **v1.4.3** | 10 | 2026-11-15 | Permanent release keystore integration and secret-driven CI signing | [CallHelper-v1.4.3.apk](https://github.com/mpsinghji/Call-Helper/releases/download/v1.4.3/CallHelper-v1.4.3.apk) |
| **v1.4.2** | 9 | 2026-11-15 | Immediate announcement cancellation on call decline | [CallHelper-v1.4.2.apk](https://github.com/mpsinghji/Call-Helper/releases/download/v1.4.2/CallHelper-v1.4.2.apk) |
| **v1.4.1** | 8 | 2026-11-09 | In-app auto-update from GitHub Releases | [CallHelper-v1.4.1.apk](https://github.com/mpsinghji/Call-Helper/releases/download/v1.4.1/CallHelper-v1.4.1.apk) |

## Verification

To verify the integrity and authentic developer signature of an APK:

```bash
# 1. Verify SHA-256 checksum:
sha256sum -c SHA256SUMS.txt

# 2. Verify APK signature with Android SDK apksigner:
apksigner verify --verbose --print-certs CallHelper-v1.4.4.apk
```

Expected Production Signer Certificate SHA-256:
```text
23414c2ab95cddc0e1818a73fd00867b2578233462543585aa900e2090491c39
```
