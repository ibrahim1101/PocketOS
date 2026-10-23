# Android QEMU integration

Status: Android ARM64 QEMU and Alpine guest assets are built and packaged. The Vivo I2501 confirmed installation, dashboard launch, and execution of packaged QEMU 11.1.2. The full Alpine instrumentation test has not completed on-device. Its runtime validation gate remains closed and the VM service remains fail-closed.

## Physical ARM64 stall evidence

The uploaded logcat shows `packagedArm64QemuRunsOnAndroid` completed, then `bootsFreshAlpineSetsPasswordLogsInPersistsAndQuitsThroughQmp` started. A later run shows the same ordering; no `finished` event for the Alpine test appears in the captured log. The process snapshot shows PocketOS PID 6019 and child QEMU PID 6108; QEMU is sleeping with 5 threads, about 14 MiB resident memory, and about 216 MiB swapped. `/proc/6108/wchan` is `0`, which does not identify a wait site. The supplied snapshots do not expose serial output, QEMU stderr, per-thread stacks, or time-separated CPU counters. The full logcat has no PocketOS exception, AndroidRuntime crash, or correlated low-memory kill around the second run. These facts establish a live stalled or prolonged test, but do not establish seccomp, SELinux, Android 16, memory pressure, or Alpine as the cause.

The tested APK build identity was verified from the GitHub Actions handoff artifact. `dist/build-info.txt` records merge commit `a3996bb1230373fd0fa7d0b6fd1a4421ef4e5353`, and the APK artifact names use that SHA. Its Git tree is identical to branch commit `3e262897a2c3b827fb847fec8006dc46d3526fee`, which the user reported as the tested commit. Workflow run #176 was later marked cancelled even though APK artifacts and the test handoff had been uploaded. The new diagnostic test build adds bounded progress and output capture; it does not claim the guest boot issue is fixed.

### Follow-up device evidence (2026-10-22)

The full UTF-16 PowerShell captures confirm the test reached `Waiting for the first-run password prompt` at 16:22:07 and remained active until the user manually force-stopped PocketOS at 16:50:35. The terminal output ends with `shortMsg=Process crashed` after that manual stop; this is not evidence of a spontaneous QEMU crash. QEMU PID 10738 emitted the startup denials, and the supplied process samples reported sleeping threads with unchanged CPU time. Neither logcat contains a PocketOS test heartbeat, serial transcript, QMP response, or QEMU stderr, so guest CPU progress and the first missing boot event cannot be recovered from this run.

The SELinux denials are not established as the boot cause. The `max_map_count` read is an optional QEMU coroutine-pool sizing read; the pinned QEMU 11.1.2 code falls back to its default when it fails. The stdio character backend calls `tcgetattr`/`tcsetattr` on stdin and ignores those return values, consistent with the denied `0x5402` terminal-setting ioctl on the process pipe. This may be noisy on Android, but the code path does not make that failure fatal. The repeated `tests` directory search denials have no corresponding QEMU startup error in the captured logs. Keep them as environment evidence and verify serial/QMP behavior on the next run rather than weakening SELinux.

The prior instrumentation deadline callback synchronously collected procfs and QMP diagnostics before it killed QEMU, and the shared timer thread executed callbacks inline. That ordering allowed a blocked diagnostic operation to delay both process termination and other deadlines. The updated test stops tracked QEMU first, interrupts the blocked test caller immediately, performs post-stop diagnostics on a separate daemon worker, and records independent 15-second QMP/CPU/serial/stderr snapshots to an app-private timestamped journal. A child that registers after the deadline is stopped in its registration callback. This addresses the demonstrated supervision gap; it does not prove the underlying Alpine stall is fixed until the new Vivo test captures guest progress or a bounded failure snapshot.

### Follow-up device evidence (2026-10-22, v3)

