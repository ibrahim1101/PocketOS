# PocketOS development status

Updated: 2026-10-18

## Current state

- Active milestone: M2 — Android ARM64 QEMU build, packaging, and guest asset installation.
- Active branch: `feat/m1-linux-boot`; PR #1 remains open.
- Latest verified revision: `f8569460dd3aa4d4c9b4dfdb632b6feff989023d`.
- CI run #125 passed Android QEMU cross-build and APK verification, JVM unit tests, Android x86_64 bridge instrumentation, and Alpine ARM64 guest boot, shell, persistence, and localhost HTTP checks.
- The APK includes QEMU 11.1.2 for Android ARM64 plus Alpine kernel, initramfs, compressed ext4 seed disk, and SHA-256 manifest.
- `GuestAssetInstaller` validates the packaged hashes, atomically stages versioned boot assets and the expanded seed disk into app-private storage, checks the ext4 signature, and preserves the disk on repeat installs. Unit tests cover hash rejection and repeat-install persistence.
- Android x86_64 instrumentation validates native process and QMP socket boundaries. The emulator cannot execute the ARM64 QEMU binary.
- No Android QEMU execution, guest boot, interactive guest login, or physical ARM64 device test has been performed. ServerService remains fail-closed.

## Implemented and verified

- Kotlin VM state machine prevents invalid and duplicate starts; unit tests cover boot, stop, failure, cancellation, and concurrency.
- NativeProcessSupervisor provides a child-process boundary with separate serial and diagnostic streams, stdin, exit waiting, and force-stop fallback.
- QmpClient negotiates QMP and requests graceful shutdown; Android local-socket protocol instrumentation passes.
- QemuLaunchConfig describes ARM virt TCG, kernel/initramfs, persistent VirtIO block, serial stdio, QMP, and loopback-only HTTP forwarding. It has not been run with Android QEMU.
- CI cross-builds pinned QEMU 11.1.2 for Android arm64-v8a with checksum-pinned static dependencies; APK checks validate ELF architecture, linker, and runtime dependencies.
- CI packages Alpine kernel/initramfs and compressed ext4 seed image; host QEMU CI boots Alpine, runs Linux commands, checks ext4 persistence, and tests localhost HTTP forwarding.
- SessionPolicy defines a 60-minute limit and 20% battery cutoff, but ServerService does not enforce them.
- The activity and widget do not start or stop a VM.

## Outstanding gaps

- Integrate the asset installer, process supervision, serial terminal, QMP shutdown, bounded force-stop recovery, and crash handling into the foreground service after ARM64 Android execution is available for validation.
- Run ARM64 QEMU on an ARM64 Android device or emulator and boot Alpine.
- Add the 60-minute monotonic deadline and 20% battery cutoff, deadline recovery, notifications, widget actions, and diagnostics.
- Implement guest account setup, interactive login, package installation, storage management, and Android-side localhost access.
- Measure TCG boot time, RAM, and battery impact on physical ARM64 phones; LAN exposure and custom kernels remain later work.

## Next engineering tasks

1. Add service-owned timer and battery enforcement with unit-tested recovery logic.
2. Validate Android QEMU execution and Alpine readiness on an ARM64 Android target.
3. Integrate service lifecycle only after the native guest boundary can be exercised safely.
4. Add interactive terminal, secure guest setup, and localhost access.
