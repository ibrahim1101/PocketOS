#!/bin/sh
# Non-root request channel; BusyBox init's root-owned watcher performs poweroff.
set -eu

if [ "$(id -un)" != pocketos ]; then
  echo "Only the pocketos account can request this shutdown." >&2
  exit 1
fi
printf shutdown > /run/pocketos-shutdown-request