The real-QEMU five-second watchdog snapshot confirms the ARM64 kernel starts, Alpine initramfs switches to the ext4 root, and OpenRC begins on the serial console. The last captured line is early OpenRC startup while `/run/lock` is being prepared. The watchdog deliberately kills QEMU at five seconds, so this snapshot does not show a failed login attempt. The separate full-test journal contains its launch record but no watchdog-timeout record; it does not establish that the eight-minute test deadline fired or identify the later blocking call.

The rootfs builder had a concrete startup-order defect: `/etc/inittab` ran OpenRC `sysinit` and `boot` as synchronous `sysinit` actions, before BusyBox init could start the serial first-boot handler. The boot runlevel includes networking. Any Android-specific delay in an OpenRC boot service therefore withheld the only usable console. The image also relied on later OpenRC work to make the initially read-only root writable before `adduser` modified account files. The builder now remounts the guest root read/write in early init, mounts `/run` before creating a coordination marker, and starts OpenRC only after the serial setup reaches getty. OpenRC runlevels run from an asynchronous `once` action, and root/local filesystem services are enabled. This removes OpenRC/network readiness as a prerequisite for account setup and the serial login. The exact service that stalled on the prior Vivo run was not captured, so this fixes the blocking startup design and has a targeted regression; it is not a claim of physical-device success.

CI now injects an OpenRC boot service that sleeps for two minutes, then requires the first-run prompt within 15 seconds, completes account setup, reboots, logs in, and verifies that the blocked service was active. This specifically guards against a slow boot service hiding the serial terminal. The real Vivo still needs one consolidated validation run to confirm Android-specific behavior.

The ARM64 test reports each boot stage and has an independent watchdog plus a 12-minute hard test timeout. It holds a partial CPU wake lock during guest execution so Android screen-off idle cannot suspend QEMU and its watchdog. If a stage fails, it captures bounded password-redacted serial and stderr tails, QEMU exit status, per-thread state and CPU deltas, and a read-only QMP connectivity/status probe before forced cleanup. Each QEMU instance has an independent serial capture and QMP socket; the test waits for its output pumps to exit before releasing the persistent disk lease and launching the next instance. It writes and syncs a sentinel file after the first authenticated login, then verifies the existing password, first-run marker, non-root account, and sentinel after reboot. Both controller-requested graceful shutdown and forced termination mark the process's stream closure as expected; unexpected pump failures retain sanitized exception-class diagnostics. Cleanup errors do not replace the original boot failure. Android may restrict procfs fields; a `wchan=0` value alone does not identify the cause. Repeated `cpuDeltaTicks=0` with no serial/stderr growth indicates no observable CPU progress during that interval, while increasing ticks show activity without identifying the boot stage. The test uses a throwaway copy of the packaged seed and keeps the runtime gate closed until the physical test passes.

### Follow-up device evidence (2026-10-23, v5)

The v5 journal showed the test-thread prompt wait stopped after its first heartbeat while the separate heartbeat thread continued. The wait callback performed a synchronous, unbounded `/proc` thread snapshot before returning to check the serial buffer. On Android, that diagnostic read could block the same thread responsible for recognizing `New password:` and enforcing its stage timeout. Prompt waits now do only bounded buffer/process checks and timed monitor waits; the independent heartbeat continues to collect bounded process and QMP diagnostics. The Windows handoff now runs only the end-to-end guest test, which covers QEMU launch, first-run login, synced file persistence, same-disk reboot, credential verification, and cleanup. The v5 run does not establish physical persistence success; one new consolidated Vivo run must complete the reboot verification.

The v6 bundle used commit `837886d4ce7bf60e718d6797b0136c490fd4a15b`. Its newest run reached ARM64 QEMU launch and virtio disk discovery, then stopped recording heartbeats and eventually hit the host timeout. It does not contain evidence of an ext4 mount or a guest login, so this run does not establish a guest boot failure cause. Review found that every stage and heartbeat synchronously appended to the app-private journal and called `fsync()` on the instrumentation or heartbeat thread. This storage operation has no deadline and could stall test progress or teardown independently of the guest. Journal writes now use a bounded in-memory queue and a daemon writer; heartbeat records are discarded before milestones if storage falls behind. Each serial prompt wait also has its own independent deadline callback: it kills its QEMU process and interrupts the instrumentation caller before collecting diagnostics. A regression test holds the journal writer deliberately and verifies that a watchdog record call returns promptly; the failed-boot regression now verifies that a process is terminated at its stage deadline and the timeout retains serial/QMP diagnostics. These changes remove unbounded storage and caller-scheduling dependencies from the supervision path, but the physical guest boot still requires the consolidated Vivo run.

