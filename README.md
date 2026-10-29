# PocketOS

A rootless, on-demand CLI Linux sandbox project for Android.

## Branch and validation status

The default branch is an earlier snapshot. Current development is on [`feat/m1-linux-boot`](https://github.com/ibrahim1101/PocketOS/tree/feat/m1-linux-boot). Updating this README does not merge that implementation into `main`.

The development branch contains Android ARM64 QEMU packaging, Alpine guest assets, a serial console and lifecycle/storage controls. Physical ARM64 guest boot, login, clean shutdown and persistence still require device acceptance; host/emulator CI does not prove success on Vivo hardware.

- [Development README](https://github.com/ibrahim1101/PocketOS/blob/feat/m1-linux-boot/README.md)
- [Development status](https://github.com/ibrahim1101/PocketOS/blob/feat/m1-linux-boot/docs/DEVELOPMENT_STATUS.md)
- [Physical testing](https://github.com/ibrahim1101/PocketOS/blob/feat/m1-linux-boot/docs/PHYSICAL_ANDROID_TESTING.md)

Review CI evidence at its exact commit. Source presence, hosted tests, physical-device acceptance and production readiness are separate evidence levels. No fresh runtime, deployment or installer acceptance was performed for this documentation update.

## This branch

The source in `main` is the early control-plane scaffold and stub engine. Goals include a 60-minute limit, a 20% battery cutoff and no Wi-Fi-disconnect shutdown. Do not interpret the development feature list as working QEMU support in this branch. Use the selected branch's own build instructions; toolchain requirements differ.
