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

echo "Guest root account is password-locked."
