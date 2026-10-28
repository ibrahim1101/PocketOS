# Linux guest experiment

This is a **host-side QEMU** test, not Android integration. The Alpine ARM64 disk builder installs Alpine's signed `linux-virt` kernel package, its matching modules, and the initramfs. It writes a 512 MiB ext4 disk by default and leaves the guest root account locked.

## Build and boot

Requirements: Docker with ARM64 emulation, QEMU system emulation for AArch64, and `debugfs` from e2fsprogs.

```sh
SIZE_MB=512 bash scripts/build-alpine-rootfs.sh out/alpine-aarch64.ext4
debugfs -R "dump /boot/vmlinuz-virt out/vmlinuz-virt" out/alpine-aarch64.ext4
debugfs -R "dump /boot/initramfs-virt out/initramfs-virt" out/alpine-aarch64.ext4
KERNEL="$PWD/out/vmlinuz-virt" \
INITRAMFS="$PWD/out/initramfs-virt" \
ROOTFS="$PWD/out/alpine-aarch64.ext4" \
  bash scripts/boot-guest.sh
```

The VM uses QEMU's `virt` machine, PL011 serial console, VirtIO block and network devices, and ext4. The initramfs is required to load VirtIO and ext4 modules before mounting `/dev/vda`.

Run the bounded CI smoke check with:

```sh
KERNEL=/absolute/path/vmlinuz-virt \
INITRAMFS=/absolute/path/initramfs-virt \
ROOTFS=/absolute/path/alpine-aarch64.ext4 \
BOOT_TIMEOUT_SECONDS=120 \
  bash scripts/check-guest-boot.sh
```

The smoke check requires the guest to reach the `pocketos login:` serial prompt. CI has verified that boot path with host-side QEMU. The root account remains locked, so this does not yet verify interactive shell login or command execution.

Do not use a Raspberry Pi-specific kernel with QEMU's `virt` machine. Android-native QEMU, persistent restart behavior, and guest networking are not verified.
