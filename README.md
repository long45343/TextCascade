# TextCascade

[中文](README_ZH_CN.md)

Lightweight native Android clipboard sync client for [TextCascade-server](https://github.com/long45343/textcascade-server). Pure Kotlin, with OkHttp as the only third-party runtime dependency.

> **Note:** Since v2.0.0 this client uses the TextCascade protocol (`POST /api/v1/login` + Bearer WebSocket, subprotocol `textcascade.v1`) and is **incompatible with ClipCascade servers**. To connect to a ClipCascade server, use the [v0.4.3 release](https://github.com/long45343/TextCascade/releases/tag/v0.4.3) instead.

## Usage

Open the app, grant background, notification and auto-start permissions, then configure the parameters and connect.

The APK itself is an LSPosed module: enable it in LSPosed Manager and reboot to read the clipboard in the background.

In theory, background clipboard reading can also be achieved by granting READ_LOGS via ADB/Shizuku, but this is untested.

## Settings

| Setting | Default | Description |
|---------|---------|-------------|
| Server URL | `https://localhosts:8443` | TextCascade server address (HTTPS only) |
| Hash rounds | `664937` | PBKDF2 iterations |
| Encryption salt | (empty) | Included in the PBKDF2 salt input |
| Local max bytes | `512000` | Max clipboard payload |
| Enable encryption | On | AES-256-GCM |
| Save password | Off | Encrypts the raw password with Android Keystore; credentials are derived on each login, and the saved-password indicator is shown when enabled |
| Trust all certificates | Off | Accepts any TLS certificate (insecure, for self-signed deployments) |
| Relaunch on boot | Off | Auto-start after reboot |
| Status notifications | Off | Notify on disconnect |

## Be different from ClipCascade
- **Text only** - Focused on text transfer
- **New protocol** - `POST /api/v1/login` (JSON, raw password over TLS) + Bearer WebSocket (subprotocol `textcascade.v1`); no STOMP, no CSRF, no cookies
- **Xposed background clipboard read** - Reads the clipboard in the background via Xposed, more stable than the ADB+READ_LOGS approach

## Features
- **Encrypted sensitive settings** - The saved raw password, derived AES key and bearer token are encrypted with Android Keystore + AES-256-GCM, with a fallback when the TEE is unavailable.
- **End-to-end encryption** - Encryption parameters are configurable; when enabled, the server cannot decrypt the plaintext.

## Architecture

```
ClipboardManager ──► ClipboardSources ──► TextSyncEngine ──► OkHttpTransport
                        │                      │
                   Xposed Hook            AES-256-GCM
                 (system_server)         encrypt/decrypt
```

- **ClipboardSources** - dual-path monitoring: `ClipboardManager.OnPrimaryClipChangedListener` (foreground) + logcat trigger (background)
- **TextSyncEngine** - JSON message protocol state machine, deduplication (FNV1a-64 + version), size limits, AES-256-GCM, backoff reconnect, session-expiry recovery, offline stash & resend
- **OkHttpTransport** - RFC 6455 WebSocket over OkHttp 4.12, carrying `Authorization: Bearer` and `Sec-WebSocket-Protocol: textcascade.v1` in the upgrade request; 2s write timeout to expose half-open connections, receive watchdog at `heartbeatIntervalSeconds + 10s`
- **Xposed module** - hooks `ClipboardService.isDefaultIme` in `system_server` for background clipboard access

## Requirements

- Android 8.0+ (API 26)
- LSPosed with API 102+ support (for the Xposed module)
- A [TextCascade server](https://github.com/long45343/textcascade-server)

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

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for release history.

## License

GNU General Public License v3.0 - see [LICENSE](LICENSE).

TextCascade Android - clipboard sync client
Copyright (C) 2026 Manet Kirby

## Credits

The Xposed clipboard access logic references [Clipboard Whitelist](https://github.com/Xposed-Modules-Repo/io.github.tehcneko.clipboardwhitelist).

Before v2.0.0 this project was a Kotlin native Android client built on the [ClipCascade](https://github.com/Sathvik-Rao/ClipCascade) protocol, originally created by [Sathvik-Rao](https://github.com/Sathvik-Rao).

Both projects are licensed under the GNU General Public License v3.0.
