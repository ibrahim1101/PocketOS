#!/bin/sh
# BusyBox init runs this as root. The only request it accepts from pocketos is
# a clean guest poweroff, which lets init stop services and ext4 unmount before QEMU exits.
set -eu

request=/run/pocketos-shutdown-request
while :; do
  if [ -r "$request" ] && [ "$(cat "$request" 2>/dev/null || true)" = shutdown ]; then
    echo "PocketOS: powering off guest cleanly" >/dev/console
    echo "POCKETOS_SHUTDOWN_WATCHER_ACCEPTED" >/dev/console
    sync
    echo "POCKETOS_GUEST_SYNC_COMPLETE" >/dev/console
    # BusyBox's -f flag bypasses init and its shutdown actions. Let PID 1 run
    # the configured ::shutdown action and unmount filesystems before QEMU exits.
    /sbin/poweroff
    exit 0
  fi
  sleep 1
done
