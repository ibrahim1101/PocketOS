# Android QEMU integration

Status: CI has built and packaged QEMU 11.1.2 for Android arm64-v8a. Android-side execution, guest boot, and service integration are unverified, so the VM service remains fail-closed.

## Verified build and package boundary

CI run #120 passed the pinned source fetch, Android ARM64 cross-build, APK assembly, executable-entry check, QMP instrumentation, and host Alpine guest gates.

The cross-build downloads QEMU 11.1.2 from the pinned upstream archive and verifies SHA-256 731b5681e4bb18be313231579b8efd0296c5b015fa36dc533874b639ba838016. It builds checksum-pinned libiconv, libffi, PCRE2, GLib, pixman, and libslirp static dependencies. A small Bionic compatibility shim provides QEMU's temporary POSIX shared-memory file descriptor using an immediately unlinked file in its working directory.

The result is an Android AArch64 PIE staged as lib/arm64-v8a/libqemu-system-aarch64.so for APK extraction. The build checks ELF machine, PIE type, /system/bin/linker64, and absence of RPATH/RUNPATH. Run #120 confirms APK presence. This revision adds a complete APK audit for the QEMU ELF and runtime dependencies.

These checks prove build and packaging only. The CI emulator is x86_64 and cannot execute the ARM64 QEMU binary. No ARM64 device test or Android guest boot has been performed.

## Existing Android boundary tests

- CI packages PIE probes for arm64-v8a and x86_64; instrumentation launches the x86_64 probe from the installed app's native-library directory.
- NativeProcessSupervisor separates serial stdout and diagnostic stderr, writes input, waits for exit, and supports force termination as a fallback.
- QmpClient passes a QMP capabilities and quit exchange over an Android local socket.
- QemuLaunchConfig unit tests cover virt TCG, kernel/initramfs, persistent VirtIO block, serial, QMP, and a localhost-only HTTP forward.
- None of these tests boots QEMU or Alpine inside Android.

## Runtime integration constraints

The Android service must run in the dedicated :vm process as a foreground service while the guest is active; launch QEMU from the APK-extracted native-library directory; use app-private working storage for temporary files and the guest disk; keep serial and diagnostics separate; request graceful QMP shutdown before bounded force termination; and keep localhost forwarding bound to loopback by default.

The current launch specification accepts kernel, initramfs, and disk paths, but Android does not yet package or manage those assets. The service intentionally fails closed.

## Next proof gates

1. Pass the expanded APK ELF and runtime dependency audit.
2. Run QEMU on ARM64 Android and capture version/help output and diagnostics.
3. Package an Alpine kernel, initramfs, and writable ext4 disk; boot to a readiness signal.
4. Bridge the serial console, verify login and package installation, and confirm disk persistence.
5. Request QMP shutdown from ServerService and verify QEMU exits before releasing disk resources.
6. Test physical ARM64 devices and record boot time, memory, battery impact, and native crash diagnostics.

Until ARM64 execution and guest boot pass, PocketOS must describe Android Linux startup as unimplemented.
