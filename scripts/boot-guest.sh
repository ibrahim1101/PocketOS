#!/usr/bin/env bash
set -euo pipefail
: "${KERNEL:?Set KERNEL to a compatible ARM64 Linux Image}"
: "${ROOTFS:?Set ROOTFS to a writable ARM64 Linux ext4 disk image}"
command -v qemu-system-aarch64 >/dev/null || { echo 'qemu-system-aarch64 not installed' >&2; exit 1; }
[[ -f "$KERNEL" && -f "$ROOTFS" ]] || { echo 'Missing kernel or rootfs' >&2; exit 1; }
exec qemu-system-aarch64   -machine virt -cpu cortex-a72 -smp 2 -m 256M   -nographic -monitor none -serial mon:stdio   -kernel "$KERNEL" -append 'console=ttyAMA0 root=/dev/vda rw rootwait'   -drive "if=none,file=$ROOTFS,format=raw,id=rootdisk"   -device virtio-blk-device,drive=rootdisk   -netdev user,id=net0,hostfwd=tcp:127.0.0.1:8080-:8080   -device virtio-net-device,netdev=net0
