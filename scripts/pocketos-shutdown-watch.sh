#!/bin/sh
# BusyBox init runs this as root. The only request it accepts from pocketos is
# a clean guest poweroff, which lets ext4 unmount before QEMU exits.
set -eu

request=/run/pocketos-shutdown-request
while :; do
  if [ -r "$request" ] && [ "$(cat "$request" 2>/dev/null || true)" = shutdown ]; then
    echo "PocketOS: powering off guest cleanly" >/dev/console
    sync
    /sbin/poweroff -f
    exit 0
  fi
  sleep 1
done
