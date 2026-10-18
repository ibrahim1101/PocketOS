#!/usr/bin/env bash
# Boot the Alpine disk under QEMU and require its serial login prompt.
set -euo pipefail
: "${KERNEL:?Set KERNEL to the ARM64 Linux Image extracted from the guest disk}"
: "${ROOTFS:?Set ROOTFS to the Alpine ARM64 ext4 disk image}"
BOOT_TIMEOUT_SECONDS="${BOOT_TIMEOUT_SECONDS:-120}"
for path in "$KERNEL" "$ROOTFS"; do
  [[ -f "$path" ]] || { echo "Missing file: $path" >&2; exit 2; }
done
command -v qemu-system-aarch64 >/dev/null || { echo "qemu-system-aarch64 is required" >&2; exit 2; }
command -v timeout >/dev/null || { echo "timeout is required" >&2; exit 2; }
[[ "$BOOT_TIMEOUT_SECONDS" =~ ^[0-9]+$ ]] || { echo "BOOT_TIMEOUT_SECONDS must be an integer" >&2; exit 2; }
log="$(mktemp)"
trap 'rm -f "$log"' EXIT
set +e
timeout "$BOOT_TIMEOUT_SECONDS" qemu-system-aarch64 \
  -machine virt -cpu cortex-a72 -smp 1 -m 512M \
  -display none -monitor none -serial stdio -no-reboot \
  -kernel "$KERNEL" \
  -append 'console=ttyAMA0 root=/dev/vda rw rootwait' \
  -drive "if=none,file=$ROOTFS,format=raw,id=rootdisk" \
  -device virtio-blk-device,drive=rootdisk \
  -netdev user,id=net0,hostfwd=tcp:127.0.0.1:8080-:8080 \
  -device virtio-net-device,netdev=net0 >"$log" 2>&1
status=$?
set -e
if (( status != 0 && status != 124 )); then
  cat "$log" >&2
  echo "QEMU exited unexpectedly with status $status" >&2
  exit 1
fi
if ! grep -Fq "pocketos login:" "$log"; then
  cat "$log" >&2
  echo "Guest did not reach the expected serial login prompt" >&2
  exit 1
fi
grep -F "pocketos login:" "$log" | tail -n 1
echo "Alpine guest reached its serial login prompt."
