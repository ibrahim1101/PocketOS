#!/usr/bin/env bash
# Build an Alpine ARM64 root disk for QEMU's virt machine.
# Requirements: Docker with linux/arm64 support (native or binfmt emulation).
set -euo pipefail
OUT="${1:-$PWD/out/alpine-aarch64.ext4}"
SIZE_MB="${SIZE_MB:-512}"
ALPINE_TAG="${ALPINE_TAG:-3.22}"
[[ "$SIZE_MB" =~ ^[0-9]+$ ]] && (( SIZE_MB >= 256 )) || { echo "SIZE_MB must be >= 256" >&2; exit 2; }
command -v docker >/dev/null || { echo "Docker is required" >&2; exit 2; }
mkdir -p "$(dirname "$OUT")"
OUT="$(cd "$(dirname "$OUT")" && pwd)/$(basename "$OUT")"
[[ ! -e "$OUT" ]] || { echo "Refusing to overwrite $OUT" >&2; exit 2; }
echo "Building Alpine $ALPINE_TAG ARM64 disk ($SIZE_MB MiB)..."
docker run --rm --platform linux/arm64 -e SIZE_MB -v "$(dirname "$OUT"):/output" -e OUTPUT_NAME="$(basename "$OUT")" "alpine:$ALPINE_TAG" sh -euc '
  apk add --no-cache e2fsprogs
  mkdir -p /guest /output
  apk --root /guest --initdb --arch aarch64 add --no-cache alpine-base openrc busybox-initscripts util-linux
  mkdir -p /guest/etc/network /guest/etc/runlevels/default /guest/etc/runlevels/boot /guest/etc/runlevels/sysinit
  printf "pocketos\n" > /guest/etc/hostname
  printf "auto lo\niface lo inet loopback\nauto eth0\niface eth0 inet dhcp\n" > /guest/etc/network/interfaces
  printf "ttyAMA0::respawn:/sbin/getty -L 115200 ttyAMA0 vt100\n" >> /guest/etc/inittab
  ln -sf /etc/init.d/networking /guest/etc/runlevels/boot/networking
  ln -sf /etc/init.d/hostname /guest/etc/runlevels/boot/hostname
  ln -sf /etc/init.d/devfs /guest/etc/runlevels/sysinit/devfs
  ln -sf /etc/init.d/procfs /guest/etc/runlevels/sysinit/procfs
  ln -sf /etc/init.d/sysfs /guest/etc/runlevels/sysinit/sysfs
  # Root login disabled by default: set a password offline before remote access.
  truncate -s "${SIZE_MB}M" "/output/$OUTPUT_NAME"
  mkfs.ext4 -q -F -d /guest "/output/$OUTPUT_NAME"
  echo "Guest disk created."
'
echo "Output: $OUT"
echo "NOTE: this is a disk image, not a kernel. Supply an ARM64 Image separately."
echo "NOTE: validate init, login, network and virtio support with an actual QEMU boot."
