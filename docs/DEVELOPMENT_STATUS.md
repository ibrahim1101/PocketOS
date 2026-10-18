# PocketOS development status

Updated: 2026-10-18

## Current state

- Active milestone: M1 — real Linux guest boot proof.
- Active development branch: `feat/m1-linux-boot`.
- Pull request: [#1 — M1: Start Alpine ARM64 guest image builder](https://github.com/ibrahim1101/PocketOS/pull/1) (open).
- Latest verified source revision: `789133f7ca8a429513f77cbc66c81f5fdfa7a732`.
- GitHub Actions run [#28](https://github.com/ibrahim1101/PocketOS/actions/runs/39134473097) passed Android unit tests/debug APK assembly and Alpine ARM64 guest boot.
- CI built the ext4 image, extracted the matching kernel and initramfs, booted them with system QEMU, mounted the VirtIO ext4 disk, and observed the `pocketos login:` prompt on PL011 serial.
- The APK was assembled, but not installed on an emulator or device.
- Guest shell login and Linux command execution are not yet verified; the image keeps the root account locked.

## Implemented and verified

- Android activity displays development status and explicitly says the VM engine is not implemented.
- Foreground service fails closed and does not launch a guest.
- Widget opens the activity; it does not start or stop a VM.
- Pure Kotlin session policy defines a 60-minute limit and a 20% battery threshold. Unit tests pass, but no service enforces the policy.
- Alpine ARM64 builder seeds trusted APK keys and package repositories, installs Alpine base/OpenRC and `linux-virt`, creates matching modules/initramfs, then writes a 512 MiB ext4 disk by default.
- Host QEMU harness supplies the Linux kernel, initramfs, ext4 root disk, and localhost-only port forwarding.
- CI checks shell syntax and ShellCheck, builds the guest image, and boots it under AArch64 system emulation to a serial login prompt.
- Android CI provisions Gradle 8.9 because the repository has no Gradle wrapper.

## Outstanding gaps

- No Android-compatible QEMU executable, native process supervisor, serial bridge, or integrated terminal exists.
- Interactive shell login, package installation, and guest command execution remain unverified.
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

1. Design a safe first-run guest account/password setup, then verify interactive serial login and commands such as `uname -a` and `apk add`.
2. Add clean shutdown and restart tests that confirm a guest file persists.
3. Verify localhost HTTP forwarding with a real guest server.
4. Evaluate Android-compatible QEMU packaging after these host guest checks.
