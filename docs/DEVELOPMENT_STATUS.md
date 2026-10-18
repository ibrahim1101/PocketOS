# PocketOS development status

Updated: 2026-10-18

## Current state

- Active milestone: M1 guest-image validation.
- Active development branch: `feat/m1-linux-boot`.
- Pull request: [#1 — M1: Start Alpine ARM64 guest image builder](https://github.com/ibrahim1101/PocketOS/pull/1) (open).
- Latest verified revision: `39cca67e43aa00a9704196bea4ef1052817c5593`.
- GitHub Actions run [#14](https://github.com/ibrahim1101/PocketOS/actions/runs/39132816032) passed both jobs: Android unit tests/debug APK assembly and the Alpine ARM64 disk builder.
- The Android APK was assembled in CI; device installation and runtime behavior were not tested.
- The ARM64 Alpine ext4 image was built in CI; it has not been booted.

## Implemented source

- Android activity displays development status and states that the VM engine is not implemented.
- Foreground service fails closed and does not launch a guest.
- Widget opens the activity; it does not start or stop a VM.
- Pure Kotlin session policy defines a 60-minute limit and a 20% battery threshold. Unit tests pass, but no service enforces the policy.
- Host QEMU boot harness accepts a supplied ARM64 kernel and ext4 root filesystem.
- Alpine ARM64 image builder seeds APK repositories and signing keys, installs Alpine base/OpenRC packages, and creates an ext4 disk.
- CI runs Kotlin unit tests, assembles a debug APK, checks shell syntax and ShellCheck, and builds a 256 MiB ARM64 disk using QEMU user emulation.
- The Android Gradle project has no committed Gradle wrapper; CI provisions Gradle 8.9.

## Audit findings and blockers

- No Android-compatible QEMU executable, native process supervisor, serial bridge, or terminal exists.
- No QEMU-virt-compatible ARM64 guest kernel has been selected or booted.
- No persistent disk restart test or guest network test exists.
- Android lifecycle, widget actions, timer, battery response, and notifications are not implemented.
- The APK has not been installed or exercised on an emulator or physical device.
- Architectural risks include software-emulation performance and Android process restrictions; neither has been tested on a physical ARM64 device.

## CI failures fixed during this update

- Removed the setup action's obsolete SDK `tools` package request and used the hosted SDK manager directly.
- Made the SDK manager path explicit because it was not on the runner PATH.
- Rewrote size validation to satisfy ShellCheck.
- Seeded repository configuration and trusted package keys inside the guest root.
- Replaced the unavailable `busybox-initscripts` package with Alpine's current `busybox-openrc` package.
- The latest CI run passed after these fixes.

## Next engineering tasks

1. Inspect the generated ext4 image contents and verify console/login setup.
2. Obtain a QEMU-virt-compatible ARM64 kernel from a reproducible source.
3. Boot the Alpine disk through host QEMU and record serial-console evidence.
4. Verify clean shutdown and persistence across a restart.
5. Evaluate Android-compatible QEMU packaging only after the host guest boot is proven.
