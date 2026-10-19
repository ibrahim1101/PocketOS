# PocketOS development status

Updated: 2026-10-19

## Current state

- Active milestones: M9 — real server workloads; physical ARM64 runtime validation remains pending.
- Active branch: feat/m1-linux-boot; PR #1 remains open.
- Latest completed workflow success: run #181 (commit 8ca2f1a). It built the debug and instrumentation APKs, cross-built Android ARM64 QEMU, passed APK ABI audit and Android unit/instrumentation tests, and verified Alpine boot, account setup, Linux shell commands, persistence, and localhost HTTP forwarding. Python workload execution is being added to the guest job and requires CI validation.
- The APK packages QEMU 11.1.2, Alpine's matching kernel and initramfs, a compressed ext4 seed disk, and a SHA-256 manifest. CI verifies the root password is locked in the generated seed image.
- The app installs and verifies guest assets in private storage. Unit and Android instrumentation tests cover disk integrity, repeat install, exclusive disk leasing, VM lifecycle, readiness, session policy, and runtime-gate decisions.
- Host CI boots Alpine under QEMU, provisions a non-root pocketos account through hidden serial password prompts, verifies login after restart, runs Linux commands, checks ext4 persistence, and tests localhost HTTP forwarding.
- No QEMU execution, guest boot, or interactive guest login has yet been performed on ARM64 Android. The physical-device instrumentation test now covers first-run password creation, non-root login, a persistent setup marker across reboot, and QMP shutdown using an isolated throwaway disk. The x86_64 Android emulator skips the ARM64-only runtime tests. A Linux ARM64 emulator job was removed because Google's SDK repository did not provide the emulator package.
- The ARM64 runtime gate is closed by default. On an ARM64 Android install, QemuAndroidRuntimeInstrumentationTest runs QEMU --version and boots the packaged Alpine guest to its first-run or login prompt. Successful tests save evidence tied to the packaged guest asset manifest and QEMU version; the service can start only when both checks pass. Run gradle -p android connectedDebugAndroidTest on an ARM64 Android device to execute these checks.

## Implemented and CI-verified

- Foreground service owns GuestSessionController, QEMU process supervision, serial readiness, the 60-minute deadline, event-driven 20% battery cutoff, QMP shutdown request, bounded force-stop, disk lease, notifications, and shutdown status. Wi-Fi changes do not stop the guest.
- Dashboard start/stop controls, status refresh, serial terminal, guest RAM/vCPU/HTTP port settings, opt-in Wi-Fi LAN exposure, a persistent storage view, runtime diagnostics, and sanitized diagnostics export through Android's document picker. The export includes a bounded event history with enum-only event details; terminal contents, credentials, file paths, arbitrary error text, and network addresses are excluded.
- Persistent storage screen reports raw guest image capacity, Android device free space, and ext4 guest filesystem free/used space. Guest filesystem metrics are read only while the exclusive disk lease is available; they are shown as unavailable while the VM is active.
- User-selected ext4 disk export acquires the exclusive guest disk lease before opening the destination and copies the image byte for byte; Android unit tests verify it is blocked while a VM owns the disk.
- Home-screen widget start/stop actions and live service status.
- Terminal password prompts use masked input; submitted input is cleared and terminal contents remain in memory only.
- A bounded private diagnostic event history records start/stop requests, runtime gate blocks, and lifecycle changes using timestamps and enum-only details; the export omits free-form runtime and terminal data.
- QemuLaunchConfig uses ARM virt TCG, a persistent VirtIO disk, serial console, and QMP. HTTP forwarding binds to 127.0.0.1 by default; optional Wi-Fi LAN forwarding requires an explicit confirmation and binds to the active Wi-Fi IPv4 address.
- Android CI cross-builds QEMU for arm64-v8a, audits packaged libraries, builds the APK, runs unit tests and x86_64 emulator instrumentation. Alpine CI performs the guest boot, account, shell, persistence, and forwarding checks.
- The Alpine guest job now also installs Python 3, starts a Python HTTP server in the guest, and verifies its response through the host's loopback-only forwarded port.

## Remaining validation

- Run the Python workload check in CI and validate package installation/workloads on physical ARM64 Android. The current workspace has no adb/device connection, and CI only provides an x86_64 Android emulator.
- The physical instrumentation test provisions a temporary throwaway guest disk so it does not set a test password on the user's persistent guest disk. After the runtime gate opens, use the app terminal to verify first-run login on the user's own persistent disk, package installation, and Android-side localhost access.
- Measure boot time, memory use, and battery impact on physical ARM64 hardware. Validate LAN exposure across network transitions; custom kernels and secure remote access remain later work.
- Do not merge or release without explicit approval.
