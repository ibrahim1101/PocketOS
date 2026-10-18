# PocketOS development status

Updated: 2026-10-18

## Current state

- Active milestone: M2 — Android ARM64 QEMU build, guest asset installation, and service integration.
- Active branch: `feat/m1-linux-boot`; PR #1 remains open.
- Latest verified revision: `5208f76c42252ea420f450d5273134bbee804f4a`.
- CI run #130 passed Android QEMU cross-build and APK audit, JVM unit tests and x86_64 bridge instrumentation, and the Alpine ARM64 boot, shell, persistence, and localhost HTTP checks.
- The APK contains QEMU 11.1.2 plus Alpine's kernel, matching initramfs, compressed ext4 seed disk, and SHA-256 manifest.
- `GuestAssetInstaller` validates packaged hashes and ext4 format, stages versioned boot assets, atomically expands the seed disk to app-private storage, and preserves the disk on repeat installs. A `forContext` factory reads assets from the packaged APK.
- `GuestReadinessProbe` detects the complete Alpine serial login prompt across chunks and ANSI output; process creation alone does not indicate readiness.
- `SessionProtection` persists an elapsed-realtime deadline, starts only when called after readiness, keeps the same deadline on recovery, expires stale pre-reboot checkpoints, and applies the 20% battery cutoff. `BatteryLevelMonitor` is broadcast-driven. Unit tests cover these cases.
- The CI emulator is x86_64 and cannot execute ARM64 QEMU. Android QEMU execution, guest boot, interactive guest login, or physical ARM64 device testing have not been performed. ServerService remains fail-closed and does not yet use these components.

## Implemented and verified

- Kotlin VM state machine prevents invalid and duplicate starts; tests cover boot, stop, failure, cancellation, and concurrency.
- NativeProcessSupervisor provides separate serial and diagnostic streams, stdin, exit waiting, and force-stop fallback.
- QmpClient negotiates QMP and requests graceful shutdown; Android local-socket instrumentation passes.
- QemuLaunchConfig describes ARM virt TCG, kernel/initramfs, persistent VirtIO block, serial stdio, QMP, and loopback-only HTTP forwarding. It has not been run with Android QEMU.
- CI cross-builds pinned QEMU 11.1.2 for arm64-v8a with checksum-pinned dependencies and audits the packaged ELF type, linker, and runtime dependencies.
- CI boots Alpine under host QEMU, runs Linux shell commands, checks ext4 persistence across restart, and verifies localhost HTTP forwarding.
- Guest asset installation, readiness detection, and session protection are tested standalone; Android service-level startup and enforcement remain unimplemented.
- The activity and widget do not start or stop a VM.

## Outstanding gaps

- Wire installer, process supervisor, readiness probe, serial terminal, QMP shutdown, bounded recovery, session deadline, and battery monitor into the foreground service.
- Execute ARM64 QEMU on an ARM64 Android device or emulator and boot Alpine to the login prompt.
- Add dashboard controls, terminal, notifications, widget actions, diagnostics, and shutdown reason reporting.
- Implement secure guest account setup, package installation, storage controls, and Android-side localhost access.
- Measure boot time, RAM, and battery impact on physical ARM64 devices; LAN exposure and custom kernels remain later work.

## Next engineering tasks

1. Integrate tested primitives into the service while preserving the guest-ready state boundary and 60-minute/20% policies.
2. Run QEMU and boot Alpine on an ARM64 Android target.
3. Build interactive terminal, secure first-run account setup, and localhost access.
