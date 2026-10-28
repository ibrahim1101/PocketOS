#!/usr/bin/env bash
set -euo pipefail
failed=0
check() {
  if command -v "$1" >/dev/null 2>&1; then
    printf 'OK      %-24s %s\n' "$1" "$(command -v "$1")"
  else
    printf 'MISSING %s\n' "$1"
    failed=1
  fi
}
echo 'PocketOS M1 host prerequisites'
check qemu-system-aarch64
check docker
check adb
if command -v docker >/dev/null 2>&1; then
  if docker info >/dev/null 2>&1; then echo 'OK      Docker daemon available'; else echo 'MISSING Docker daemon unavailable'; failed=1; fi
fi
if command -v qemu-system-aarch64 >/dev/null 2>&1; then
  qemu-system-aarch64 --version | head -n 1
fi
if command -v adb >/dev/null 2>&1; then
  adb devices -l
fi
if (( failed )); then
  echo 'Install missing prerequisites before running M1 guest tests.' >&2
  exit 1
fi
echo 'Host prerequisites present; Linux guest boot not yet validated.'