Android CI also boots the exact kernel and initramfs copied into the app assets, against the raw disk used to make the compressed APK seed. QemuLaunchConfig now uses the host-validated `cortex-a72` CPU model and explicitly declares the ext4 root filesystem. The v3 trace establishes that guest Linux runs through early OpenRC on ARM64 Android; the particular OpenRC service or Android-specific wait that prevented the full test from reaching the console was not captured. CI can validate the new console-independent startup path, but only the consolidated Vivo run can close the physical runtime gate.

## Verified build and package boundary

CI run #144 passed the pinned QEMU source fetch, Android ARM64 cross-build, APK assembly, native dependency audit, Android unit and x86_64 instrumentation tests, root-lock validation against the APK seed image and host-boot image, and host Alpine guest gates.

The cross-build downloads QEMU 11.1.2 from the pinned upstream archive and verifies SHA-256 `731b5681e4bb18be313231579b8efd0296c5b015fa36dc533874b639ba838016`. It builds checksum-pinned libiconv, libffi, PCRE2, GLib, pixman, and libslirp static dependencies. A Bionic compatibility shim provides QEMU's temporary POSIX shared-memory file descriptor using an immediately unlinked file in its working directory.

The APK contains an Android AArch64 PIE staged as `lib/arm64-v8a/libqemu-system-aarch64.so`. CI checks ELF machine, PIE type, `/system/bin/linker64`, required system libraries, and absence of RPATH/RUNPATH.

The APK also contains Alpine's `vmlinuz-virt`, matching `initramfs-virt`, gzip-compressed ext4 seed disk, and SHA-256 manifest. The root account's password field is forcibly set to the locked marker in the image builder. Early guest init remounts the persistent root read/write before the first-boot handler creates a `pocketos` account without a preset password, invokes `passwd` on the guest TTY, and records completion on the persistent disk. OpenRC sysinit, boot, and default runlevels still start, but asynchronously so service startup cannot block console readiness. The password is entered interactively with echo disabled; it is not supplied in QEMU arguments, the environment, or log output. CI checks the root lock, writable-root hook, asynchronous OpenRC configuration, and first-boot setup in the host-built and APK-seed images. `GuestAssetInstaller.forContext(context)` reads packaged assets, validates hashes, stores versioned boot assets in app-private storage, atomically expands and validates the disk, and preserves an existing disk on repeat install.

`GuestDiskLease` creates and locks a persistent sibling lock file for the lifetime of a guest disk writer. JVM tests and Android instrumentation verify that a second writer is rejected until the lease is closed, after which the disk can be leased again.

## Guest readiness and session protection

`GuestReadinessProbe` detects the complete first-run password prompt or Alpine login prompt across serial chunks, removes ANSI sequences, and avoids treating `Last login:` as readiness.

`SessionProtection` stores a monotonic elapsed-realtime start and deadline through a `SessionCheckpointStore`. SharedPreferences commits the deadline before use. Recovery preserves the existing deadline rather than granting another hour; a checkpoint from before device reboot expires. Defaults remain 60 minutes, 20% battery cutoff, and no-stop-on-Wi-Fi-disconnect.

`BatteryLevelMonitor` listens for Android's sticky `ACTION_BATTERY_CHANGED` broadcast and subsequent events without polling.

`GuestBootCoordinator` couples these components to `VmLifecycle`: process start advances to BOOTING, and the RUNNING transition requires a complete login prompt and a persisted session deadline. It reports low-battery rejection and clears the session checkpoint after a process exit. JVM tests cover readiness, timer start at the prompt, cutoff, clean shutdown, and unexpected exit.

