# Linux guest experiment

The `scripts/boot-guest.sh` script is a **host-side** QEMU smoke-test harness, not an Android executable. Supply your own compatible ARM64 kernel `Image` and Alpine ARM64 root filesystem image. It boots a serial-only guest using QEMU `virt` machine and virtio block/network devices.

Example:

```sh
KERNEL=/absolute/path/Image ROOTFS=/absolute/path/alpine-aarch64.ext4 ./scripts/boot-guest.sh
```

The root filesystem must contain a working `/sbin/init` and appropriate serial-console login configuration. This script does not download unverified binaries, create an Alpine image, or claim to produce an Android VM. Do not use a Raspberry Pi-specific kernel image with the QEMU `virt` machine.
