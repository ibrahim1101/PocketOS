#!/usr/bin/env python3
"""Verify Alpine guest writes survive a guest shutdown and QEMU restart."""
from __future__ import annotations

import argparse
import os
import pty
import select
import subprocess
import sys
import time

MARKER_PATH = "/var/lib/pocketos/persistence-probe"
MARKER_VALUE = "pocketos-persistence-v1"


def launch(kernel: str, initramfs: str, rootfs: str) -> tuple[subprocess.Popen[bytes], int]:
    master_fd, slave_fd = pty.openpty()
    command = [
        "qemu-system-aarch64",
        "-machine", "virt",
        "-cpu", "cortex-a72",
        "-smp", "1",
        "-m", "512M",
        "-display", "none",
        "-monitor", "none",
        "-serial", "stdio",
        "-no-reboot",
        "-kernel", kernel,
        "-initrd", initramfs,
        "-append", "console=ttyAMA0 root=/dev/vda rootfstype=ext4 rw rootwait init=/bin/sh",
        "-drive", f"if=none,file={rootfs},format=raw,id=rootdisk",
        "-device", "virtio-blk-device,drive=rootdisk",
    ]
    process = subprocess.Popen(
        command,
        stdin=slave_fd,
        stdout=slave_fd,
        stderr=slave_fd,
        close_fds=True,
        start_new_session=True,
    )
    os.close(slave_fd)
    os.set_blocking(master_fd, False)
    return process, master_fd


def read_until(
    process: subprocess.Popen[bytes],
    master_fd: int,
    token: bytes,
    deadline: float,
    buffer: bytearray,
) -> None:
    while token not in buffer:
        if process.poll() is not None:
            raise RuntimeError(f"QEMU exited with status {process.returncode} before {token.decode()}")
        if time.monotonic() >= deadline:
            raise TimeoutError(f"guest did not emit {token.decode()} before timeout")
        ready, _, _ = select.select([master_fd], [], [], 0.2)
        if ready:
            try:
                chunk = os.read(master_fd, 8192)
            except BlockingIOError:
                continue
            if chunk:
                buffer.extend(chunk)


def wait_for_shell(
    process: subprocess.Popen[bytes],
    master_fd: int,
    timeout: int,
    buffer: bytearray,
) -> None:
    read_until(process, master_fd, b"~ # ", time.monotonic() + timeout, buffer)


def stop(process: subprocess.Popen[bytes], master_fd: int, graceful: bool) -> None:
    try:
        if graceful:
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                pass
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
    finally:
        os.close(master_fd)


def run_phase(kernel: str, initramfs: str, rootfs: str, timeout: int, command: bytes, sentinel: bytes) -> None:
    process, master_fd = launch(kernel, initramfs, rootfs)
    buffer = bytearray()
    graceful = False
    try:
        wait_for_shell(process, master_fd, timeout, buffer)
        os.write(master_fd, command)
        read_until(process, master_fd, sentinel, time.monotonic() + timeout, buffer)
        graceful = True
    finally:
        stop(process, master_fd, graceful)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kernel", required=True)
    parser.add_argument("--initramfs", required=True)
    parser.add_argument("--rootfs", required=True)
    parser.add_argument("--timeout", type=int, default=120)
    args = parser.parse_args()

    for path in (args.kernel, args.initramfs, args.rootfs):
        if not os.path.isfile(path):
            parser.error(f"missing file: {path}")
    if args.timeout <= 0:
        parser.error("--timeout must be positive")

    write_done = b"__POCKETOS_PERSIST_WRITTEN__"
    run_phase(
        args.kernel,
        args.initramfs,
        args.rootfs,
        args.timeout,
        (
            f"mount -o remount,rw / && mkdir -p /var/lib/pocketos && printf '%s\\n' '{MARKER_VALUE}' > {MARKER_PATH} "
            f"&& sync && echo {write_done.decode()} && poweroff -f\n"
        ).encode(),
        write_done,
    )

    verify_done = b"__POCKETOS_PERSIST_OK__"
    process, master_fd = launch(args.kernel, args.initramfs, args.rootfs)
    buffer = bytearray()
    try:
        wait_for_shell(process, master_fd, args.timeout, buffer)
        command = (
            f"if mount -o remount,rw / && [ \"$(cat {MARKER_PATH} 2>/dev/null)\" = \"{MARKER_VALUE}\" ]; "
            f"then rm -f {MARKER_PATH}; sync; echo {verify_done.decode()}; "
            "else echo __POCKETOS_PERSIST_MISSING__; fi; echo __POCKETOS_PERSIST_DONE__\n"
        )
        os.write(master_fd, command.encode())
        read_until(process, master_fd, b"__POCKETOS_PERSIST_DONE__", time.monotonic() + args.timeout, buffer)
        if b"__POCKETOS_PERSIST_MISSING__" in buffer:
            raise RuntimeError("guest marker was missing after the QEMU restart")
        print("Guest ext4 write survived shutdown and QEMU restart.")
        return 0
    except (RuntimeError, TimeoutError) as exc:
        print(str(exc), file=sys.stderr)
        print(buffer.decode("utf-8", errors="replace")[-4000:], file=sys.stderr)
        return 1
    finally:
        stop(process, master_fd, graceful=False)


if __name__ == "__main__":
    raise SystemExit(main())
