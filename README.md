# PocketOS

Rootless Alpine Linux ARM64 guest running under Android ARM64 QEMU, with a persistent ext4 disk and a foreground-service control plane.

## Current status

The Android app, ARM64 QEMU packaging, guest image, serial login flow, storage tools, networking controls, and CI validation are implemented. CI builds both APKs and verifies Alpine boot, first-run account setup, same-disk persistence, SQLite, and localhost HTTP workloads.

**Physical ARM64 Android validation is still pending.** The guest runtime gate remains closed until the consolidated QEMU and Alpine boot test passes on a Vivo I2501. Host QEMU and the x86_64 Android emulator do not establish physical ARM64 guest success.

## Features

- Runs without Android root using packaged ARM64 QEMU and an Alpine ARM64 guest.
- Creates the `pocketos` guest account during first login; no preset Linux password is shipped.
- Persists guest files on a private ext4 disk, with storage reporting, backup export/import, disk growth, and boot-time filesystem expansion.
- Provides a serial terminal, home-screen widget, foreground-service lifecycle, 60-minute session limit, and 20% battery cutoff.
- Forwards guest HTTP to Android localhost by default. Wi-Fi LAN exposure requires explicit opt-in.
- Keeps Android guest startup fail-closed until physical ARM64 runtime validation succeeds.

## Build and test

Use JDK 17, Android SDK 35, NDK 27.2.12479018, and Gradle 8.9 for the repository's CI configuration. The Gradle wrapper is not committed. Build and test commands are documented in [Android QEMU integration](docs/ANDROID_QEMU_INTEGRATION.md).

For a Vivo device test, follow [Physical Android testing](docs/PHYSICAL_ANDROID_TESTING.md). Use both APKs from the same successful trusted push workflow run; the handoff script verifies their hashes and signing certificate before installation.

See [Development status](docs/DEVELOPMENT_STATUS.md), [Architecture](docs/ARCHITECTURE.md), and [Roadmap](docs/ROADMAP.md) for implementation details and remaining validation.
