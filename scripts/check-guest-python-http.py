#!/usr/bin/env python3
"""Install Python in Alpine and serve a real Python HTTP response through QEMU."""
from __future__ import annotations

import argparse
import os
import pty
import select
import signal
import subprocess
import sys
import termios
import time
import urllib.request

HOST_PORT = 18082
GUEST_PORT = 8082
SHELL_PROMPT = b"~ # "
APK_LOG = "/tmp/pocketos-apk-install.log"
HTTP_BODY = b"PocketOS Python HTTP workload works\n"


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


def wait_for(
    process: subprocess.Popen[bytes],
    master_fd: int,
    buffer: bytearray,
    token: bytes,
    deadline: float,
) -> None:
    while token not in buffer:
        if process.poll() is not None:
            raise RuntimeError(f"QEMU exited with status {process.returncode}")
        if time.monotonic() >= deadline:
            raise TimeoutError(f"guest did not emit {token!r}")
        ready, _, _ = select.select([master_fd], [], [], 0.2)
        if ready:
            try:
                chunk = os.read(master_fd, 8192)
            except BlockingIOError:
                continue
            if chunk:
                buffer.extend(chunk)


def run_command(
    process: subprocess.Popen[bytes],
    master_fd: int,
    buffer: bytearray,
    command: str,
    label: str,
    timeout: int,
) -> None:
    marker = f"__POCKETOS_{label}__"
    midpoint = len(marker) // 2
    first, second = marker[:midpoint], marker[midpoint:]
    payload = (
        f"{command}; status=$?; "
        f"printf '%s%s:%s\\n' '{first}' '{second}' \"$status\"\n"
    )
    os.write(master_fd, payload.encode("utf-8"))
    status_marker = f"{marker}:".encode("ascii")
    deadline = time.monotonic() + timeout
    wait_for(process, master_fd, buffer, status_marker, deadline)
    marker_at = buffer.rfind(status_marker)
    line_end = buffer.find(b"\n", marker_at)
    if line_end < 0:
        wait_for(process, master_fd, buffer, b"\n", deadline)
        line_end = buffer.find(b"\n", marker_at)
    status = buffer[marker_at + len(status_marker):line_end].strip()
    if status != b"0":
        raise RuntimeError(f"guest command failed ({label}, status={status.decode(errors='replace')})")


def verify_loopback_listener(port: int) -> None:
    listeners: list[str] = []
    with open("/proc/net/tcp", encoding="ascii") as table:
        for line in table.read().splitlines()[1:]:
            fields = line.split()
            local_address, local_port = fields[1].split(":")
            if int(local_port, 16) == port and fields[3] == "0A":
                listeners.append(local_address.upper())
    if listeners != ["0100007F"]:
        raise RuntimeError(f"expected only a loopback listener on {port}; got {listeners!r}")


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
    parser.add_argument("--timeout", type=int, default=240)
    args = parser.parse_args()
    for path in (args.kernel, args.initramfs, args.rootfs):
        if not os.path.isfile(path):
            parser.error(f"missing file: {path}")
    if args.timeout <= 0:
        parser.error("--timeout must be positive")

    def handle_timeout(signum: int, frame: object) -> None:
        raise TimeoutError("Python guest workload received the CI watchdog signal")

    signal.signal(signal.SIGTERM, handle_timeout)

    print("Starting Alpine guest for Python workload validation.", flush=True)
    process, master_fd = launch(args.kernel, args.initramfs, args.rootfs)
    buffer = bytearray()
    try:
        wait_for(process, master_fd, buffer, SHELL_PROMPT, time.monotonic() + args.timeout)
        print("Guest shell ready; configuring DHCP.", flush=True)
        run_command(
            process, master_fd, buffer,
            "ip link set eth0 up && timeout 30 udhcpc -n -q -t 5 -T 1 -i eth0",
            "DHCP_READY", min(args.timeout, 60),
        )
        print("Guest networking ready; installing Python 3.", flush=True)
        run_command(
            process, master_fd, buffer,
            f"apk update >{APK_LOG} 2>&1 && apk add --no-progress python3 >>{APK_LOG} 2>&1 "
            f"|| {{ tail -n 40 {APK_LOG}; false; }}",
            "PYTHON_INSTALLED", args.timeout,
        )
        print("Python 3 installed; verifying interpreter.", flush=True)
        run_command(
            process, master_fd, buffer,
            "python3 -c 'import sys; assert sys.version_info.major == 3; print(sys.version)'",
            "PYTHON_RUNS", args.timeout,
        )
        print("Starting Python HTTP server in guest.", flush=True)
        run_command(
            process, master_fd, buffer,
            "mkdir -p /tmp/pocketos-python-www && "
            "printf 'PocketOS Python HTTP workload works\\n' > /tmp/pocketos-python-www/index.html && "
            f"python3 -m http.server {GUEST_PORT} --bind 0.0.0.0 --directory /tmp/pocketos-python-www "
            ">/tmp/pocketos-python-http.log 2>&1 & "
            "echo $! >/tmp/pocketos-python-http.pid; sleep 2; "
            "kill -0 \"$(cat /tmp/pocketos-python-http.pid)\"",
            "PYTHON_SERVER_READY", args.timeout,
        )
        verify_loopback_listener(HOST_PORT)
        print("Python HTTP server started; checking localhost forwarding.", flush=True)
        deadline = time.monotonic() + min(args.timeout, 45)
        last_error: Exception | None = None
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError(f"QEMU exited with status {process.returncode}")
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{HOST_PORT}/", timeout=2) as response:
                    body = response.read()
                    if response.status != 200 or body != HTTP_BODY:
                        raise RuntimeError(f"unexpected Python HTTP response: {response.status} {body!r}")
                    run_command(process, master_fd, buffer, "sync", "DISK_SYNCED", args.timeout)
                    print("Alpine installed Python 3 and served HTTP through localhost-only QEMU forwarding.")
                    return 0
            except Exception as exc:  # Retry briefly while the guest server initializes.
                last_error = exc
                time.sleep(0.5)
        raise TimeoutError(f"Python HTTP response did not arrive: {last_error}")
    except (RuntimeError, TimeoutError) as exc:
        print(str(exc), file=sys.stderr)
        print(buffer.decode("utf-8", errors="replace")[-4000:], file=sys.stderr)
        return 1
    finally:
        stop(process, master_fd)


if __name__ == "__main__":
    raise SystemExit(main())
