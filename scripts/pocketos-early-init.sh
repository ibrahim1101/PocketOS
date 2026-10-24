#!/bin/sh
# BusyBox init's sysinit hook. Make the persistent guest writable before
# OpenRC or the first-run account setup can modify it.
set -eu

if ! mount -o remount,rw /; then
  echo "PocketOS boot error: could not remount the guest root filesystem read/write" >/dev/console
  exit 1
fi

# Make kernel devices visible before OpenRC's deferred devfs startup so
# resize2fs can address the mounted VirtIO root device.
if ! awk '$2 == "/dev" { found = 1 } END { exit !found }' /proc/mounts; then
  mount -t devtmpfs -o mode=0755,nosuid devtmpfs /dev || true
fi

# Grow the mounted ext4 filesystem when Android has enlarged the backing image.
# resize2fs supports online ext4 expansion; failures are reported without
# making an otherwise bootable guest unusable.
if ! command -v resize2fs >/dev/null 2>&1; then
  echo "PocketOS warning: resize2fs is unavailable" >/dev/console
elif [ ! -b /dev/vda ]; then
  echo "PocketOS warning: VirtIO root device /dev/vda is unavailable" >/dev/console
elif resize2fs /dev/vda; then
  echo "POCKETOS_EXT4_RESIZE_COMPLETE" >/dev/console
else
  echo "PocketOS warning: ext4 auto-expansion failed" >/dev/console
fi

mkdir -p /run
if ! awk '$2 == "/run" { found = 1 } END { exit !found }' /proc/mounts; then
  mount -t tmpfs -o mode=0755,nosuid,nodev tmpfs /run
fi
mkdir -p /run/lock /var/lib/pocketos
# Permit the unprivileged pocketos account to request a root-mediated clean guest poweroff.
(umask 000; : > /run/pocketos-shutdown-request)
chmod 0666 /run/pocketos-shutdown-request
# OpenRC is deferred until the first authenticated shell, so initialize the
# configured hostname here for a stable serial login prompt and QEMU identity.
if [ -r /etc/hostname ] && [ -s /etc/hostname ]; then
  hostname "$(cat /etc/hostname)"
fi
# BusyBox init starts OpenRC asynchronously. Allow the first authenticated
# pocketos login to release it without running device services before getty.
(umask 000; : > /run/pocketos-user-login-ready)
chmod 0666 /run/pocketos-user-login-ready
echo "PocketOS: persistent root filesystem is writable" >/dev/console
