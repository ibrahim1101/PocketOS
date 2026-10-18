#!/usr/bin/env bash
# Extract a compact Alpine kernel, initramfs, and compressed ext4 seed disk for APK assets.
set -euo pipefail

disk="${1:?usage: package-android-guest-assets.sh <rootfs.ext4> <output-dir>}"
output="${2:?usage: package-android-guest-assets.sh <rootfs.ext4> <output-dir>}"

if [[ ! -f "$disk" ]]; then
  echo "Guest disk does not exist: $disk" >&2
  exit 2
fi
if [[ -e "$output" ]]; then
  echo "Refusing to overwrite guest asset directory: $output" >&2
  exit 2
fi

output_parent="$(dirname "$output")"
mkdir -p "$output_parent"
output_parent="$(cd "$output_parent" && pwd)"
stage="$(mktemp -d "$output_parent/.pocketos-guest-assets.XXXXXX")"
cleanup() {
  rm -rf "$stage"
}
trap cleanup EXIT

debugfs -R "dump /boot/vmlinuz-virt $stage/vmlinuz-virt" "$disk" >/dev/null
debugfs -R "dump /boot/initramfs-virt $stage/initramfs-virt" "$disk" >/dev/null
gzip -9 -n -c "$disk" > "$stage/rootfs.ext4.gz"

for asset in vmlinuz-virt initramfs-virt rootfs.ext4.gz; do
  if [[ ! -s "$stage/$asset" ]]; then
    echo "Generated guest asset is empty: $asset" >&2
    exit 1
  fi
done
(
  cd "$stage"
  sha256sum vmlinuz-virt initramfs-virt rootfs.ext4.gz > assets.sha256
)
mv "$stage" "$output"
trap - EXIT
echo "Packaged Alpine kernel, initramfs, and compressed root disk: $output"
