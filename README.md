# TextCascade

[中文](README_ZH_CN.md)

Lightweight native Android clipboard sync client (v2 protocol) for TextCascade servers. Pure Kotlin, zero third-party runtime dependencies, sub-10MB memory footprint.

## Be different from ClipCascade
- **Text only** - Remove Image and File sharing support.
- **Token protocol v2** - `POST /api/v1/login` (JSON, raw password over TLS) + Bearer WebSocket with subprotocol `textcascade.v1`; no STOMP, no CSRF, no cookies.
- **Xposed Background Clipboard read support** - can read clipboard in background via Xposed.

## Features
- **Encrypted sensitive settings** - the saved raw password, derived AES key and bearer token are stored through Android Keystore with AES-256-GCM. Legacy plaintext values migrate on first read; when Keystore is unavailable the app falls back to plaintext storage instead of crashing.
- **V2 sync engine** - hello (with clipboard snapshot) / welcome / clip / clip_ack / ping→pong / bye / error handling, hash+version double dedup, echo suppression, watch-dog half-open detection, maintenance vs normal backoff (1/2/5/10 fixed 10 vs 1/2/5/10/30/60 fixed 60), early reconnect on user unlock.
- **Token lifecycle** - local expiry pre-check (re-login 60s before `expiresAtUtc`), single silent re-login on 401 per session cycle, rate-limited login (429) backs off at least 30s.
- **End-to-end encryption** - PBKDF2-HMAC-SHA256 (salt = `username$password$salt`, `hashRounds` iterations) → AES-256-GCM payloads `{"nonce","ciphertext","tag"}` with 16-byte nonces; FNV-1a 64 lowercase-hex hash fields; interops with the Windows client.
- **Saved-password indicator** - when "Save password" is enabled, the password field shows a green "Password saved - leave empty to reuse" indicator.
- **Versioned title** - the main screen displays the current app version for easier troubleshooting.
- **Unit test baseline** - JUnit4 + Robolectric tests cover protocol contract samples (byte-exact), crypto vectors, login client, engine state machine and encrypted prefs.

## Architecture

```
ClipboardManager ──► ClipboardSources ──► TextSyncEngine ──► RawWebSocketClient
                        │                      │
                   Xposed Hook            AES-256-GCM
                 (system_server)         encrypt/decrypt
```

- **ClipboardSources** - dual-path monitoring: `ClipboardManager.OnPrimaryClipChangedListener` (foreground) + logcat trigger (background)
- **TextSyncEngine** - protocol state machine over JSON messages, deduplication (FNV1a-64 + version), size-limit enforcement, AES-256-GCM, backoff reconnect, session-expiry recovery
- **RawWebSocketClient** - hand-written RFC 6455 WebSocket over raw `java.net.Socket` / `SSLSocketFactory` with `Authorization: Bearer` + `Sec-WebSocket-Protocol: textcascade.v1`, zero external dependencies
- **Xposed module** - hooks `ClipboardService.isDefaultIme` in `system_server` for background clipboard access

## Requirements

- Android 8.0+ (API 26)
- LSPosed with API 102+ support (for Xposed module)
- TextCascade server exposing `POST /api/v1/login` and `wss://host/api/v1/sync`

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
| Server URL | `https://localhosts:8443` | TextCascade server address (HTTPS only) |
| Hash rounds | `664937` | PBKDF2 iterations |
| Encryption salt | (empty) | Included in the PBKDF2 salt input |
| Local max bytes | `512000` | Max clipboard payload |
| Enable encryption | On | AES-256-GCM |
| Save password | Off | Encrypts the raw password with Android Keystore; credentials are derived on each login and the saved-password indicator is shown when enabled |
| Trust all certificates | Off | Accepts any TLS certificate (insecure, for self-signed deployments) |
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
