# PocketOS development status

Updated: 2026-10-18

## Current state

- Active milestone: M2 — Android ARM64 QEMU build, packaging, guest asset installation, and service integration.
- Active branch: `feat/m1-linux-boot`; PR #1 remains open.
- Latest verified revision: `a4ca53bc4e798ac9330ac75f392dced588a5c01a`.
- CI run #127 passed the Android ARM64 QEMU cross-build and APK audit, Android JVM unit tests and x86_64 bridge instrumentation, and the Alpine ARM64 boot, shell, persistence, and localhost HTTP checks.
- The APK contains QEMU 11.1.2 plus the Alpine kernel, matching initramfs, compressed ext4 seed disk, and SHA-256 manifest.
- `GuestAssetInstaller` validates packaged hashes, stages versioned boot assets, atomically expands and validates the seed disk in app-private storage, and preserves the disk on repeat installs.
- `SessionProtection` persists a monotonic deadline, starts only when called after guest readiness, detects a prior-boot checkpoint, applies the 20% battery cutoff, and exposes a broadcast-driven battery monitor. Unit tests cover cutoff, expiry, process recreation, and reboot recovery.
- The CI emulator is x86_64 and cannot execute ARM64 QEMU. No Android QEMU execution, guest boot, interactive guest login, or physical ARM64 device test has been performed. ServerService remains fail-closed and does not yet use the installer or session policy.

## Implemented and verified

- Kotlin VM state machine prevents invalid and duplicate starts; tests cover boot, stop, failure, cancellation, and concurrency.
- NativeProcessSupervisor provides separate serial and diagnostic streams, stdin, exit waiting, and force-stop fallback.
- QmpClient negotiates QMP and requests graceful shutdown; Android local-socket instrumentation passes.
- QemuLaunchConfig describes ARM virt TCG, kernel/initramfs, persistent VirtIO block, serial stdio, QMP, and loopback-only HTTP forwarding. It has not been run with Android QEMU.
- CI cross-builds pinned QEMU 11.1.2 for arm64-v8a with checksum-pinned static dependencies and checks the packaged ELF type, linker, and runtime dependencies.
- CI boots Alpine under host QEMU, runs Linux shell commands, checks ext4 persistence across restart, and verifies localhost HTTP forwarding.
- Session protection and guest asset installation are verified as standalone components; service-level startup and enforcement remain unimplemented.
- The activity and widget do not start or stop a VM.

## Outstanding gaps

- Wire asset installation, process supervision, serial terminal, QMP shutdown, bounded recovery, session deadline, and battery monitor into the foreground service.
- Execute ARM64 QEMU on an ARM64 Android device or emulator and boot Alpine to a real readiness signal.
- Add user-visible dashboard controls, terminal, notifications, widget actions, diagnostics, and shutdown reason reporting.
- Implement secure guest account setup, package installation, storage controls, and Android-side localhost access.
- Measure boot time, RAM, and battery impact on physical ARM64 devices; LAN exposure and custom kernels remain later work.

## Next engineering tasks

1. Integrate the now-tested asset and session primitives into a service lifecycle while keeping startup gated on a verified guest-ready signal.
2. Run QEMU and boot Alpine on an ARM64 Android target.
3. Build terminal, secure first-run account setup, and local port access.
