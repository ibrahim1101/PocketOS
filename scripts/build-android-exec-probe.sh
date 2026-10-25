#!/usr/bin/env bash
# Build PIE executables for APK native-library packaging and Android launch testing.
# The emulator test launches the x86_64 copy from the installed app library directory.
set -euo pipefail

ndk_root="${1:?usage: build-android-exec-probe.sh <ndk-root> <output-root>}"
output_root="${2:?usage: build-android-exec-probe.sh <ndk-root> <output-root>}"
toolchain="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin"
source_file="$(cd "$(dirname "$0")" && pwd)/android-exec-probe.c"

for tool in "$toolchain/llvm-readelf" "$toolchain/aarch64-linux-android24-clang" "$toolchain/x86_64-linux-android24-clang"; do
  if [[ ! -x "$tool" ]]; then
    echo "Required Android NDK tool is missing: $tool" >&2
    exit 2
  fi
done

for abi in arm64-v8a x86_64; do
  case "$abi" in
    arm64-v8a) compiler="$toolchain/aarch64-linux-android24-clang" ;;
    x86_64) compiler="$toolchain/x86_64-linux-android24-clang" ;;
    *) echo "Unsupported ABI: $abi" >&2; exit 2 ;;
  esac

  abi_dir="$output_root/$abi"
  mkdir -p "$abi_dir"
  executable="$abi_dir/libpocketos_exec_probe.so"
  "$compiler" -std=c11 -Wall -Wextra -Werror -fPIE -pie \
    -Wl,--dynamic-linker=/system/bin/linker64 "$source_file" -o "$executable"

  if ! "$toolchain/llvm-readelf" -h "$executable" | grep -Eq 'Type:.*DYN'; then
    echo "Expected an Android PIE executable for $abi" >&2
    exit 1
  fi
  if ! "$toolchain/llvm-readelf" -l "$executable" | grep -q 'Requesting program interpreter'; then
    echo "Android PIE executable for $abi has no program interpreter" >&2
    exit 1
  fi
  chmod 755 "$executable"
done
