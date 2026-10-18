# PocketOS development status

Updated: 2026-10-18

## Current state

- Active milestone: M2 — Android ARM64 QEMU build, guest asset installation, and service integration.
- Active branch: `feat/m1-linux-boot`; PR #1 remains open.
- Latest fully passing CI revision: `9bc8f46cd0575fa9aa3ce83ac9885c808a4f078d` (run #138).
- On revision `b1a476dd855fb31460953faa0b577d9e8a0aced4`, CI run #141 passed the Android build and x86_64 instrumentation plus the Alpine ARM64 guest boot, shell, persistence, and localhost HTTP checks. Its optional ARM64 Android runtime job could not start because the SDK manager on `ubuntu-24.04-arm` has no `emulator` package.
- The APK contains QEMU 11.1.2 plus Alpine's kernel, matching initramfs, compressed ext4 seed disk, and SHA-256 manifest.
- `GuestAssetInstaller` verifies packaged hashes and ext4 format, stages versioned boot assets, atomically expands the seed disk to app-private storage, preserves it across repeat installs, and has an app-context factory. Android instrumentation installs the real APK assets, checks the 256 MiB ext4 disk, and verifies repeat-install preservation.
- `GuestDiskLease` obtains an exclusive OS file lock beside the guest disk. Unit and Android instrumentation tests reject a concurrent writer and verify the lease can be acquired after release.
- `GuestReadinessProbe` detects Alpine's complete serial login prompt across chunks and ANSI output.
- `SessionProtection` persists an elapsed-realtime deadline, preserves it through process recovery, expires pre-reboot checkpoints, and applies the 20% battery cutoff. `BatteryLevelMonitor` uses broadcasts without polling.
- `GuestBootCoordinator` ties readiness to the VM state machine: process start reaches BOOTING, while RUNNING requires both the login prompt and a saved deadline. It rejects low-battery readiness and fails on unexpected process exit.
- `QemuAndroidRuntimeInstrumentationTest` checks the app's actual installed native-library ABI and runs QEMU `--version` when the app is installed as ARM64. The regular x86_64 CI emulator skips that test. We attempted a Linux ARM64 Android emulator job, but Google’s stable SDK repository did not provide the Linux ARM64 `emulator` package (`sdkmanager` returned `Failed to find package 'emulator'`), so that job was removed. No Android QEMU execution, Android guest boot, interactive guest login, or physical ARM64 device test has been performed. `ServerService` remains fail-closed and does not yet use the coordinator.

## Implemented and verified

- Kotlin VM state machine prevents invalid and duplicate starts; tests cover boot, stop, failure, cancellation, and concurrency.
- NativeProcessSupervisor provides separate serial and diagnostic streams, stdin, exit waiting, and force-stop fallback.
- QmpClient negotiates QMP and requests graceful shutdown; Android local-socket instrumentation passes.
- QemuLaunchConfig describes ARM virt TCG, kernel/initramfs, persistent VirtIO block, serial stdio, QMP, and loopback-only HTTP forwarding. It has not been run with Android QEMU.
- CI cross-builds pinned QEMU 11.1.2 for arm64-v8a with checksum-pinned dependencies and audits packaged ELF architecture, linker, and runtime dependencies.
- CI boots Alpine under host QEMU, runs Linux shell commands, verifies ext4 persistence across restart, and tests localhost HTTP forwarding.
- Installer, readiness, session protection, and lifecycle coordination are standalone tested components; Android service-level startup and policy enforcement remain unimplemented.
- The activity and widget do not start or stop a VM.

## Outstanding gaps

- Wire the tested components, process supervisor, serial terminal, QMP shutdown, bounded recovery, timer, and battery monitor into the foreground service.
- Execute ARM64 QEMU on an ARM64 Android device or emulator and boot Alpine to the login prompt.
- Add dashboard controls, terminal, notifications, widget actions, diagnostics, and shutdown reason reporting.
- Implement secure guest account setup, package installation, storage controls, and Android-side localhost access.
- Measure boot time, RAM, and battery impact on physical ARM64 devices; LAN exposure and custom kernels remain later work.

## Next engineering tasks

1. Integrate the tested coordinator and process boundary into service lifecycle tests while preserving fail-closed startup until ARM64 validation.
2. Run `QemuAndroidRuntimeInstrumentationTest` on a physical ARM64 Android device or a Linux ARM64 host that has a supported Android emulator binary; then attempt guest boot on that target.
3. Wire up `ServerService`, then build an interactive terminal, secure first-run account setup, and localhost access.
