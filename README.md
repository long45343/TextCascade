# TextCascade

[中文](README_ZH_CN.md)

Lightweight native Android clipboard sync client for [ClipCascade](https://github.com/Sathvik-Rao/ClipCascade) servers. Pure Kotlin, zero third-party runtime dependencies, sub-10MB memory footprint.

## Be different from ClipCascade
- **Text only** - Remove Image and File sharing support.
- **P2S only** - Remove P2P mode Support.
- **Xposed Background Clipboard read support** - can read clipboard in background via Xposed.

## Features
- **Encrypted sensitive settings** - the saved raw password, password hashes, CSRF tokens and cookies are stored through Android Keystore with AES-256-GCM. Legacy plaintext values migrate on first read; when Keystore is unavailable the app falls back to plaintext storage instead of crashing.
- **Hardened sync engine** - single-flight reconnect, handshake timeout, half-open detection, session-expiry auto re-login, STOMP frame limits, malformed frame handling and logcat trigger restart.
- **Saved-password indicator** - when "Save password" is enabled, the password field shows a green "Password saved - leave empty to reuse" indicator.
- **Versioned title** - the main screen displays the current app version for easier troubleshooting.
- **Unit test baseline** - JUnit4 + Robolectric tests cover config, hashing, STOMP framing, encrypted prefs and password-hint UI.

## Architecture

```
ClipboardManager ──► ClipboardSources ──► TextSyncEngine ──► StompClient ──► RawWebSocketClient
                        │                      │
                   Xposed Hook            AES-256-GCM
                 (system_server)         encrypt/decrypt
```

- **ClipboardSources** - dual-path monitoring: `ClipboardManager.OnPrimaryClipChangedListener` (foreground) + logcat trigger (background)
- **TextSyncEngine** - deduplication (FNV1a-64), size-limit enforcement, AES-256-GCM, exponential backoff reconnect, session-expiry recovery
- **StompClient / RawWebSocketClient** - STOMP 1.0 over raw `java.net.Socket` / `SSLSocketFactory`, zero external dependencies
- **Xposed module** - hooks `ClipboardService.isDefaultIme` in `system_server` for background clipboard access

## Requirements

- Android 8.0+ (API 26)
- LSPosed with API 102+ support (for Xposed module)
- ClipCascade server (P2S mode)

## Building

```bash
export ANDROID_HOME=/path/to/android/sdk
./gradlew assembleRelease
```

Run unit tests:

```bash
./gradlew testDebugUnitTest
```

Sign with your own keystore:

```bash
apksigner sign --ks your-key.jks --out app-release.apk app/build/outputs/apk/release/app-release-unsigned.apk
```

## Xposed Module

The APK doubles as an LSPosed module:

1. Enable the module in LSPosed Manager
2. Reboot

## Settings

| Setting | Default | Description |
|---------|---------|-------------|
| Server URL | `http://localhost:8080` | ClipCascade server address |
| Hash rounds | `664937` | PBKDF2 iterations |
| Encryption salt | (empty) | PBKDF2 salt suffix |
| Local max bytes | `512000` | Max clipboard payload |
| Enable encryption | On | AES-256-GCM |
| Save password | Off | Encrypts the raw password with Android Keystore; credentials are derived on each login and the saved-password indicator is shown when enabled |
| Trust all certificates | Off | Accepts any TLS certificate (insecure) |
| Relaunch on boot | Off | Auto-start after reboot |
| Status notifications | Off | Notify on disconnect |

## Changelog

Release history is documented in [CHANGELOG.md](CHANGELOG.md).

## License

GNU General Public License v3.0 - see [LICENSE](LICENSE).

TextCascade Android - Native clipboard sync client for ClipCascade
Copyright (C) 2026 Manet Kirby

## Credits

This project also references logic from [Clipboard Whitelist](https://github.com/Xposed-Modules-Repo/io.github.tehcneko.clipboardwhitelist) for Xposed-based clipboard access.

This project is a Kotlin-based native Android client for [ClipCascade](https://github.com/Sathvik-Rao/ClipCascade), originally created by [Sathvik-Rao](https://github.com/Sathvik-Rao).

Both projects are licensed under the GNU General Public License v3.0.
