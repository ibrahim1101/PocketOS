# PocketOS development status

Updated: 2026-10-18

## Current state

- Active milestone: M2 — Android-native QEMU integration investigation.
- Active development branch: `feat/m1-linux-boot`.
- Pull request: [#1 — M1: Start Alpine ARM64 guest image builder](https://github.com/ibrahim1101/PocketOS/pull/1) (open).
- Latest verified code revision: `468efa18e55235da500286f6d175e58564a038a4`.
- CI run [#93](https://github.com/ibrahim1101/PocketOS/actions/runs/39149791305) passed Android unit tests, APK assembly, JNI instrumentation on an x86_64 emulator, ELF ABI and dependency checks for both packaged native libraries, and all Alpine ARM64 guest checks: serial boot, Linux shell commands, ext4 persistence across a fresh QEMU process, and localhost HTTP forwarding. The HTTP test confirmed QEMU listens only on host `127.0.0.1:18080` and serves the expected response from guest port 8080. The JNI libraries depend on `libm.so`, `libdl.so`, and `libc.so`, with no RPATH or RUNPATH.
- CI run [#95](https://github.com/ibrahim1101/PocketOS/actions/runs/39153381185) passed on the current revision. It reran Android unit tests, APK assembly, x86_64 emulator JNI instrumentation and ELF checks, plus the ARM64 Alpine build, boot, shell, ext4 persistence, and localhost forwarding gates.
- CI run [#98](https://github.com/ibrahim1101/PocketOS/actions/runs/39154199895) passed on the current revision, including an actual download of the pinned QEMU 11.1.2 archive, SHA-256 verification, safe extraction, and configure-script check, along with both Android and Alpine guest jobs.
- The instrumentation test installed the app and test APK on the emulator and confirmed the embedded native library loads and returns `JNI_BRIDGE_READY;QEMU_NOT_LINKED`. This validates the JNI packaging boundary only; it does not run QEMU.
- The APK audit extracts the JNI libraries and confirms their ELF machines are `AArch64` and `Advanced Micro Devices X86-64`.
- CI built the ext4 image, extracted the matching kernel and initramfs, booted them with system QEMU, mounted the VirtIO ext4 disk, and observed the `pocketos login:` prompt on PL011 serial.
- A separate test boot overrides init with `/bin/sh`; it waits for the guest shell prompt, runs `uname -s`, and reads `/etc/alpine-release` (`3.22.6`). This verifies guest command execution without enabling the locked root account for a normal boot.
- The debug APK and JNI library have been tested on an x86_64 Android emulator; no physical ARM64 device test has been performed.

## Implemented and verified

- Android activity displays development status and explicitly says the VM engine is not implemented.
- Pure Kotlin VM lifecycle state machine accepts only valid transitions, prevents duplicate start transitions, and does not mark the guest running until its readiness transition; unit tests cover normal boot/stop, failures/recovery, startup cancellation, and concurrent starts.
- Android NDK/CMake configuration packages a JNI smoke library for `arm64-v8a` and `x86_64`. It deliberately reports `QEMU_NOT_LINKED`; it does not start a VM.
- `scripts/fetch-android-qemu-source.sh` downloads the pinned QEMU 11.1.2 source archive, verifies its SHA-256, validates the archive layout, and extracts it without overwriting an existing destination. CI runs this fetch step and checks the extracted configure script; this fetch step does not build or link Android QEMU.
- AndroidX instrumentation test loads and calls the JNI library on the x86_64 emulator.
- CI extracts the debug APK native libraries and verifies both ELF machine types with the NDK `llvm-readelf`.
- CI extracts the debug APK native libraries, verifies both ELF machine types, and audits `DT_NEEDED` plus runtime search paths. The current smoke libraries have only Android system dependencies (`libm.so`, `libdl.so`, `libc.so`) and no RPATH/RUNPATH; this does not yet validate a QEMU library.
- Foreground service fails closed and does not launch a guest.
- Widget opens the activity; it does not start or stop a VM.
- Pure Kotlin session policy defines a 60-minute limit and a 20% battery threshold. Unit tests pass, but no service enforces the policy.
- Alpine ARM64 builder seeds trusted APK keys and package repositories, installs Alpine base/OpenRC and `linux-virt`, creates matching modules/initramfs, then writes a 512 MiB ext4 disk by default.
- Host QEMU harness supplies the Linux kernel, initramfs, ext4 root disk, and localhost-only port forwarding.
- CI starts Alpine's `busybox-extras httpd` inside the guest after bringing up `eth0` and obtaining a DHCP lease, then verifies the exact HTTP response through a QEMU host forward bound to `127.0.0.1`.
- CI checks shell syntax and ShellCheck, builds the guest image, boots it under AArch64 system emulation to a serial login prompt, then runs `uname -s` and reads the Alpine release from a temporary test shell.
- CI remounts the guest disk read-write, writes a marker, powers Alpine off, starts a fresh QEMU process on the same ext4 disk, verifies the marker, then removes it.
- CI starts a real Alpine guest HTTP server, verifies the forwarded listener is bound only to `127.0.0.1`, and checks the exact HTTP response.
- Android CI provisions Gradle 8.9 because the repository has no Gradle wrapper.

## Outstanding gaps

- No Android-compatible QEMU executable, native process supervisor, serial bridge, or integrated terminal exists. Android packaging constraints, the pinned Termux QEMU 11.1.2 compatibility reference, and the next JNI-native build proof gates are documented in [ANDROID_QEMU_INTEGRATION.md](ANDROID_QEMU_INTEGRATION.md).
- Normal interactive login with a configured guest account and package installation remain unverified; only `uname -s` and reading `/etc/alpine-release` have been exercised in the temporary test shell.
- Host-side CI verifies guest poweroff and ext4 persistence across a QEMU restart, plus localhost HTTP forwarding from a guest service. Android VM process shutdown and Android-side localhost forwarding remain unverified.
- Android lifecycle, widget actions, timer enforcement, battery response, notifications, and recovery are not implemented.
- Software-emulation performance and Android process restrictions have not been tested on a physical ARM64 device.

## CI issues fixed

- Removed the obsolete SDK `tools` package installation and used the hosted SDK manager path explicitly.
- Rewrote image-size validation to satisfy ShellCheck.
- Added APK repositories and trusted keys inside the guest root.
- Replaced the unavailable `busybox-initscripts` package with Alpine's `busybox-openrc`.
- Added Alpine `busybox-extras` because the base BusyBox build does not include `httpd`; raised the guest `eth0` interface before DHCP in the networking probe.
- Added `linux-virt`, its initramfs, and `rootfstype=ext4` to the host boot path.
- Assigned the generated disk to the invoking host user so QEMU can open it.
- Added Android SDK tools to the emulator `PATH`, pinned `ANDROID_AVD_HOME`, and granted the hosted runner access to `/dev/kvm`.
- Bounded emulator discovery and boot waits so failures print diagnostics rather than leaving the step blocked indefinitely.

## Next engineering tasks

1. Build a pinned Android NDK QEMU `aarch64-softmmu` shared-library artifact using the minimal required headless devices and compatibility patches; verify its ABI and dynamic dependencies.
2. Prove a JNI entry point can start, supervise, and stop the QEMU main loop inside a dedicated Android process.
3. Install on an ARM64 Android device and boot Alpine through the Android native engine.
4. Add safe first-run guest account setup, then test interactive login and package installation; verify disk persistence through the Android VM lifecycle.
