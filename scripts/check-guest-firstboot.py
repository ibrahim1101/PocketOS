#!/usr/bin/env python3
"""Exercise the Alpine first-boot credential setup and a real non-root console login."""
from __future__ import annotations

import argparse
import os
import pty
import re
import secrets
import select
import shutil
import subprocess
import sys
import tempfile
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


def sanitized_serial_tail(buffer: bytearray, password: str, limit: int = 2400) -> str:
    """Bound serial failure context and redact the ephemeral test credential."""
    transcript = bytes(buffer).decode("utf-8", errors="replace")
    transcript = transcript.replace(password, "[REDACTED]")
    transcript = re.sub(r"(?i)(password|passwd|token|secret|private[_ -]?key)(\s*[:=]\s*)\S+", r"\1\2[REDACTED]", transcript)
    transcript = re.sub(r"/(?:home/runner|tmp)/[^\s\r\n]+", "<host-path>", transcript)
    return transcript[-limit:]


def prepare_openrc_stall_image(source: str, delay_seconds: int) -> str:
    """Add a boot service that blocks OpenRC, proving it cannot hold up serial setup."""
    if shutil.which("debugfs") is None:
        raise RuntimeError("debugfs is required for the OpenRC stall regression")
    fd, image = tempfile.mkstemp(prefix="pocketos-openrc-stall-", suffix=".ext4")
    os.close(fd)
    shutil.copyfile(source, image)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False) as service:
        service.write(
            "#!/sbin/openrc-run\n"
            "depend() { before networking; }\n"
            "start() { : > /run/pocketos-openrc-stall-active; sleep "
            f"{delay_seconds}"
            "; }\n"
        )
        service_path = service.name
    try:
        commands = [
            f"write {service_path} /etc/init.d/pocketos-test-openrc-stall",
            "set_inode_field /etc/init.d/pocketos-test-openrc-stall mode 0100755",
            "symlink /etc/runlevels/boot/pocketos-test-openrc-stall /etc/init.d/pocketos-test-openrc-stall",
            "stat /etc/runlevels/boot/pocketos-test-openrc-stall",
        ]
        for command in commands:
            result = subprocess.run(
                ["debugfs", "-w", "-R", command, image],
                check=False,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
            )
            if result.returncode != 0 or (command.startswith("stat ") and "Type: symlink" not in result.stdout):
                raise RuntimeError("Could not prepare the blocked-OpenRC guest regression image")
    except Exception:
        os.unlink(image)
        raise
    finally:
        os.unlink(service_path)
    return image


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kernel", required=True)
    parser.add_argument("--initramfs", required=True)
    parser.add_argument("--rootfs", required=True)
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument(
        "--simulate-openrc-stall",
        action="store_true",
        help="inject a blocked boot service and require serial setup to continue independently",
    )
    args = parser.parse_args()

    for path in (args.kernel, args.initramfs, args.rootfs):
        if not os.path.isfile(path):
            parser.error(f"missing file: {path}")
    if args.timeout <= 0:
        parser.error("--timeout must be positive")

    rootfs = args.rootfs
    test_image = None
    if args.simulate_openrc_stall:
        test_image = prepare_openrc_stall_image(args.rootfs, max(args.timeout, 120))
        rootfs = test_image

    # Ephemeral test credential; never printed, written to disk by the host, or added to argv.
    password = "P" + secrets.token_hex(12)
    process, master_fd = launch(args.kernel, args.initramfs, rootfs)
    buffer = bytearray()
    first_boot_timeout = min(args.timeout, 15) if args.simulate_openrc_stall else args.timeout
    first_boot_completed = False
    stage = "first-run password prompt"
    try:
        wait_for(process, master_fd, buffer, b"New password:", first_boot_timeout)
        os.write(master_fd, password.encode("ascii") + b"\n")
        stage = "password confirmation"
        wait_for(process, master_fd, buffer, b"Retype password:", args.timeout)
        os.write(master_fd, password.encode("ascii") + b"\n")
        stage = "first-run completion marker"
        wait_for(process, master_fd, buffer, b"POCKETOS_FIRST_BOOT_COMPLETE", args.timeout)
        stage = "initial login prompt"
        wait_for(process, master_fd, buffer, b"pocketos login:", args.timeout)
        os.write(master_fd, b"pocketos\n")
        stage = "initial account password prompt"
        wait_for(process, master_fd, buffer, b"Password:", args.timeout)
        os.write(master_fd, password.encode("ascii") + b"\n")
        stage = "initial authenticated shell"
        wait_for(process, master_fd, buffer, b"$ ", args.timeout)
        if args.simulate_openrc_stall:
            os.write(
                master_fd,
                b'i=0; while [ "$i" -lt 60 ]; do '
                b'if test -f /run/pocketos-openrc-stall-active; then '
                b'echo __POCKETOS_OPENRC_STALL_ACTIVE__; break; fi; '
                b'sleep 1; i=$((i + 1)); done\n',
            )
            stage = "initial login while OpenRC boot service is blocked"
            wait_for(process, master_fd, buffer, b"__POCKETOS_OPENRC_STALL_ACTIVE__", min(args.timeout, 75))
        os.write(
            master_fd,
            b'test "$(id -un)" = pocketos && test "$(id -u)" != 0 '
            b'&& test -f /var/lib/pocketos/user-setup-complete '
            b'&& printf "pocketos-persistence-v1\\n" > "$HOME/.pocketos-persistence-probe" '
            b'&& sync && echo __POCKETOS_FIRST_LOGIN_SYNCED__\n',
        )
        stage = "first-login persistence write and sync"
        wait_for(process, master_fd, buffer, b"__POCKETOS_FIRST_LOGIN_SYNCED__", args.timeout)
        first_boot_completed = True
    except (RuntimeError, TimeoutError):
        print(f"First-boot account setup failed at {stage}.", file=sys.stderr)
        print(f"Sanitized serial tail: {sanitized_serial_tail(buffer, password)!r}", file=sys.stderr)
        return 1
    finally:
        stop(process, master_fd)
        if test_image is not None and not first_boot_completed:
            try:
                os.unlink(test_image)
            except FileNotFoundError:
                pass

    process, master_fd = launch(args.kernel, args.initramfs, rootfs)
    buffer = bytearray()
    stage = "login prompt after restart"
    try:
        wait_for(process, master_fd, buffer, b"pocketos login:", args.timeout)
        if b"New password:" in buffer:
            raise RuntimeError("first-run password setup repeated after restart")
        os.write(master_fd, b"pocketos\n")
        stage = "password prompt"
        wait_for(process, master_fd, buffer, b"Password:", args.timeout)
        os.write(master_fd, password.encode("ascii") + b"\n")
        stage = "authenticated user shell"
        wait_for(process, master_fd, buffer, b"$ ", args.timeout)
        if args.simulate_openrc_stall:
            os.write(
                master_fd,
                b'i=0; while [ "$i" -lt 60 ]; do '
                b'if test -f /run/pocketos-openrc-stall-active; then '
                b'echo __POCKETOS_OPENRC_STALL_ACTIVE__; break; fi; '
                b'sleep 1; i=$((i + 1)); done\n',
            )
            stage = "serial login while OpenRC boot service is blocked"
            wait_for(process, master_fd, buffer, b"__POCKETOS_OPENRC_STALL_ACTIVE__", min(args.timeout, 75))
        os.write(
            master_fd,
            b'test "$(id -un)" = pocketos && test "$(id -u)" != 0 '
            b'&& test -d "$HOME" && test "$PWD" = "$HOME" '
            b'&& test -f /var/lib/pocketos/user-setup-complete '
            b'&& test "$(cat "$HOME/.pocketos-persistence-probe")" = pocketos-persistence-v1 '
            b"&& echo __POCKETOS_PERSISTED_LOGIN_OK__\n",
        )
        stage = "persisted non-root shell, password, and file verification"
        wait_for(process, master_fd, buffer, b"__POCKETOS_PERSISTED_LOGIN_OK__", args.timeout)
    except (RuntimeError, TimeoutError):
        print(f"First-boot setup or same-disk reboot verification failed at {stage}.", file=sys.stderr)
        print(f"Sanitized serial tail: {sanitized_serial_tail(buffer, password)!r}", file=sys.stderr)
        return 1
    finally:
        stop(process, master_fd)
        if test_image is not None:
            try:
                os.unlink(test_image)
            except FileNotFoundError:
                pass

    if args.simulate_openrc_stall:
        print("First login and persisted second-boot login succeeded while an OpenRC boot service was blocked.")
    else:
        print("First-boot account setup, synced persistence file, and same-disk second-boot login succeeded.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
