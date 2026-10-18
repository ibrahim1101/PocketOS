#!/usr/bin/env bash
# Boot the Alpine disk under QEMU and require its serial login prompt.
set -euo pipefail
: "${KERNEL:?Set KERNEL to the ARM64 Linux Image extracted from the guest disk}"
: "${INITRAMFS:?Set INITRAMFS to the matching Alpine initramfs image}"
: "${ROOTFS:?Set ROOTFS to the Alpine ARM64 ext4 disk image}"
BOOT_TIMEOUT_SECONDS="${BOOT_TIMEOUT_SECONDS:-120}"
for path in "$KERNEL" "$INITRAMFS" "$ROOTFS"; do
  [[ -f "$path" ]] || { echo "Missing file: $path" >&2; exit 2; }
done
command -v qemu-system-aarch64 >/dev/null || { echo "qemu-system-aarch64 is required" >&2; exit 2; }
[[ "$BOOT_TIMEOUT_SECONDS" =~ ^[0-9]+$ ]] || { echo "BOOT_TIMEOUT_SECONDS must be an integer" >&2; exit 2; }
log="$(mktemp)"
trap 'rm -f "$log"' EXIT
qemu-system-aarch64 \
  -machine virt -cpu cortex-a72 -smp 1 -m 512M \
  -display none -monitor none -serial stdio -no-reboot \
  -kernel "$KERNEL" -initrd "$INITRAMFS" \
  -append 'console=ttyAMA0 root=/dev/vda rw rootwait' \
  -drive "if=none,file=$ROOTFS,format=raw,id=rootdisk" \
  -device virtio-blk-device,drive=rootdisk \
  -netdev user,id=net0,hostfwd=tcp:127.0.0.1:8080-:8080 \
  -device virtio-net-device,netdev=net0 >"$log" 2>&1 &
qemu_pid=$!
deadline=$((SECONDS + BOOT_TIMEOUT_SECONDS))
while kill -0 "$qemu_pid" 2>/dev/null && (( SECONDS < deadline )); do
  if grep -Fq "pocketos login:" "$log"; then
    break
  fi
  sleep 1
done
if grep -Fq "pocketos login:" "$log"; then
  kill -TERM "$qemu_pid" 2>/dev/null || true
  wait "$qemu_pid" 2>/dev/null || true
  grep -F "pocketos login:" "$log" | tail -n 1
  echo "Alpine guest reached its serial login prompt."
  exit 0
fi
kill -TERM "$qemu_pid" 2>/dev/null || true
wait "$qemu_pid" 2>/dev/null || true
cat "$log" >&2
echo "Guest did not reach the expected serial login prompt within ${BOOT_TIMEOUT_SECONDS}s" >&2
exit 1
