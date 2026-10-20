# Roadmap

## M0 — repository and policy scaffold (this commit)
- Native Android skeleton and widget that opens app
- Pure Kotlin shutdown policy and unit tests
- Host-side QEMU boot experiment harness

## M1 — Linux boot proof
- Reproducible Alpine ARM64 rootfs and compatible guest kernel
- Host-side serial boot and disk persistence tests
- License/source notices for QEMU, Alpine and bundled binaries

## M2 — Android native engine
- Android-compatible QEMU packaging and process execution
- PTY/serial terminal, readiness detection, VM lifecycle state machine
- Device-specific performance and compatibility testing

## M3 — Widget and power controls
- Genuine one-tap start/stop widget with explicit state reporting
- Android foreground-service compliance and notification actions
- Elapsed-realtime 60-minute shutdown, 20% battery stop

## M4 — Networking
- Localhost-only hostfwd, explicit opt-in LAN exposure
- Local HTTP/SSH smoke tests and binding security checks
- Optional public tunnel in a later release

## M6 — Persistent storage management
- Private ext4 capacity and free-space reporting
- Byte-for-byte backup export while stopped
- Validated ext4 backup import with staged atomic replacement and confirmation
- Disk image growth followed by automatic ext4 expansion on next boot

## M7 — Android networking
- Localhost-only host forwarding by default
- Explicit Wi-Fi LAN exposure bound to the active Wi-Fi address
- CI localhost-forwarding and loopback-binding checks
- Physical-device validation remains required

## M9 — Real server workloads
- CI installs Python 3 in the Alpine guest, runs a Python HTTP server, and verifies its response through localhost-only QEMU forwarding
- CI verifies SQLite database creation, commit, close, reopen, and row retrieval in the Alpine guest
- Physical-device validation of package installation and workloads remains required