The APK asset install path has additionally been exercised on the Android x86_64 emulator. This does not test ARM64 QEMU execution. `ServerService` constructs and owns `GuestSessionController`, which calls the installer, process supervisor, readiness coordinator, persisted session policy, QMP shutdown request, and disk lease. The service also starts the event-driven battery monitor, publishes terminal serial input/output, updates foreground notifications, and keeps the runtime gate closed until ARM64 execution and guest boot evidence exists. JVM tests cover controller lifecycle and policy behavior; direct Android service lifecycle coverage remains a proof gap.

## Existing Android boundary tests

- CI packages PIE probes for arm64-v8a and x86_64; instrumentation launches the x86_64 probe from the installed app's native-library directory.
- NativeProcessSupervisor separates serial stdout and diagnostic stderr, writes input, waits for exit, and supports force termination as a fallback.
- QmpClient passes a QMP capabilities and quit exchange over an Android local socket.
- QemuLaunchConfig unit tests cover virt TCG, kernel/initramfs, persistent VirtIO block, serial, QMP, and a localhost-only HTTP forward.
- None of these tests boots QEMU or Alpine inside Android.


## First-run account validation

The guest-boot CI job drives the hidden `passwd` prompts over QEMU serial using a random, ephemeral test password. The checker does not print the serial transcript or credential. It verifies the persistent account record, then boots again, logs in through the normal serial login, and confirms the shell runs as non-root. This host-side proof does not establish ARM64 Android guest boot or first-run completion on the Vivo; those remain blocked by the physical runtime validation gate.

## Runtime integration constraints

The service runs in the dedicated `:vm` process as a foreground service while the guest is active; launches QEMU from the APK-extracted native-library directory; uses app-private working storage and the persistent guest disk; keeps serial and diagnostics separate; requests graceful QMP shutdown before bounded force termination; and binds localhost forwarding to loopback by default. The activity and service already bridge terminal serial input/output. Completing first-run setup and login on the user's persistent disk, installing packages, and verifying Android-side localhost access still require the ARM64 runtime gate to open.

The service may transition to RUNNING only after the first-run password prompt or serial login prompt is detected and the deadline is stored. The runtime gate remains closed until QEMU execution and guest boot pass on ARM64 Android. Low battery blocks startup and requests shutdown at or below 20%. ARM64 Android execution and secure guest login remain unverified, so service startup stays disabled.

## Physical ARM64 device test

The ARM64-only instrumentation test uses a fresh copy of the packaged ext4 seed, verifies its hash, provisions a non-root account, logs in, checks the setup marker across a reboot, and requires a clean QMP quit. The temporary disk keeps test credentials out of the user's persistent guest disk. Separate instrumentation tests cover child stdout/stderr and nonzero exit handling, concurrent pipe closure during forced cleanup, and output-callback failure isolation.

CI publishes app and instrumentation APKs from the same commit, with build hashes and emulator results. Trusted pushes to `feat/m1-linux-boot` seed/reuse a branch-scoped development debug key; pull-request runs deliberately do not share that key. If an older APK was signed by a different key, uninstall it once before switching to trusted branch artifacts. Follow [Physical ARM64 Android testing](PHYSICAL_ANDROID_TESTING.md) to install and run them.

## Next proof gates

1. Add direct Android foreground-service lifecycle coverage for startup rejection at the closed runtime gate, event delivery, and cleanup; JVM controller tests already cover process/session policy transitions.
2. Run the instrumented QEMU guest-boot test on ARM64 Android and use the pre-cleanup QMP probe, process state, serial/stderr tails, and CPU-delta heartbeats to locate the current stall.
3. After guest boot passes, use the app terminal to complete first-run password setup and login on the persistent disk, then verify package installation, disk persistence, and Android-side localhost access.
4. Verify QMP shutdown, timer cutoff, battery cutoff, and physical-device performance on the Vivo.

Until ARM64 execution and guest boot pass, PocketOS must describe Android Linux startup as unimplemented.
