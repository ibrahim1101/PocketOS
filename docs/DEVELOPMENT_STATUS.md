# PocketOS development status

Updated: 2026-10-25

## Current state

- Active work: physical ARM64 runtime validation on the Vivo I2501. M0-M9 feature implementation is CI-verified; public tunneling and custom kernels remain deferred.
- Active branch: feat/m1-linux-boot; PR #1 remains open.
- The latest application/physical-handoff change is commit `c2dfe826af67e4339532eef10ea2f34fdbb0e0fd`; trusted push run #309 and PR run #310 passed. The candidate cross-built Android ARM64 QEMU, booted the packaged Alpine assets under host QEMU, passed guest boot/account/persistence/network workloads, built both APKs from that commit, passed the APK ABI audit and Android emulator instrumentation, and passed the PowerShell handoff parser and diagnostics-sanitizer checks. Push artifacts are `pocketos-android-apk-c2dfe826af67e4339532eef10ea2f34fdbb0e0fd`, `pocketos-instrumentation-apk-c2dfe826af67e4339532eef10ea2f34fdbb0e0fd`, and `pocketos-test-handoff-c2dfe826af67e4339532eef10ea2f34fdbb0e0fd` (expire 2026-11-08).
- Run artifacts contain the signed debug app APK, instrumentation APK, build identity, APK checksums, and emulator reports. The trusted branch debug signing identity is retained so updates install with `adb install -r`.
- CI verified guest ext4 growth from an enlarged image through online `resize2fs`, first-run setup, clean poweroff, and same-disk login after reboot. Android-side ext4 restore/storage parsing also rejects overflowing 64-bit filesystem metadata.
- Host QEMU and the x86_64 Android emulator do not establish ARM64 guest boot on Android. The Vivo runtime gate remains closed until the physical instrumentation workflow passes.

## Implemented and CI-verified

- Foreground service owns GuestSessionController, QEMU process supervision, a 180-second serial-readiness deadline, the 60-minute session deadline, event-driven 20% battery cutoff, bounded QMP shutdown, bounded force-stop, disk lease, notifications, and shutdown status. Wi-Fi changes do not stop the guest.
- Dashboard start/stop controls, status refresh, serial terminal, guest RAM/vCPU/HTTP port settings, opt-in Wi-Fi LAN exposure, a persistent storage view, runtime diagnostics, and sanitized diagnostics export through Android's document picker. The export includes a bounded event history with enum-only event details; terminal contents, credentials, file paths, arbitrary error text, and network addresses are excluded.
- Persistent storage screen reports raw guest image capacity, Android device free space, and ext4 guest filesystem free/used space. Guest filesystem metrics are read only while the exclusive disk lease is available; they are shown as unavailable while the VM is active.
- User-selected ext4 disk export acquires the exclusive guest disk lease before opening the destination and copies the image byte for byte; Android unit tests verify it is blocked while a VM owns the disk.
- Guest disk import validates the ext4 superblock in a staging file, preserves the current disk on failure, and atomically replaces it under the exclusive lease. Growth is staged and atomic; boot-time online ext4 expansion is verified by enlarging an isolated CI disk, cleanly shutting down, and reading the resulting superblock capacity.
- Ext4 capacity/free-space parsing bounds 64-bit block counts against the image length before multiplication; regression tests reject overflowed restore metadata without changing the current disk.
- Home-screen widget start/stop actions and live service status.
- Terminal password prompts use masked input; submitted input is cleared and terminal contents remain in memory only.
- A bounded private diagnostic event history records start/stop requests, runtime gate blocks, and lifecycle changes using timestamps and enum-only details; the export omits free-form runtime and terminal data.
- QemuLaunchConfig uses ARM virt TCG, a persistent VirtIO disk, serial console, and QMP. HTTP forwarding binds to 127.0.0.1 by default; optional Wi-Fi LAN forwarding requires an explicit confirmation and binds to the active Wi-Fi IPv4 address.
- Android CI cross-builds QEMU for arm64-v8a, audits packaged libraries, builds the app and instrumentation APKs from the same commit, and runs unit tests and x86_64 emulator instrumentation. Trusted non-PR builds reuse a branch-scoped development debug signing key and record its certificate fingerprint in the test handoff, allowing successive APKs from that branch to update in place. Alpine CI performs the guest boot, account, shell, persistence, SQLite, Python HTTP, and forwarding checks.
- The Alpine guest job installs Python 3, verifies SQLite persistence across close and reopen, starts a Python HTTP server in the guest, and verifies its response through the host's loopback-only forwarded port.
- ARM64 guest instrumentation now has an independent 4-minute watchdog that runs outside the test thread, captures diagnostics, kills tracked QEMU children, and interrupts blocked waits; the 5-minute JUnit ceiling remains as a secondary limit. Fifteen-second heartbeats record every visible QEMU thread's CPU ticks/deltas and wait channel, plus sanitized launch parameters, stdout/stderr tails, exit status, QMP status, and QMP `query-cpus-fast` thread IDs/halted state. The Vivo snapshot's `wchan=0` remains inconclusive; the next device run should show whether the guest vCPU advances and serial output appears.
- QEMU stream pumps now treat forced-close read exceptions as expected, isolate callback failures, and always release their pump completion latch. QMP quit and diagnostic probes have caller-side deadlines that also bound local socket connection attempts. Failed guest-test cleanup records bounded process/CPU/serial/stderr/exit/QMP diagnostics before termination and preserves the original test failure. ARM64 instrumentation covers boot failure, concurrent forced pipe closure, callback failure, and an independent watchdog firing while its test thread is blocked. Android CI boots the exact packaged kernel/initramfs/disk combination on host QEMU; the Vivo failure cause remains unconfirmed until physical results are returned.
- The Windows physical-test handoff now uses a shared sanitizer before saving diagnostics. It redacts credential assignments, bearer tokens, IPv4/IPv6 addresses, MAC addresses, Android app-private paths, and local host paths. A PowerShell regression script exercises those cases in CI.

## Remaining validation

- Run the consolidated ARM64 instrumentation test on the Vivo I2501 using a successful push run’s APKs. It checks QEMU launch, Alpine first-run password setup, non-root login, clean shutdown, and same-disk persistence after restart.
- After the runtime gate opens, validate the user's own persistent guest disk, package installation, and Android-side localhost access.
- Measure boot time, memory use, and battery impact on physical ARM64 hardware. Validate LAN exposure across network transitions.
- Do not merge or release without explicit approval.

## Latest physical-device follow-up (v8)

The v8 log shows first boot and QMP exit succeed, then second boot stops at `Mounting root:` before ext4 reports a mount. The next candidate requests a clean poweroff from Alpine before reopening the image, adds immediate detection for a repeated first-run prompt, retains bounded serial tails in the private journal, and moves timeout interruption ahead of process cleanup. Host and Android-emulator CI do not establish the Vivo second boot; keep the ARM64 guest runtime gate closed until that device test passes.


## Latest application and handoff candidate (2026-10-25)

Commit `c2dfe826af67e4339532eef10ea2f34fdbb0e0fd`, push workflow run [#309](https://github.com/ibrahim1101/PocketOS/actions/runs/40184794770), and PR workflow run [#310](https://github.com/ibrahim1101/PocketOS/actions/runs/40184797276) passed. This candidate includes ext4 metadata validation before export and installation, terminal password masking across serial read boundaries, the QEMU cleanup regression expectation fix, an explicit Android linker path for the executable probe, and network-identifier redaction in the Windows physical-test handoff. CI is not evidence of Vivo ARM64 guest success; the physical runtime gate remains closed.
