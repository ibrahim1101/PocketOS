# PocketOS M1 — Windows emulator setup

PocketOS uses two test environments. Start with QEMU in WSL2 to prove that the Linux guest boots. Android Emulator tests come after the host boot is validated.

## Windows host setup

Open an elevated PowerShell terminal to install WSL2 if it is not already installed:

```powershell
wsl --install -d Ubuntu
```

Restart if requested, then open Ubuntu. Install host dependencies:

```bash
sudo apt update
sudo apt install -y qemu-system-arm qemu-utils docker.io android-tools-adb
qemu-system-aarch64 --version
```

Docker must be started and configured to run ARM64 containers. On Windows, Docker Desktop with WSL integration is an alternative to the Ubuntu Docker daemon. Test:

```bash
docker info
docker run --rm --platform linux/arm64 alpine:3.22 uname -m
```

The last command must print `aarch64`. If it fails with an exec-format error, configure ARM64 binfmt emulation or use a native ARM64 builder.

Clone the repo and run:

```bash
git clone https://github.com/ibrahim1101/PocketOS.git
cd PocketOS
git checkout feat/m1-linux-boot
bash scripts/check-host-prereqs.sh
bash scripts/build-alpine-rootfs.sh
```

The rootfs builder generates an ext4 disk image, **not** a kernel or Android APK. The first kernel boot still requires a verified QEMU-virt-compatible ARM64 Linux Image with PL011 serial, virtio-mmio and ext4 support. Follow `linux/README.md` for the current boot command.

## Android Emulator (second test environment)

Install Android Studio and the Android SDK Platform Tools. In Device Manager, create a recent Pixel virtual device using an x86_64 system image on an x86_64 Windows host. This is suitable for the Kotlin Android control plane, but **cannot run an ARM64-only native QEMU executable**. The complete Linux VM will eventually need an Android-compatible QEMU build for the emulator architecture, or a physical ARM64 phone.

In PowerShell:

```powershell
adb devices -l
```

From Android Studio, open the repository's `android/` project. This starter does not include a Gradle wrapper and has not yet been verified to build. Resolve Gradle sync and build errors before attempting to install the debug APK.

## Verification gates

1. Alpine disk image successfully created.
2. ARM64 Linux kernel boots through host-side QEMU into a serial login prompt.
3. CI writes a marker to the ext4 guest disk, powers Alpine off, restarts QEMU with the same disk, and verifies the marker survives.
4. HTTP forwarding works through 127.0.0.1:8080.
5. Android APK builds and launches in the emulator.
6. Android lifecycle and widget behaviors pass tests.
7. Actual native Android QEMU integration is tested separately.

Never treat a working Android status screen as proof that the Linux guest is running.
