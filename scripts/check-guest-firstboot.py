#!/usr/bin/env python3
"""Exercise the Alpine first-boot credential setup and a real non-root console login."""
from __future__ import annotations

import argparse
import os
import pty
import secrets
import select
import subprocess
import sys
import time


def launch(kernel: str, initramfs: str, rootfs: str):
    master_fd, slave_fd = pty.openpty()
    kernel_args = "console=ttyAMA0 root=/dev/vda rootfstype=ext4 rw rootwait"
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
        "-append", kernel_args,
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


def wait_for(process, master_fd: int, buffer: bytearray, token: bytes, timeout: int) -> None:
    deadline = time.monotonic() + timeout
    while token not in buffer:
        if process.poll() is not None:
            raise RuntimeError(f"QEMU exited with status {process.returncode} before an expected console prompt")
        if time.monotonic() >= deadline:
            raise TimeoutError("guest did not reach an expected console prompt")
        ready, _, _ = select.select([master_fd], [], [], 0.2)
        if ready:
            try:
                chunk = os.read(master_fd, 8192)
            except BlockingIOError:
                continue
            except OSError:
                continue
            if chunk:
                buffer.extend(chunk)


def stop(process, master_fd: int) -> None:
    try:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
    finally:
        os.close(master_fd)


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

    # Ephemeral test credential; never printed, written to disk by the host, or added to argv.
    password = "P" + secrets.token_hex(12)
    process, master_fd = launch(args.kernel, args.initramfs, args.rootfs)
    buffer = bytearray()
    try:
        wait_for(process, master_fd, buffer, b"New password:", args.timeout)
        os.write(master_fd, password.encode("ascii") + b"\n")
        wait_for(process, master_fd, buffer, b"Retype password:", args.timeout)
        os.write(master_fd, password.encode("ascii") + b"\n")
        wait_for(process, master_fd, buffer, b"pocketos login:", args.timeout)
    finally:
        stop(process, master_fd)

    process, master_fd = launch(args.kernel, args.initramfs, args.rootfs)
    buffer = bytearray()
    stage = "login prompt after restart"
    try:
        wait_for(process, master_fd, buffer, b"pocketos login:", args.timeout)
        os.write(master_fd, b"pocketos\n")
        stage = "password prompt"
        wait_for(process, master_fd, buffer, b"Password:", args.timeout)
        os.write(master_fd, password.encode("ascii") + b"\n")
        stage = "authenticated user shell"
        wait_for(process, master_fd, buffer, b"$ ", args.timeout)
        os.write(
            master_fd,
            b'test "$(id -un)" = pocketos && test "$(id -u)" != 0 '
            b"&& echo __POCKETOS_USER_LOGIN_OK__\n",
        )
        stage = "non-root shell verification"
        wait_for(process, master_fd, buffer, b"__POCKETOS_USER_LOGIN_OK__", args.timeout)
    except (RuntimeError, TimeoutError):
        # Do not print the serial transcript: it may contain terminal input on guest failure.
        print(f"First-boot account setup or non-root login failed at {stage}.", file=sys.stderr)
        return 1
    finally:
        stop(process, master_fd)

    print("First-boot account setup and non-root serial login succeeded.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
