#!/usr/bin/env python3
"""Verify that the debug APK contains correctly targeted PocketOS JNI libraries."""

from __future__ import annotations

import argparse
import re
import subprocess
import tempfile
import zipfile
from pathlib import Path

EXPECTED_MACHINES = {
    "lib/arm64-v8a/libpocketos_vm.so": "AArch64",
    "lib/x86_64/libpocketos_vm.so": "Advanced Micro Devices X86-64",
    "lib/arm64-v8a/libqemu-system-aarch64.so": "AArch64",
}
ANDROID_SYSTEM_LIBRARIES = {
    "libandroid.so", "libatomic.so", "libc.so", "libdl.so",
    "liblog.so", "libm.so", "libz.so",
}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("llvm_readelf", type=Path)
    args = parser.parse_args()

    if not args.apk.is_file():
        parser.error(f"APK does not exist: {args.apk}")
    if not args.llvm_readelf.is_file():
        parser.error(f"llvm-readelf does not exist: {args.llvm_readelf}")

    with zipfile.ZipFile(args.apk) as apk, tempfile.TemporaryDirectory() as temp_dir:
        names = set(apk.namelist())
        for archive_path, expected_machine in EXPECTED_MACHINES.items():
            if archive_path not in names:
                raise SystemExit(f"APK is missing {archive_path}")

            library = Path(temp_dir) / Path(archive_path).name
            library.write_bytes(apk.read(archive_path))
            result = subprocess.run(
                [str(args.llvm_readelf), "-h", str(library)],
                check=True,
                capture_output=True,
                text=True,
            )
            machine_lines = [
                line.split(":", 1)[1].strip()
                for line in result.stdout.splitlines()
                if line.strip().startswith("Machine:")
            ]
            if machine_lines != [expected_machine]:
                raise SystemExit(
                    f"{archive_path}: expected ELF machine {expected_machine!r}, "
                    f"found {machine_lines!r}"
                )
            if archive_path.endswith("libqemu-system-aarch64.so"):
                if not re.search(r"Type:\s+DYN", result.stdout):
                    raise SystemExit(f"{archive_path}: expected a PIE (ELF type DYN)")
                program_headers = subprocess.run(
                    [str(args.llvm_readelf), "-l", str(library)],
                    check=True,
                    capture_output=True,
                    text=True,
                )
                if "/system/bin/linker64" not in program_headers.stdout:
                    raise SystemExit(f"{archive_path}: expected Android /system/bin/linker64")

            dynamic = subprocess.run(
                [str(args.llvm_readelf), "-d", str(library)],
                check=True,
                capture_output=True,
                text=True,
            )
            needed = re.findall(r"\(NEEDED\).*?\[([^\]]+)\]", dynamic.stdout)
            search_paths = re.findall(
                r"\((?:RPATH|RUNPATH)\).*?\[([^\]]*)\]", dynamic.stdout
            )
            if search_paths:
                raise SystemExit(
                    f"{archive_path}: unexpected ELF runtime search paths {search_paths!r}"
                )
            if any("/data/data/com.termux" in item or "termux-build" in item for item in needed):
                raise SystemExit(f"{archive_path}: Termux runtime dependency detected: {needed!r}")
            if any(item.startswith("/") for item in needed):
                raise SystemExit(f"{archive_path}: absolute ELF dependency detected: {needed!r}")
            unknown = sorted(set(needed) - ANDROID_SYSTEM_LIBRARIES)
            if unknown:
                raise SystemExit(
                    f"{archive_path}: non-system or unreviewed Android dependencies {unknown!r}"
                )
            print(
                f"{archive_path}: ELF machine {machine_lines[0]}; "
                f"DT_NEEDED={', '.join(needed) or '(none)'}; no RPATH/RUNPATH"
            )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
