# Android QEMU integration

Status: Android ARM64 QEMU and Alpine guest assets are built and packaged. The app-private installer and session-protection primitives are unit-tested. Android-side QEMU execution, guest boot, and service integration remain unverified; the VM service remains fail-closed.

## Verified build and package boundary

CI run #127 passed the pinned QEMU source fetch, Android ARM64 cross-build, APK assembly, native dependency audit, Android unit and x86_64 instrumentation tests, and host Alpine guest gates.

The cross-build downloads QEMU 11.1.2 from the pinned upstream archive and verifies SHA-256 `731b5681e4bb18be313231579b8efd0296c5b015fa36dc533874b639ba838016`. It builds checksum-pinned libiconv, libffi, PCRE2, GLib, pixman, and libslirp static dependencies. A Bionic compatibility shim provides QEMU's temporary POSIX shared-memory file descriptor using an immediately unlinked file in its working directory.

The APK contains an Android AArch64 PIE staged as `lib/arm64-v8a/libqemu-system-aarch64.so`. CI checks ELF machine, PIE type, `/system/bin/linker64`, required system libraries, and absence of RPATH/RUNPATH.

The APK also contains Alpine's `vmlinuz-virt`, matching `initramfs-virt`, gzip-compressed ext4 seed disk, and SHA-256 manifest. `GuestAssetInstaller` verifies hashes, stores versioned boot assets under app-private storage, atomically expands and validates the ext4 seed disk, and preserves an existing disk on repeat install. JVM tests cover hash rejection and persistent data preservation.

## Session protection boundary

`SessionProtection` stores a monotonic elapsed-realtime start and deadline through a `SessionCheckpointStore`. The SharedPreferences implementation commits the deadline before use, allowing recovery after service-process recreation without wall-clock drift. A checkpoint from before device reboot expires instead of granting a fresh hour. The policy retains the 60-minute default, 20% battery cutoff, and no-stop-on-Wi-Fi-disconnect behavior.

`BatteryLevelMonitor` listens for Android's sticky `ACTION_BATTERY_CHANGED` broadcast and subsequent battery events; it does not poll. Unit tests cover low-battery start rejection, deadline expiry, restoration after process recreation, reboot detection, and checkpoint clearing.

These are tested reusable components only. `ServerService` still does not call the installer, session policy, battery monitor, or QEMU process supervisor.

## Existing Android boundary tests

- CI packages PIE probes for arm64-v8a and x86_64; instrumentation launches the x86_64 probe from the installed app's native-library directory.
- NativeProcessSupervisor separates serial stdout and diagnostic stderr, writes input, waits for exit, and supports force termination as a fallback.
- QmpClient passes a QMP capabilities and quit exchange over an Android local socket.
- QemuLaunchConfig unit tests cover virt TCG, kernel/initramfs, persistent VirtIO block, serial, QMP, and a localhost-only HTTP forward.
- None of these tests boots QEMU or Alpine inside Android.

## Runtime integration constraints

The service must run in the dedicated `:vm` process as a foreground service while the guest is active; launch QEMU from the APK-extracted native-library directory; use app-private working storage and the persistent guest disk; keep serial and diagnostics separate; request graceful QMP shutdown before bounded force termination; and bind localhost forwarding to loopback by default.

The service may transition to RUNNING only after a real guest-ready signal. The 60-minute deadline begins at that transition. Low battery must block startup and request shutdown at or below 20%. ARM64 Android execution, secure guest login, and QMP shutdown remain unverified, so service startup stays disabled.

## Next proof gates

1. Wire the installer, process supervisor, battery monitor, and deadline store into foreground-service lifecycle tests.
2. Run QEMU on ARM64 Android and capture version/help output and diagnostics.
3. Boot the packaged Alpine kernel/initramfs and reach a real guest readiness signal.
4. Bridge the serial console, verify secure login and package installation, and confirm persistence.
5. Verify QMP shutdown, timer cutoff, and battery cutoff before testing physical devices, performance, and crash recovery.

Until ARM64 execution and guest boot pass, PocketOS must describe Android Linux startup as unimplemented.
