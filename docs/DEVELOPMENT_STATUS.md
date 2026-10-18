# PocketOS development status

Updated: 2026-10-18

## Current state

- Active milestone: M2 — Android-native QEMU integration investigation.
- Active development branch: `feat/m1-linux-boot`.
- Pull request: [#1 — M1: Start Alpine ARM64 guest image builder](https://github.com/ibrahim1101/PocketOS/pull/1) (open).
- Latest verified code revision: `7285061399c0746e9a8c812cb3802449ec5a3669`.
- CI run [#67](https://github.com/ibrahim1101/PocketOS/actions/runs/39145289175) passed Android unit tests, APK assembly, JNI instrumentation on an x86_64 emulator, ELF ABI checks for both packaged native libraries, and the Alpine ARM64 guest boot, command, and disk-persistence probes. Earlier runs [#59](https://github.com/ibrahim1101/PocketOS/actions/runs/39143983887) and [#60](https://github.com/ibrahim1101/PocketOS/actions/runs/39144008484) also passed their recorded Android and guest checks.
- The instrumentation test installed the app and test APK on the emulator and confirmed the embedded native library loads and returns `JNI_BRIDGE_READY;QEMU_NOT_LINKED`. This validates the JNI packaging boundary only; it does not run QEMU.
- The APK audit extracts the JNI libraries and confirms their ELF machines are `AArch64` and `Advanced Micro Devices X86-64`.
- CI built the ext4 image, extracted the matching kernel and initramfs, booted them with system QEMU, mounted the VirtIO ext4 disk, and observed the `pocketos login:` prompt on PL011 serial.
- A separate test boot overrides init with `/bin/sh`; it waits for the guest shell prompt, runs `uname -s`, and reads `/etc/alpine-release` (`3.22.6`). This verifies guest command execution without enabling the locked root account for a normal boot.
- The debug APK and JNI library have been tested on an x86_64 Android emulator; no physical ARM64 device test has been performed.

## Implemented and verified

- Android activity displays development status and explicitly says the VM engine is not implemented.
- Pure Kotlin VM lifecycle state machine accepts only valid transitions, prevents duplicate start transitions, and does not mark the guest running until its readiness transition; unit tests cover normal boot/stop, failures/recovery, startup cancellation, and concurrent starts.
- Android NDK/CMake configuration packages a JNI smoke library for `arm64-v8a` and `x86_64`. It deliberately reports `QEMU_NOT_LINKED`; it does not start a VM.
- AndroidX instrumentation test loads and calls the JNI library on the x86_64 emulator.
- CI extracts the debug APK native libraries and verifies both ELF machine types with the NDK `llvm-readelf`.
- Foreground service fails closed and does not launch a guest.
- Widget opens the activity; it does not start or stop a VM.
- Pure Kotlin session policy defines a 60-minute limit and a 20% battery threshold. Unit tests pass, but no service enforces the policy.
- Alpine ARM64 builder seeds trusted APK keys and package repositories, installs Alpine base/OpenRC and `linux-virt`, creates matching modules/initramfs, then writes a 512 MiB ext4 disk by default.
- Host QEMU harness supplies the Linux kernel, initramfs, ext4 root disk, and localhost-only port forwarding.
- CI checks shell syntax and ShellCheck, builds the guest image, boots it under AArch64 system emulation to a serial login prompt, then runs `uname -s` and reads the Alpine release from a temporary test shell.
- CI remounts the guest disk read-write, writes a marker, powers Alpine off, starts a fresh QEMU process on the same ext4 disk, verifies the marker, then removes it.
- Android CI provisions Gradle 8.9 because the repository has no Gradle wrapper.

## Outstanding gaps

- No Android-compatible QEMU executable, native process supervisor, serial bridge, or integrated terminal exists. Android packaging constraints, the pinned Termux QEMU 11.1.2 compatibility reference, and the next JNI-native build proof gates are documented in [ANDROID_QEMU_INTEGRATION.md](ANDROID_QEMU_INTEGRATION.md).
- Normal interactive login with a configured guest account and package installation remain unverified; only `uname -s` and reading `/etc/alpine-release` have been exercised in the temporary test shell.
- Host-side CI verifies guest poweroff and ext4 persistence across a QEMU restart. Android VM process shutdown and localhost HTTP forwarding remain unverified.
- Android lifecycle, widget actions, timer enforcement, battery response, notifications, and recovery are not implemented.
- Software-emulation performance and Android process restrictions have not been tested on a physical ARM64 device.

## CI issues fixed

- Removed the obsolete SDK `tools` package installation and used the hosted SDK manager path explicitly.
- Rewrote image-size validation to satisfy ShellCheck.
- Added APK repositories and trusted keys inside the guest root.
- Replaced the unavailable `busybox-initscripts` package with Alpine's `busybox-openrc`.
- Added `linux-virt`, its initramfs, and `rootfstype=ext4` to the host boot path.
- Assigned the generated disk to the invoking host user so QEMU can open it.
- Added Android SDK tools to the emulator `PATH`, pinned `ANDROID_AVD_HOME`, and granted the hosted runner access to `/dev/kvm`.
- Bounded emulator discovery and boot waits so failures print diagnostics rather than leaving the step blocked indefinitely.

## Next engineering tasks

1. Build a pinned Android NDK QEMU `aarch64-softmmu` shared-library artifact using the minimal required headless devices and compatibility patches; verify its ABI and dynamic dependencies.
2. Prove a JNI entry point can start, supervise, and stop the QEMU main loop inside a dedicated Android process.
3. Install on an ARM64 Android device and boot Alpine through the Android native engine.
4. Add safe first-run guest account setup, then test interactive login and package installation; verify disk persistence through the Android VM lifecycle.
5. Verify localhost HTTP forwarding from a real guest service.
