#!/usr/bin/env bash
# Build an Alpine ARM64 root disk for QEMU's virt machine.
# Requirements: Docker with linux/arm64 support (native or binfmt emulation).
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${1:-$PWD/out/alpine-aarch64.ext4}"
SIZE_MB="${SIZE_MB:-512}"
ALPINE_TAG="${ALPINE_TAG:-3.22}"
if [[ ! "$SIZE_MB" =~ ^[0-9]+$ ]] || (( SIZE_MB < 256 )); then
  echo "SIZE_MB must be an integer >= 256" >&2
  exit 2
fi
if [[ "$ALPINE_TAG" == "edge" ]]; then
  ALPINE_REPOSITORY_BRANCH="edge"
elif [[ "$ALPINE_TAG" =~ ^[0-9]+\.[0-9]+$ ]]; then
  ALPINE_REPOSITORY_BRANCH="v$ALPINE_TAG"
else
  echo "ALPINE_TAG must be a stable major.minor version (for example 3.22) or edge" >&2
  exit 2
fi
command -v docker >/dev/null || { echo "Docker is required" >&2; exit 2; }
mkdir -p "$(dirname "$OUT")"
OUT="$(cd "$(dirname "$OUT")" && pwd)/$(basename "$OUT")"
[[ ! -e "$OUT" ]] || { echo "Refusing to overwrite $OUT" >&2; exit 2; }
echo "Building Alpine $ALPINE_TAG ARM64 disk ($SIZE_MB MiB)..."
docker run --rm --platform linux/arm64 -e SIZE_MB -e ALPINE_REPOSITORY_BRANCH="$ALPINE_REPOSITORY_BRANCH" -e OUTPUT_UID="$(id -u)" -e OUTPUT_GID="$(id -g)" -v "$(dirname "$OUT"):/output" -v "$SCRIPT_DIR:/source/scripts:ro" -e OUTPUT_NAME="$(basename "$OUT")" "alpine:$ALPINE_TAG" sh -euc '
  apk add --no-cache e2fsprogs
  mkdir -p /guest/etc/apk/keys /guest/etc/network /guest/etc/profile.d /guest/etc/runlevels/default /guest/etc/runlevels/boot /guest/etc/runlevels/sysinit
  cp /etc/apk/keys/* /guest/etc/apk/keys/
  printf "https://dl-cdn.alpinelinux.org/alpine/%s/main\nhttps://dl-cdn.alpinelinux.org/alpine/%s/community\n" "$ALPINE_REPOSITORY_BRANCH" "$ALPINE_REPOSITORY_BRANCH" > /guest/etc/apk/repositories
  apk --root /guest --initdb --arch aarch64 add --no-cache alpine-base openrc busybox-openrc busybox-extras util-linux e2fsprogs e2fsprogs-extra linux-virt
  # Leave no usable root password in the distributed seed image.
  sed -i "s/^root:[^:]*:/root:!:/" /guest/etc/shadow
  grep -q "^root:!:" /guest/etc/shadow
  printf "pocketos\n" > /guest/etc/hostname
  printf "auto lo\niface lo inet loopback\nauto eth0\niface eth0 inet dhcp\n" > /guest/etc/network/interfaces
  mkdir -p /guest/usr/local/sbin /guest/usr/local/bin
  install -m 755 /source/scripts/pocketos-early-init.sh /guest/usr/local/sbin/pocketos-early-init
  install -m 755 /source/scripts/pocketos-openrc-start.sh /guest/usr/local/sbin/pocketos-openrc-start
  install -m 755 /source/scripts/pocketos-firstboot.sh /guest/usr/local/sbin/pocketos-firstboot
  install -m 755 /source/scripts/pocketos-shutdown-watch.sh /guest/usr/local/sbin/pocketos-shutdown-watch
  install -m 755 /source/scripts/pocketos-shutdown.sh /guest/usr/local/bin/pocketos-shutdown
  cat > /guest/etc/profile.d/pocketos-openrc.sh <<EOF
# Release asynchronous OpenRC only after the first authenticated guest login.
if [ "\$(id -un)" = pocketos ] && [ -w /run/pocketos-user-login-ready ]; then
  echo ready > /run/pocketos-user-login-ready
fi
EOF
  chmod 644 /guest/etc/profile.d/pocketos-openrc.sh
  # Establish a writable guest root before account setup, then keep OpenRC service
  # startup off the serial first-boot path. OpenRC waits for the first authenticated
  # pocketos shell so neither boot services nor device setup can hide the console.
  cat > /guest/etc/inittab <<EOF
::sysinit:/usr/local/sbin/pocketos-early-init
::once:/usr/local/sbin/pocketos-shutdown-watch
::once:/usr/local/sbin/pocketos-openrc-start
ttyAMA0::respawn:/usr/local/sbin/pocketos-firstboot
::ctrlaltdel:/sbin/reboot
::shutdown:/sbin/openrc shutdown
EOF
  ln -sf /etc/init.d/networking /guest/etc/runlevels/boot/networking
  ln -sf /etc/init.d/hostname /guest/etc/runlevels/boot/hostname
  ln -sf /etc/init.d/fsck /guest/etc/runlevels/boot/fsck
  ln -sf /etc/init.d/root /guest/etc/runlevels/boot/root
  ln -sf /etc/init.d/localmount /guest/etc/runlevels/boot/localmount
  ln -sf /etc/init.d/devfs /guest/etc/runlevels/sysinit/devfs
  ln -sf /etc/init.d/procfs /guest/etc/runlevels/sysinit/procfs
  ln -sf /etc/init.d/sysfs /guest/etc/runlevels/sysinit/sysfs
  # Root login disabled by default: set a password offline before remote access.
  truncate -s "${SIZE_MB}M" "/output/$OUTPUT_NAME"
  mkfs.ext4 -q -F -d /guest "/output/$OUTPUT_NAME"
  chown "$OUTPUT_UID:$OUTPUT_GID" "/output/$OUTPUT_NAME"
  chmod 644 "/output/$OUTPUT_NAME"
  echo "Guest disk created."
'
echo "Output: $OUT"
echo "The ext4 disk contains Alpine's linux-virt kernel and matching modules."
echo "Extract /boot/vmlinuz-virt and boot it with scripts/boot-guest.sh."
