# PocketOS development status

Updated: 2026-10-19

## Current state

- Active milestones: M6 — persistent storage management and M7 — localhost/LAN networking.
- Active branch: feat/m1-linux-boot; PR #1 remains open.
- Latest fully passing feature validation: run #161. Storage reporting/export and Wi-Fi LAN additions await the current CI run.
- The APK packages QEMU 11.1.2, Alpine's matching kernel and initramfs, a compressed ext4 seed disk, and a SHA-256 manifest. CI verifies the root password is locked in the generated seed image.
- The app installs and verifies guest assets in private storage. Unit and Android instrumentation tests cover disk integrity, repeat install, exclusive disk leasing, VM lifecycle, readiness, session policy, and runtime-gate decisions.
- Host CI boots Alpine under QEMU, provisions a non-root pocketos account through hidden serial password prompts, verifies login after restart, runs Linux commands, checks ext4 persistence, and tests localhost HTTP forwarding.
- No QEMU execution, guest boot, or interactive guest login has yet been performed on ARM64 Android. The x86_64 Android emulator skips the ARM64-only runtime tests. A Linux ARM64 emulator job was removed because Google's SDK repository did not provide the emulator package.
- The ARM64 runtime gate is closed by default. On an ARM64 Android install, QemuAndroidRuntimeInstrumentationTest runs QEMU --version and boots the packaged Alpine guest to its first-run or login prompt. Successful tests save evidence tied to the packaged guest asset manifest and QEMU version; the service can start only when both checks pass. Run gradle -p android connectedDebugAndroidTest on an ARM64 Android device to execute these checks.

## Implemented and CI-verified

- Foreground service owns GuestSessionController, QEMU process supervision, serial readiness, the 60-minute deadline, event-driven 20% battery cutoff, QMP shutdown request, bounded force-stop, disk lease, notifications, and shutdown status. Wi-Fi changes do not stop the guest.
- Dashboard start/stop controls, status refresh, serial terminal, guest RAM/vCPU/HTTP port settings, opt-in Wi-Fi LAN exposure, a persistent storage view, and runtime diagnostics.
- Persistent storage screen reports raw guest image capacity, Android device free space, and ext4 guest filesystem free/used space. Guest filesystem metrics are read only while the exclusive disk lease is available; they are shown as unavailable while the VM is active.
- User-selected ext4 disk export acquires the exclusive guest disk lease before opening the destination and copies the image byte for byte; export is blocked while a VM owns the disk.
- Home-screen widget start/stop actions and live service status.
- Terminal password prompts use masked input; submitted input is cleared and terminal contents remain in memory only.
- QemuLaunchConfig uses ARM virt TCG, a persistent VirtIO disk, serial console, and QMP. HTTP forwarding binds to 127.0.0.1 by default; optional Wi-Fi LAN forwarding requires an explicit confirmation and binds to the active Wi-Fi IPv4 address.
- Android CI cross-builds QEMU for arm64-v8a, audits packaged libraries, builds the APK, runs unit tests and x86_64 emulator instrumentation. Alpine CI performs the guest boot, account, shell, persistence, and forwarding checks.

## Remaining validation

- Run the ARM64 instrumentation suite on a physical ARM64 Android device. The current workspace has no adb/device connection, and CI only provides an x86_64 Android emulator.
- After the runtime gate opens on that device, finish first-run account setup and login through the app terminal; verify package installation, guest filesystem usage telemetry, and Android-side localhost access.
- Measure boot time, memory use, and battery impact on physical ARM64 hardware. LAN exposure, disk resizing/import, guest filesystem usage telemetry, and custom kernels remain later work.
- Do not merge or release without explicit approval.
