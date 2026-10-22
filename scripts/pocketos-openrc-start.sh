#!/bin/sh
# BusyBox init runs this as an asynchronous `once` action. Wait until first-run
# account setup has reached getty before starting services; a slow service can
# never block setup or the serial login console.
set -eu

while [ ! -e /run/pocketos-console-ready ]; do
  sleep 1
done

exec >/dev/console 2>&1
/sbin/openrc sysinit
/sbin/openrc boot
/sbin/openrc default
