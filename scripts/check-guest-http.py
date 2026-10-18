#!/usr/bin/env python3
"""Verify a guest HTTP service is reachable through localhost-only QEMU forwarding."""
from __future__ import annotations

import argparse
import os
import pty
import select
import subprocess
import sys
import termios
import time
import urllib.error
import urllib.request

HOST_PORT = 18080
GUEST_PORT = 8080
HTTP_BODY = b"PocketOS HTTP forwarding works\n"
SHELL_PROMPT = b"~ # "
SERVER_STARTED = b"__POCKETOS_HTTP_SERVER_STARTED__"


def launch(kernel: str, initramfs: str, rootfs: str) -> tuple[subprocess.Popen[bytes], int]:
    master_fd, slave_fd = pty.openpty()
    terminal_settings = termios.tcgetattr(slave_fd)
    terminal_settings[3] &= ~termios.ECHO
    termios.tcsetattr(slave_fd, termios.TCSANOW, terminal_settings)
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
        "-netdev", f"user,id=net0,hostfwd=tcp:127.0.0.1:{HOST_PORT}-:{GUEST_PORT}",
        "-device", "virtio-net-device,netdev=net0",
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
    buffer: bytearray,
    tokens: tuple[bytes, ...],
    deadline: float,
) -> bytes:
    while True:
        for token in tokens:
            if token in buffer:
                return token
        if process.poll() is not None:
            raise RuntimeError(f"QEMU exited with status {process.returncode}")
        if time.monotonic() >= deadline:
            raise TimeoutError(f"guest did not emit any expected marker: {tokens!r}")
        ready, _, _ = select.select([master_fd], [], [], 0.2)
        if ready:
            try:
                chunk = os.read(master_fd, 8192)
            except BlockingIOError:
                continue
            if chunk:
                buffer.extend(chunk)


def marker_command(token: bytes) -> str:
    midpoint = len(token) // 2
    first = token[:midpoint].decode("ascii")
    second = token[midpoint:].decode("ascii")
    return "printf '%s%s\\n' '" + first + "' '" + second + "'"


def send_checked(
    process: subprocess.Popen[bytes],
    master_fd: int,
    buffer: bytearray,
    command: str,
    success: bytes,
    timeout: int,
) -> None:
    failure = b"__POCKETOS_GUEST_COMMAND_FAILED__"
    payload = (
        f"if {command}; then {marker_command(success)}; "
        f"else {marker_command(failure)}; fi\n"
    )
    os.write(master_fd, payload.encode())
    observed = read_until(
        process, master_fd, buffer, (success, failure), time.monotonic() + timeout
    )
    if observed == failure:
        raise RuntimeError(f"guest setup command failed: {command}")

def stop(process: subprocess.Popen[bytes], master_fd: int) -> None:
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

    process, master_fd = launch(args.kernel, args.initramfs, args.rootfs)
    buffer = bytearray()
    try:
        read_until(process, master_fd, buffer, (SHELL_PROMPT,), time.monotonic() + args.timeout)
        send_checked(
            process, master_fd, buffer,
            "mount -o remount,rw / && mkdir -p /tmp/pocketos-www "
            "&& printf 'PocketOS HTTP forwarding works\\n' > /tmp/pocketos-www/index.html",
            b"__POCKETOS_HTTP_CONTENT_READY__", args.timeout,
        )
        send_checked(
            process, master_fd, buffer,
            "udhcpc -n -q -t 5 -T 1 -i eth0",
            b"__POCKETOS_DHCP_READY__", args.timeout,
        )

        start_server = (
            "busybox httpd -f -p 8080 -h /tmp/pocketos-www "
            ">/tmp/pocketos-httpd.log 2>&1 & "
            "echo $! >/tmp/pocketos-httpd.pid; sleep 1; "
            "kill -0 \"$(cat /tmp/pocketos-httpd.pid)\""
        )
        send_checked(process, master_fd, buffer, start_server, SERVER_STARTED, args.timeout)

        deadline = time.monotonic() + min(args.timeout, 45)
        last_error: Exception | None = None
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError(f"QEMU exited with status {process.returncode}")
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{HOST_PORT}/", timeout=2) as response:
                    body = response.read()
                    if response.status != 200 or body != HTTP_BODY:
                        raise RuntimeError(f"unexpected HTTP response: status={response.status}, body={body!r}")
                    print(f"Guest HTTP server returned the expected body through 127.0.0.1:{HOST_PORT}.")
                    return 0
            except (urllib.error.URLError, TimeoutError, OSError) as exc:
                last_error = exc
                time.sleep(0.5)
        raise TimeoutError(f"localhost forwarding did not return the guest response: {last_error}")
    except (RuntimeError, TimeoutError) as exc:
        print(str(exc), file=sys.stderr)
        print(buffer.decode("utf-8", errors="replace")[-4000:], file=sys.stderr)
        return 1
    finally:
        stop(process, master_fd)


if __name__ == "__main__":
    raise SystemExit(main())
