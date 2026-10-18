# Android QEMU integration

Status: Android ARM64 QEMU and Alpine guest assets are built and packaged. The app-private installer is unit-tested. Android-side QEMU execution, guest boot, and service integration remain unverified; the VM service remains fail-closed.

## Verified build and package boundary

CI run #125 passed the pinned QEMU source fetch, Android ARM64 cross-build, APK assembly, native dependency audit, QMP instrumentation, and host Alpine guest gates.

The cross-build downloads QEMU 11.1.2 from the pinned upstream archive and verifies SHA-256 `731b5681e4bb18be313231579b8efd0296c5b015fa36dc533874b639ba838016`. It builds checksum-pinned libiconv, libffi, PCRE2, GLib, pixman, and libslirp static dependencies. A Bionic compatibility shim provides QEMU's temporary POSIX shared-memory file descriptor using an immediately unlinked file in its working directory.

The APK contains an Android AArch64 PIE staged as `lib/arm64-v8a/libqemu-system-aarch64.so`. CI checks ELF machine, PIE type, `/system/bin/linker64`, required system libraries, and absence of RPATH/RUNPATH.

The APK also contains the Alpine `vmlinuz-virt`, matching `initramfs-virt`, gzip-compressed ext4 seed disk, and SHA-256 manifest. `GuestAssetInstaller` verifies those hashes, stores versioned boot assets under app-private storage, atomically expands and validates the ext4 seed disk, and does not replace an existing disk on repeat install. JVM unit tests verify successful initialization, repeat-install preservation, and rejection of a boot asset hash mismatch.

These checks prove cross-build, packaging, and installer behavior only. The CI emulator is x86_64 and cannot execute ARM64 QEMU. No ARM64 device test or Android guest boot has been performed.

## Existing Android boundary tests

- CI packages PIE probes for arm64-v8a and x86_64; instrumentation launches the x86_64 probe from the installed app's native-library directory.
- NativeProcessSupervisor separates serial stdout and diagnostic stderr, writes input, waits for exit, and supports force termination as a fallback.
- QmpClient passes a QMP capabilities and quit exchange over an Android local socket.
- QemuLaunchConfig unit tests cover virt TCG, kernel/initramfs, persistent VirtIO block, serial, QMP, and a localhost-only HTTP forward.
- None of these tests boots QEMU or Alpine inside Android.

## Runtime integration constraints

The service must run in the dedicated `:vm` process as a foreground service while the guest is active; launch QEMU from the APK-extracted native-library directory; use app-private working storage and the persistent guest disk; keep serial and diagnostics separate; request graceful QMP shutdown before bounded force termination; and keep localhost forwarding bound to loopback by default.

The launch specification accepts kernel, initramfs, and disk paths. The installer now supplies verified paths, but no service consumes them yet. QEMU must remain disabled until ARM64 Android execution and guest readiness are verified.

## Next proof gates

1. Run QEMU on ARM64 Android and capture version/help output and diagnostics.
2. Boot the packaged Alpine kernel/initramfs and reach a real guest readiness signal.
3. Bridge the serial console, verify login and package installation, and confirm disk persistence.
4. Request QMP shutdown from ServerService and verify QEMU exits before releasing disk resources.
5. Enforce session timer and battery policy, then test physical ARM64 devices and record boot time, memory, battery impact, and crash diagnostics.

Until ARM64 execution and guest boot pass, PocketOS must describe Android Linux startup as unimplemented.
