# PocketOS development status

Updated: 2026-10-18

## Current state

- Active milestone: M2 — Android-native QEMU integration investigation.
- Active development branch: `feat/m1-linux-boot`.
- Pull request: [#1 — M1: Start Alpine ARM64 guest image builder](https://github.com/ibrahim1101/PocketOS/pull/1) (open).
- Latest verified code revision: `5bc7fb57ab9e00fd39dc4cfd227e9278d6490605`.
- GitHub Actions run [#39](https://github.com/ibrahim1101/PocketOS/actions/runs/39138074110) passed Android unit tests/debug APK assembly, serial boot, and the guest command probe.
- CI built the ext4 image, extracted the matching kernel and initramfs, booted them with system QEMU, mounted the VirtIO ext4 disk, and observed the `pocketos login:` prompt on PL011 serial.
- A separate test boot overrides init with `/bin/sh`; it waits for the guest shell prompt, runs `uname -s`, and reads `/etc/alpine-release` (`3.22.6`). This verifies guest command execution without enabling the locked root account for a normal boot.
- The APK was assembled, but not installed on an emulator or device.

## Implemented and verified

- Android activity displays development status and explicitly says the VM engine is not implemented.
- Pure Kotlin VM lifecycle state machine accepts only valid transitions, prevents duplicate start transitions, and does not mark the guest running until its readiness transition; unit tests cover normal boot/stop, failures/recovery, startup cancellation, and concurrent starts.
- Foreground service fails closed and does not launch a guest.
- Widget opens the activity; it does not start or stop a VM.
- Pure Kotlin session policy defines a 60-minute limit and a 20% battery threshold. Unit tests pass, but no service enforces the policy.
- Alpine ARM64 builder seeds trusted APK keys and package repositories, installs Alpine base/OpenRC and `linux-virt`, creates matching modules/initramfs, then writes a 512 MiB ext4 disk by default.
- Host QEMU harness supplies the Linux kernel, initramfs, ext4 root disk, and localhost-only port forwarding.
- CI checks shell syntax and ShellCheck, builds the guest image, boots it under AArch64 system emulation to a serial login prompt, then runs `uname -s` and reads the Alpine release from a temporary test shell.
- Android CI provisions Gradle 8.9 because the repository has no Gradle wrapper.

## Outstanding gaps

- No Android-compatible QEMU executable, native process supervisor, serial bridge, or integrated terminal exists. Android packaging constraints and the first native integration proof gates are documented in [ANDROID_QEMU_INTEGRATION.md](ANDROID_QEMU_INTEGRATION.md).
- Normal interactive login with a configured guest account and package installation remain unverified; only `uname -s` and reading `/etc/alpine-release` have been exercised in the temporary test shell.
- No clean shutdown, persistence-across-restart, or HTTP forwarding smoke test exists.
- Android lifecycle, widget actions, timer enforcement, battery response, notifications, and recovery are not implemented.
- The APK has not been installed or exercised on an emulator or physical device.
- Software-emulation performance and Android process restrictions have not been tested on a physical ARM64 device.

## CI issues fixed

- Removed the obsolete SDK `tools` package installation and used the hosted SDK manager path explicitly.
- Rewrote image-size validation to satisfy ShellCheck.
- Added APK repositories and trusted keys inside the guest root.
- Replaced the unavailable `busybox-initscripts` package with Alpine's `busybox-openrc`.
- Added `linux-virt`, its initramfs, and `rootfstype=ext4` to the host boot path.
- Assigned the generated disk to the invoking host user so QEMU can open it.

## Next engineering tasks

1. Build a pinned Android NDK QEMU `aarch64-softmmu` artifact with only the required headless dependencies; verify its ABI and linkage.
2. Prove a JNI entry point can start, supervise, and stop the QEMU main loop inside a dedicated Android process.
3. Install on an ARM64 Android emulator/device and boot Alpine through the Android native engine.
4. Add safe first-run guest account setup, then test package installation and persistent restart behavior.
5. Verify localhost HTTP forwarding from a real guest service.
