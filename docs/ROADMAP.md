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
