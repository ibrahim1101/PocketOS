#!/bin/sh
# BusyBox init runs this as an asynchronous `once` action. Wait until the first
# authenticated pocketos shell is ready before starting services; a slow
# service can never block first-run setup, the login prompt, or that shell.
set -eu

while ! grep -qx ready /run/pocketos-user-login-ready; do
  sleep 1
done

exec >/dev/console 2>&1
/sbin/openrc sysinit
/sbin/openrc boot
/sbin/openrc default
