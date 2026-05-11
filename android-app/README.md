# Stalkerhek-rewrite-android

Android IPTV streaming client with embedded STB emulation engine. Built with Jetpack Compose for TV and Android, powered by a Rust native engine.

This is a rebuild and fork of [kidpoleon/stalkerhek](https://github.com/kidpoleon/stalkerhek), porting the full management web UI and engine controller into a standalone Android app with a modern Jetpack Compose UI, foreground service, and automated CI releases.

## Features

- **Embedded Engine** — Full STB portal emulation via native Rust library (JNI)
- **Management Web UI** — Built-in Ktor server on port 4400 for managing profiles and filters from any device on your network
- **Profile Management** — Multiple portal profiles with custom MAC, device IDs, HLS/proxy port configuration
- **Channel Filtering** — Per-profile genre/channel enable/disable, channel renaming (prefix/suffix), genre renaming
- **Live Status** — Real-time profile status polling, start/stop controls
- **Auto-start** — Foreground service with boot receiver, OOM-protected
- **QR Code** — Scan to open management dashboard from any device

## Tech Stack

- **UI**: Jetpack Compose, Android TV Compose, Material3
- **Engine**: Kotlin JNI bridge to Rust (`libstalkerhek_engine.so`)
- **Server**: Ktor CIO (embedded HTTP server)
- **Build**: Gradle + Android SDK

## Build

```bash
./gradlew assembleDebug
```

The APK includes native libraries for `arm64-v8a`, `armeabi-v7a`, and `x86_64`.

## Architecture

```
android-app/
├── app/
│   ├── src/main/
│   │   ├── java/com/stalkerhek/tv/
│   │   │   ├── engine/       # JNI bridge, engine controller, profile config
│   │   │   ├── management/   # Ktor server + web UI templates
│   │   │   ├── tv/           # TV UI (QR, player)
│   │   │   ├── util/         # Network utils
│   │   │   ├── MainActivity.kt
│   │   │   ├── EngineService.kt
│   │   │   ├── BootReceiver.kt
│   │   │   └── StalkerApplication.kt
│   │   ├── jniLibs/          # Prebuilt native Rust libraries
│   │   └── res/              # Drawables, icons
│   └── build.gradle.kts
├── gradle/
└── build.gradle.kts
```

## Web UI

Once running, open `http://<device-ip>:4400/dashboard` in any browser:

| Path | Description |
|------|-------------|
| `/dashboard` | Profile management (create/edit/start/stop/delete) |
| `/filters?id=N` | Channel filtering, genre management, renaming |
| `/api/v1/profiles` | Profile list (JSON) |
| `/api/profile_status` | Live profile statuses (JSON) |

## License

MIT
