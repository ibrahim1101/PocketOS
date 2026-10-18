# PocketOS development status

Updated: 2026-10-18

## Current state

- Active milestone: M2 — Android ARM64 QEMU build and packaging.
- Active branch: feat/m1-linux-boot; PR #1 remains open.
- Latest verified revision: 104a7f1c600608f4faff82e67bda0384dbd480d6.
- CI run #120 passed the Android QEMU cross-build, APK assembly and executable-entry check, QMP instrumentation, and the Alpine ARM64 guest gates.
- The debug APK contains the pinned QEMU 11.1.2 ARM64 PIE executable. The build checks AArch64 ELF type, Android linker path, and absence of RPATH/RUNPATH. This revision expands the APK audit to check QEMU's runtime dependencies.
- QMP instrumentation passed a capabilities handshake and graceful quit exchange on an Android x86_64 emulator. That emulator cannot execute the ARM64 QEMU binary.
- Host QEMU CI boots Alpine 3.22.6, runs Linux commands, verifies ext4 persistence across restart, and tests HTTP forwarding bound to 127.0.0.1:18080.
- Android QEMU execution, guest boot, interactive login, and physical ARM64 device testing remain unverified. The service remains fail-closed.

## Implemented and verified

- Kotlin VM state machine prevents invalid and duplicate starts; unit tests cover boot, stop, failure, cancellation, and concurrency.
- NativeProcessSupervisor launches an APK-packaged executable with separate serial and diagnostic streams, stdin writing, exit waiting, and a force-stop fallback.
- QmpClient negotiates QMP and requests graceful shutdown; the Android local-socket protocol test passes.
- QemuLaunchConfig specifies ARM virt TCG, kernel/initramfs, persistent VirtIO disk, serial stdio, QMP, and loopback-only HTTP forwarding. QEMU has not yet been run with this configuration on Android.
- CI cross-builds pinned QEMU 11.1.2 for Android arm64-v8a with checksum-pinned static dependencies and packages the executable in the debug APK.
- Host CI verifies Alpine serial boot, Linux command execution, ext4 persistence, and localhost HTTP service forwarding.
- SessionPolicy defines a 60-minute limit and 20% battery cutoff, but ServerService does not enforce them.
- ServerService still fails closed. The activity and widget do not start or stop a VM.

## Outstanding gaps

- Integrate QEMU process supervision, validated guest assets, serial terminal streaming, QMP shutdown, bounded force-stop recovery, and crash handling into the foreground service.
- Execute the ARM64 QEMU binary on an ARM64 Android device or emulator and boot Alpine.
- Add real timer and battery enforcement, deadline recovery, notifications, widget actions, and diagnostics.
- Implement guest account setup, interactive login, package installation, storage management, and Android-side localhost access.
- Measure TCG boot time, RAM use, and battery impact on physical ARM64 phones; LAN exposure and custom kernels remain later work.

## Next engineering tasks

1. Enforce QEMU ELF and Android system dependency checks in the APK audit.
2. Integrate the verified process supervisor, QMP shutdown, and serial console into ServerService.
3. Add the 60-minute monotonic deadline and 20% battery cutoff to the service lifecycle.
4. Package the Alpine kernel/initramfs/disk, then test boot, login, storage persistence, and localhost access on ARM64 Android.
