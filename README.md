# TextCascade

Lightweight native Android clipboard sync client for [ClipCascade](https://github.com/Sathvik-Rao/ClipCascade) servers. Pure Kotlin, zero third-party runtime dependencies, sub-10MB memory footprint.

## Be different from ClipCascade
- **Text only** - Remove Image and File sharing support.
- **P2S only** - Remove P2P mode Support.
- **Xposed Background Clipboard read support** - can read clipboard in backgroound via xposed.


## Architecture

```
ClipboardManager ──► ClipboardSources ──► TextSyncEngine ──► StompClient ──► RawWebSocketClient
                        │                      │
                   Xposed Hook            AES-256-GCM
                 (system_server)         encrypt/decrypt
```

- **ClipboardSources** — dual-path monitoring: `ClipboardManager.OnPrimaryClipChangedListener` (foreground) + logcat trigger (background)
- **TextSyncEngine** — deduplication (FNV1a-64), size-limit enforcement, AES-256-GCM, exponential backoff reconnect
- **StompClient / RawWebSocketClient** — STOMP 1.0 over raw `java.net.Socket` / `SSLSocketFactory`, zero external dependencies
- **Xposed module** — hooks `ClipboardService.isDefaultIme` in `system_server` for background clipboard access

## Requirements

- Android 8.0+ (API 26)
- LSPosed with API 102+ support (for Xposed module)
- ClipCascade server (P2S mode)

## Building

```bash
export ANDROID_HOME=/path/to/android/sdk
./gradlew assembleRelease
```

Sign with your own keystore:

```bash
apksigner sign --ks your-key.jks --out app-release.apk app/build/outputs/apk/release/app-release-unsigned.apk
```

## Xposed Module

The APK doubles as an LSPosed module:

1. Enable the module in LSPosed Manager
2. Set scope to `system` (hooks `ClipboardService` in `system_server`)
3. Reboot

Hooks `isDefaultIme()` to return `true` for TextCascade, granting background clipboard access.

## Settings

| Setting | Default | Description |
|---------|---------|-------------|
| Server URL | `http://localhost:8080` | ClipCascade server address |
| Hash rounds | `664937` | PBKDF2 iterations |
| Encryption salt | (empty) | PBKDF2 salt suffix |
| Local max bytes | `512000` | Max clipboard payload |
| Enable encryption | On | AES-256-GCM |
| Save password | Off | Stores SHA3-512 hash only |
| Relaunch on boot | Off | Auto-start after reboot |
| Status notifications | Off | Notify on disconnect |

## License

GNU General Public License v3.0 — see [LICENSE](LICENSE).

TextCascade Android — Native clipboard sync client for ClipCascade
Copyright (C) 2026 Manet Kirby

## Credits

This project also references logic from [Clipboard Whitelist](https://github.com/Xposed-Modules-Repo/io.github.tehcneko.clipboardwhitelist) for Xposed-based clipboard access.


This project is a Kotlin-based native Android client for [ClipCascade](https://github.com/Sathvik-Rao/ClipCascade), originally created by [Sathvik-Rao](https://github.com/Sathvik-Rao).

Both projects are licensed under the GNU General Public License v3.0.
