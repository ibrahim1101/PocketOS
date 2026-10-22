#!/usr/bin/env bash
set -euo pipefail

image="${1:?Usage: check-guest-root-lock.sh <guest.ext4>}"
[[ -f "$image" ]] || { echo "Guest disk not found: $image" >&2; exit 2; }
command -v debugfs >/dev/null || { echo "debugfs is required" >&2; exit 2; }

root_hash="$(debugfs -R "cat /etc/shadow" "$image" 2>/dev/null | awk -F: '$1 == "root" { print $2; found = 1; exit } END { if (!found) exit 1 }')"
if [[ "$root_hash" != '!'* ]]; then
  echo "Guest root account is not password-locked" >&2
  exit 1
fi

passwd_file="$(debugfs -R "cat /etc/passwd" "$image" 2>/dev/null)"
if grep -q "^pocketos:" <<< "$passwd_file"; then
  echo "Seed image must not contain a preconfigured PocketOS login account" >&2
  exit 1
fi
inittab="$(debugfs -R "cat /etc/inittab" "$image" 2>/dev/null)"
grep -Fq "ttyAMA0::respawn:/usr/local/sbin/pocketos-firstboot" <<< "$inittab" || {
  echo "Guest serial console is not wired to first-run account setup" >&2
  exit 1
}
grep -Fq "::sysinit:/usr/local/sbin/pocketos-early-init" <<< "$inittab" || {
  echo "Guest does not remount its persistent root before account setup" >&2
  exit 1
}
grep -Fq "::once:/usr/local/sbin/pocketos-openrc-start" <<< "$inittab" || {
  echo "Guest OpenRC startup is not isolated from serial console readiness" >&2
  exit 1
}
if grep -Eq '^::(sysinit|wait):/sbin/openrc (boot|default)' <<< "$inittab"; then
  echo "OpenRC boot/default must not block BusyBox init before the serial console" >&2
  exit 1
fi
setup="$(debugfs -R "cat /usr/local/sbin/pocketos-firstboot" "$image" 2>/dev/null)"
grep -Fq "passwd pocketos" <<< "$setup" || {
  echo "Guest first-run account setup does not set a password interactively" >&2
  exit 1
}
grep -Fq '( sleep 5; : > /run/pocketos-console-ready ) &' <<< "$setup" || {
  echo "Guest does not defer OpenRC until getty establishes the serial login prompt" >&2
  exit 1
}
early_init="$(debugfs -R "cat /usr/local/sbin/pocketos-early-init" "$image" 2>/dev/null)"
grep -Fq "mount -o remount,rw /" <<< "$early_init" || {
  echo "Guest early init does not make the persistent root writable" >&2
  exit 1
}
grep -Fq 'mount -t tmpfs -o mode=0755,nosuid,nodev tmpfs /run' <<< "$early_init" || {
  echo "Guest early init does not preserve volatile console coordination state" >&2
  exit 1
}
openrc_start="$(debugfs -R "cat /usr/local/sbin/pocketos-openrc-start" "$image" 2>/dev/null)"
for runlevel in sysinit boot default; do
  grep -Fq "/sbin/openrc $runlevel" <<< "$openrc_start" || {
    echo "Guest asynchronous OpenRC startup omits the $runlevel runlevel" >&2
    exit 1
  }
done
grep -Fq '/run/pocketos-console-ready' <<< "$openrc_start" || {
  echo "OpenRC starts before the serial setup has reached getty" >&2
  exit 1
}
for service in fsck root localmount; do
  debugfs -R "stat /etc/runlevels/boot/$service" "$image" 2>/dev/null | grep -Fq 'Type: symlink' || {
    echo "Required writable-root service is not enabled in the boot runlevel: $service" >&2
    exit 1
  }
done
echo "Guest root is locked; writable-root and non-blocking serial startup are configured."
