# PocketOS architecture

## Layers
1. Android activity and AppWidgetProvider: status and user-triggered control.
2. Foreground service (future): lifecycle orchestration, battery receiver, elapsed-realtime deadline, notification actions.
3. Native QEMU executable (future): unprivileged process launched using Android-compatible packaging; verify Android app executable restrictions.
4. ARM64 QEMU `virt` guest: minimal Linux kernel, Alpine root filesystem, serial-only console, persistent block device.
5. Networking: QEMU user-mode host forwarding to 127.0.0.1 by default; optional LAN bind with explicit confirmation. Public tunnel deferred.

## Safety invariants
- Never request Android root.
- Guest root does not equal Android root.
- Do not mark guest READY before a real serial/network readiness check.
- Stop at 60 minutes measured with elapsed realtime, or at/below 20% battery.
- Do not stop on Wi-Fi disconnect.
- Attempt graceful guest shutdown and bounded cleanup; never discard disk without warning.
- Never expose SSH/admin services publicly by default.
- Foreground-service type must be justified and tested against Android/Play policies.
- QEMU emulation does not provide native hardware virtualization acceleration.

## Known limitations
Android process lifecycle and battery management can interrupt operation. No VM isolation security guarantee has been audited. Mobile CGNAT prevents straightforward inbound public IPv4 connectivity. No guest networking is implemented in the APK yet.
