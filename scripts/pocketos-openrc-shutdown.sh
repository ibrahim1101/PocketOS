#!/bin/sh
# BusyBox init executes this action before its bounded process teardown. Do not
# run OpenRC shutdown concurrently with its asynchronous startup: both contend
# for OpenRC's state lock and a slow boot service can otherwise hang poweroff.
set -u

state=/run/pocketos-openrc-start-state
waited=0
while [ -e "$state" ] && [ "$waited" -lt 5 ]; do
  sleep 1
  waited=$((waited + 1))
done

if [ -e "$state" ]; then
  echo "PocketOS: OpenRC startup still active; skipping competing service shutdown" >/dev/console
else
  echo "PocketOS: stopping OpenRC services" >/dev/console
  /sbin/openrc shutdown &
  openrc_pid=$!
  waited=0
  timed_out=0
  while kill -0 "$openrc_pid" 2>/dev/null && [ "$waited" -lt 8 ]; do
    sleep 1
    waited=$((waited + 1))
  done
  if kill -0 "$openrc_pid" 2>/dev/null; then
    timed_out=1
    echo "PocketOS: OpenRC shutdown exceeded 8 seconds; continuing bounded init shutdown" >/dev/console
    kill -TERM "$openrc_pid" 2>/dev/null || true
    sleep 1
    kill -KILL "$openrc_pid" 2>/dev/null || true
  fi
  # Do not wait on a killed task here: even an uninterruptible OpenRC process
  # must not keep PID 1 from advancing to its own process teardown.
  if [ "$timed_out" -eq 0 ]; then
    wait "$openrc_pid" 2>/dev/null || true
  fi
fi

# BusyBox init will then stop remaining processes and sync again before halt.
sync
echo "POCKETOS_INIT_SHUTDOWN_ACTION_COMPLETE" >/dev/console
