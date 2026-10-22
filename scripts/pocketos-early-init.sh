#!/bin/sh
# BusyBox init's sysinit hook. Make the persistent guest writable before
# OpenRC or the first-run account setup can modify it.
set -eu

if ! mount -o remount,rw /; then
  echo "PocketOS boot error: could not remount the guest root filesystem read/write" >/dev/console
  exit 1
fi

mkdir -p /run
if ! awk '$2 == "/run" { found = 1 } END { exit !found }' /proc/mounts; then
  mount -t tmpfs -o mode=0755,nosuid,nodev tmpfs /run
fi
mkdir -p /run/lock /var/lib/pocketos
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
