#!/bin/sh
# One-time non-root login provisioning. This script runs as root on the guest serial TTY.
set -eu

marker=/var/lib/pocketos/user-setup-complete
if [ ! -e "$marker" ]; then
  printf '\nPocketOS first boot: creating the pocketos account\n'
  if ! id pocketos >/dev/null 2>&1; then
    adduser -D -h /home/pocketos -s /bin/ash pocketos
  fi

  # passwd reads the new password twice from the controlling serial TTY with echo disabled.
  # Nothing secret is passed through argv, environment, files, or diagnostic output.
  until passwd pocketos; do
    printf 'Password setup failed. Please try again.\n'
  done

  mkdir -p /var/lib/pocketos
  marker_tmp="$marker.tmp.$$"
  (umask 077; : > "$marker_tmp")
  mv "$marker_tmp" "$marker"
  # Persist the password and completion marker before any restart can interrupt the guest.
  sync
  printf 'POCKETOS_FIRST_BOOT_COMPLETE\n'
fi

exec /sbin/getty -L 115200 ttyAMA0 vt100
