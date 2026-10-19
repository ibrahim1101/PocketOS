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
setup="$(debugfs -R "cat /usr/local/sbin/pocketos-firstboot" "$image" 2>/dev/null)"
grep -Fq "passwd pocketos" <<< "$setup" || {
  echo "Guest first-run account setup does not set a password interactively" >&2
  exit 1
}
echo "Guest root is locked and first-run user setup is present."
