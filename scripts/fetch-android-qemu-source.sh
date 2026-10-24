#!/usr/bin/env bash
# Fetch the pinned upstream QEMU source archive for the Android build spike.
set -euo pipefail

QEMU_VERSION="11.1.2"
QEMU_SHA256="731b5681e4bb18be313231579b8efd0296c5b015fa36dc533874b639ba838016"
DESTINATION="${1:-$PWD/out/qemu-$QEMU_VERSION}"
ARCHIVE_URL="https://download.qemu.org/qemu-$QEMU_VERSION.tar.xz"

if [[ -e "$DESTINATION" ]]; then
  echo "Refusing to overwrite $DESTINATION" >&2
  exit 2
fi
for tool in curl sha256sum tar; do
  command -v "$tool" >/dev/null || { echo "$tool is required" >&2; exit 2; }
done

parent="$(dirname "$DESTINATION")"
mkdir -p "$parent"
parent="$(cd "$parent" && pwd)"
destination="$parent/$(basename "$DESTINATION")"
work_dir="$(mktemp -d "$parent/.qemu-$QEMU_VERSION.XXXXXX")"
trap 'rm -rf "$work_dir"' EXIT

archive="$work_dir/qemu-$QEMU_VERSION.tar.xz"
curl --fail --location --retry 3 --output "$archive" "$ARCHIVE_URL"
printf '%s  %s\n' "$QEMU_SHA256" "$archive" | sha256sum --check --status

tar -tf "$archive" > "$work_dir/archive-files.txt"
if ! awk -F/ -v expected="qemu-$QEMU_VERSION" '
  NF == 0 || $1 != expected { invalid = 1 }
  END { exit invalid }
' "$work_dir/archive-files.txt"; then
  echo "Archive contains paths outside qemu-$QEMU_VERSION/" >&2
  exit 1
fi
if ! grep -Fxq "qemu-$QEMU_VERSION/configure" "$work_dir/archive-files.txt"; then
  echo "Pinned archive is missing its configure script" >&2
  exit 1
fi

mkdir "$work_dir/unpacked"
tar -xf "$archive" --no-same-owner -C "$work_dir/unpacked"
mv "$work_dir/unpacked/qemu-$QEMU_VERSION" "$destination"
echo "Verified QEMU $QEMU_VERSION source at $destination"
