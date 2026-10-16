# PocketOS

Rootless, on-demand CLI Linux server sandbox for Android.

## Status

**Early development scaffold.** The Android app and widget are implemented as a control-plane prototype. **No QEMU guest is launched yet.** The engine is explicitly a stub; the UI must not claim a guest has booted.

## Goals

- Android ARM64, no device root
- Independent Alpine Linux ARM64 kernel in QEMU (future integration)
- Serial-only console; persistent guest disk
- Localhost-only forwarding by default, opt-in LAN
- Widget start/stop, 60-minute session cap, 20% battery cutoff
- Wi-Fi disconnect does not trigger shutdown

## Build

Open `android/` in Android Studio (JDK 17, Android SDK 35). Gradle wrapper is not committed in this starter: generate it with `gradle wrapper --gradle-version 8.9` using an installed Gradle, then `./gradlew assembleDebug`, or let Android Studio sync with its Gradle installation. The current app uses no external UI dependencies.

## Implementation state

- [x] Android manifest, activity, widget, control-plane service source
- [x] Policy model and unit tests
- [x] Host-side minimal guest boot experiment script
- [ ] Build/verify Android APK on a device
- [ ] Integrate QEMU as an Android native executable
- [ ] Boot real Alpine guest and expose serial console
- [ ] Graceful guest shutdown, real networking and persistence

See `docs/ARCHITECTURE.md` and `docs/ROADMAP.md`.
