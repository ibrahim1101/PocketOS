#!/usr/bin/env python3
"""Run real Linux commands in the Alpine guest over its QEMU serial console."""
from __future__ import annotations

import argparse
import os
import pty
import select
import subprocess
import sys
import time


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--kernel", required=True)
    parser.add_argument("--initramfs", required=True)
    parser.add_argument("--rootfs", required=True)
    parser.add_argument("--timeout", type=int, default=120)
    args = parser.parse_args()

    for path in (args.kernel, args.initramfs, args.rootfs):
        if not os.path.isfile(path):
            parser.error(f"missing file: {path}")

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
        "-kernel", args.kernel,
        "-initrd", args.initramfs,
        "-append", "console=ttyAMA0 root=/dev/vda rootfstype=ext4 rw rootwait init=/bin/sh",
        "-drive", f"if=none,file={args.rootfs},format=raw,id=rootdisk",
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
    buffer = bytearray()
    command_sent = False
    done = b"__POCKETOS_TEST_DONE__"
    deadline = time.monotonic() + args.timeout

    try:
        while done not in buffer:
            if process.poll() is not None:
                raise RuntimeError(f"QEMU exited with status {process.returncode}")
            if time.monotonic() >= deadline:
                raise TimeoutError(f"guest did not finish the command probe within {args.timeout}s")
            ready, _, _ = select.select([master_fd], [], [], 0.2)
            if not ready:
                continue
            try:
                chunk = os.read(master_fd, 8192)
            except BlockingIOError:
                continue
            if not chunk:
                continue
            buffer.extend(chunk)
            if not command_sent and b"~ # " in buffer:
                os.write(
                    master_fd,
                    b"echo __POCKETOS_UNAME_BEGIN__\nuname -s\necho __POCKETOS_UNAME_DONE__\ncat /etc/alpine-release\necho __POCKETOS_TEST_DONE__\n",
                )
                command_sent = True

        output = buffer.decode("utf-8", errors="replace")
        uname_begin = "__POCKETOS_UNAME_BEGIN__"
        uname_end = "__POCKETOS_UNAME_DONE__"
        start = output.rfind(uname_begin)
        end = output.find(uname_end, start + len(uname_begin)) if start >= 0 else -1
        uname_output = output[start + len(uname_begin):end] if start >= 0 and end >= 0 else ""
        if "Linux" not in [line.strip() for line in uname_output.splitlines()]:
            raise RuntimeError(f"guest uname command did not return Linux:\n{output[-4000:]}")
        release = next(
            (line.strip() for line in output.splitlines() if line.strip().startswith("3.")),
            None,
        )
        if release is None:
            raise RuntimeError(f"Alpine release was not printed:\n{output[-4000:]}")
        print(f"Guest uname succeeded; Alpine release: {release}")
        return 0
    except (RuntimeError, TimeoutError) as exc:
        print(str(exc), file=sys.stderr)
        print(buffer.decode("utf-8", errors="replace")[-4000:], file=sys.stderr)
        return 1
    finally:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        os.close(master_fd)


if __name__ == "__main__":
    raise SystemExit(main())
