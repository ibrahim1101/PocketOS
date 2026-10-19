# Android QEMU integration

Status: Android ARM64 QEMU and Alpine guest assets are built and packaged. The generated guest image locks the root password, verified directly from both ext4 images in CI. The app-private installer, serial readiness probe, session-protection primitives, and lifecycle coordinator are unit-tested. Android-side QEMU execution, guest boot, and service integration remain unverified; the VM service remains fail-closed.

## Verified build and package boundary

CI run #144 passed the pinned QEMU source fetch, Android ARM64 cross-build, APK assembly, native dependency audit, Android unit and x86_64 instrumentation tests, root-lock validation against the APK seed image and host-boot image, and host Alpine guest gates.

The cross-build downloads QEMU 11.1.2 from the pinned upstream archive and verifies SHA-256 `731b5681e4bb18be313231579b8efd0296c5b015fa36dc533874b639ba838016`. It builds checksum-pinned libiconv, libffi, PCRE2, GLib, pixman, and libslirp static dependencies. A Bionic compatibility shim provides QEMU's temporary POSIX shared-memory file descriptor using an immediately unlinked file in its working directory.

The APK contains an Android AArch64 PIE staged as `lib/arm64-v8a/libqemu-system-aarch64.so`. CI checks ELF machine, PIE type, `/system/bin/linker64`, required system libraries, and absence of RPATH/RUNPATH.

The APK also contains Alpine's `vmlinuz-virt`, matching `initramfs-virt`, gzip-compressed ext4 seed disk, and SHA-256 manifest. The root account's password field is forcibly set to the locked marker in the image builder. CI checks the resulting ext4 image with `scripts/check-guest-root-lock.sh` in both the APK-seed and host-boot paths. First-run creation of a non-root login account is not implemented yet. `GuestAssetInstaller.forContext(context)` reads packaged assets, validates hashes, stores versioned boot assets in app-private storage, atomically expands and validates the disk, and preserves an existing disk on repeat install.

`GuestDiskLease` creates and locks a persistent sibling lock file for the lifetime of a guest disk writer. JVM tests and Android instrumentation verify that a second writer is rejected until the lease is closed, after which the disk can be leased again.

## Guest readiness and session protection

`GuestReadinessProbe` detects the complete Alpine `pocketos login:` prompt across serial chunks, removes ANSI sequences, and avoids treating `Last login:` as readiness.

`SessionProtection` stores a monotonic elapsed-realtime start and deadline through a `SessionCheckpointStore`. SharedPreferences commits the deadline before use. Recovery preserves the existing deadline rather than granting another hour; a checkpoint from before device reboot expires. Defaults remain 60 minutes, 20% battery cutoff, and no-stop-on-Wi-Fi-disconnect.

`BatteryLevelMonitor` listens for Android's sticky `ACTION_BATTERY_CHANGED` broadcast and subsequent events without polling.

`GuestBootCoordinator` couples these components to `VmLifecycle`: process start advances to BOOTING, and the RUNNING transition requires a complete login prompt and a persisted session deadline. It reports low-battery rejection and clears the session checkpoint after a process exit. JVM tests cover readiness, timer start at the prompt, cutoff, clean shutdown, and unexpected exit.

The APK asset install path has additionally been exercised on the Android x86_64 emulator. This does not test ARM64 QEMU execution. These are tested components only. `ServerService` does not yet call the installer, coordinator, battery monitor, session policy, or process supervisor.

## Existing Android boundary tests

- CI packages PIE probes for arm64-v8a and x86_64; instrumentation launches the x86_64 probe from the installed app's native-library directory.
- NativeProcessSupervisor separates serial stdout and diagnostic stderr, writes input, waits for exit, and supports force termination as a fallback.
- QmpClient passes a QMP capabilities and quit exchange over an Android local socket.
- QemuLaunchConfig unit tests cover virt TCG, kernel/initramfs, persistent VirtIO block, serial, QMP, and a localhost-only HTTP forward.
- None of these tests boots QEMU or Alpine inside Android.

## Runtime integration constraints

The service must run in the dedicated `:vm` process as a foreground service while the guest is active; launch QEMU from the APK-extracted native-library directory; use app-private working storage and the persistent guest disk; keep serial and diagnostics separate; request graceful QMP shutdown before bounded force termination; and bind localhost forwarding to loopback by default.

The service may transition to RUNNING only after the serial login prompt is detected and the deadline is stored. Low battery blocks startup and requests shutdown at or below 20%. ARM64 Android execution and secure guest login remain unverified, so service startup stays disabled.

## Next proof gates

1. Integrate installer, process supervisor, coordinator, battery monitor, and deadline store into foreground-service lifecycle tests.
2. Run QEMU on ARM64 Android and boot the packaged Alpine guest.
3. Bridge serial input/output, add secure first-run credentials, and verify package installation and disk persistence.
4. Verify QMP shutdown, timer cutoff, battery cutoff, and physical-device performance.

Until ARM64 execution and guest boot pass, PocketOS must describe Android Linux startup as unimplemented.
