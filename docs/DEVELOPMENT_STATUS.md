# PocketOS development status

Updated: 2026-10-18

## Current state

- Active milestone: M0 stabilization, with M1 guest-image work in progress.
- Active development branch: `feat/m1-linux-boot`.
- Pull request: [#1 — M1: Start Alpine ARM64 guest image builder](https://github.com/ibrahim1101/PocketOS/pull/1) (open).
- Latest verified repository revision before this update: `99b8319c3f2b61f61aa81c4b5a5b160bc77c46f5`.
- No GitHub Actions workflow runs were present during the initial audit.
- No APK build, Linux guest boot, disk-persistence test, or Android device test has been verified.

## Implemented source

- Android activity shows a development status screen and explicitly says the VM engine is not implemented.
- Foreground service fails closed and does not launch a guest.
- Widget opens the activity; it does not start or stop a VM.
- Pure Kotlin session policy defines a 60-minute limit and a 20% battery threshold. Unit tests cover the policy, but no service enforces it.
- Host QEMU boot harness accepts a supplied ARM64 kernel and ext4 root filesystem.
- Alpine ARM64 ext4 image builder and host prerequisite script are on PR #1.
- Android Gradle project and unit test sources exist, but the repository has no Gradle wrapper.

## Audit findings and blockers

- No Android-compatible QEMU executable, native process supervisor, serial bridge, or terminal exists.
- No guest kernel or Alpine disk has been built and booted in a verified environment.
- No persistent disk restart test or guest network test exists.
- Android lifecycle, widget actions, timer, battery response, and notifications are not implemented.
- The Android build has not been verified. The missing wrapper makes builds dependent on a locally installed Gradle unless CI provisions Gradle explicitly.
- There was no CI workflow before the current PR update.
- Architectural risks include software-emulation performance and Android process restrictions; neither has been tested on a physical ARM64 device.

## Work added in this update

- Added a GitHub Actions workflow to run Android unit tests and assemble a debug APK.
- Added guest-builder checks for shell syntax, ShellCheck, and an ARM64 Alpine image build under QEMU user emulation.
- These checks have not yet run; their outcome must be recorded after GitHub Actions reports it.

## Next engineering tasks

1. Inspect CI results and fix build or image-builder failures.
2. Verify the generated root filesystem contents and make its boot/login setup usable without insecure defaults.
3. Obtain a QEMU-virt-compatible ARM64 kernel from a reproducible source and boot the disk through host QEMU.
4. Add and pass a clean shutdown/restart persistence test.
5. Evaluate Android-compatible QEMU packaging only after the host guest boot is proven.
