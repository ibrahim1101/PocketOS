#!/bin/sh
# One-time non-root login provisioning. This script runs as root on the guest serial TTY.
set -eu

# Fail visibly before account setup if early init did not make persistent storage writable.
if ! (umask 077; : > /var/lib/pocketos/.write-check); then
  printf 'PocketOS boot error: persistent guest storage is not writable.\n'
  sleep 5
  exit 1
fi
rm -f /var/lib/pocketos/.write-check

# Android can grow the raw image; expand mounted ext4 before login.
if command -v resize2fs >/dev/null 2>&1; then
  resize2fs /dev/vda || printf 'PocketOS: ext4 auto-grow failed; disk remains available.\n'
fi

marker=/var/lib/pocketos/user-setup-complete
if [ ! -e "$marker" ]; then
  printf '\nPocketOS first boot: creating the pocketos account\n'
  if ! id pocketos >/dev/null 2>&1; then
    if ! adduser -D -h /home/pocketos -s /bin/ash pocketos; then
      printf 'PocketOS boot error: could not create the pocketos account.\n'
      sleep 5
      exit 1
    fi
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
