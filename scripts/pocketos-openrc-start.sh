#!/bin/sh
# BusyBox init runs this as an asynchronous `once` action. Wait until the first
# authenticated pocketos shell is ready before starting services; a slow
# service can never block first-run setup, the login prompt, or that shell.
set -eu

state=/run/pocketos-openrc-start-state
trap 'rm -f "$state"' EXIT
trap 'exit 0' HUP INT TERM

while ! grep -qx ready /run/pocketos-user-login-ready; do
  if [ "$(cat /run/pocketos-shutdown-request 2>/dev/null || true)" = shutdown ]; then
    exit 0
  fi
  sleep 1
done

exec >/dev/console 2>&1
printf starting > "$state"
for runlevel in sysinit boot default; do
  [ "$(cat /run/pocketos-shutdown-request 2>/dev/null || true)" = shutdown ] && exit 0
  /sbin/openrc "$runlevel"
done
