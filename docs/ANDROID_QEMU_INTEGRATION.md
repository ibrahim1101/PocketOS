# Android QEMU integration

Status: QEMU integration remains investigation only. CI has verified that PocketOS can package and launch a small Android PIE executable from its installed native-library directory on an x86_64 emulator; no QEMU binary has been built or run by PocketOS.

## Evidence and constraints

- QEMU's documented supported build hosts include Linux, macOS, BSD, and Windows; Android is not listed: [QEMU supported build platforms](https://www.qemu.org/docs/master/about/build-platforms.html).
- Termux maintains a QEMU system-emulation package for Android ARM64. Its headless build enables the `aarch64-softmmu` target and links Android-specific libraries, while the package depends on the Termux prefix and its package set: [Termux headless QEMU build](https://github.com/termux/termux-packages/blob/master/packages/qemu-system-x86-64-headless/build.sh).
- For apps targeting Android 10 or later, Android blocks `execve()` of files inside the writable app home directory and advises loading code embedded in the APK: [Android 10 behavior changes](https://developer.android.com/about/versions/10/behavior-changes-10).
- Android's NDK packages native libraries by ABI inside the app package: [Android ABIs](https://developer.android.com/ndk/guides/abis).

These points show that Android ARM64 QEMU builds are possible in a Termux environment, but a Termux executable cannot simply be copied into PocketOS's writable files directory and launched with `ProcessBuilder`. CI run [#105](https://github.com/ibrahim1101/PocketOS/actions/runs/39157789567) now proves a separately built PIE executable packaged as an APK native library can be launched from the installed native-library directory on an Android x86_64 emulator. This does not establish QEMU compatibility, ARM64-device execution, or guest boot on Android.

## Android build reference snapshot

The Termux package recipe is a useful compatibility reference, but it does not produce an app-ready PocketOS artifact:

- The recipe snapshot inspected for this spike is Termux packages commit `42efb53f075008cf93e4dc262159002966dfcb87`, at [qemu-system-x86-64-headless/build.sh](https://github.com/termux/termux-packages/blob/42efb53f075008cf93e4dc262159002966dfcb87/packages/qemu-system-x86-64-headless/build.sh).
- That recipe builds QEMU `11.1.2` from the upstream release archive with SHA-256 `731b5681e4bb18be313231579b8efd0296c5b015fa36dc533874b639ba838016`. Its target list includes `aarch64-softmmu`.
- The recipe also carries Android compatibility patches and expects the Termux package prefix and dependency set. Reusing its produced executable would keep those environment assumptions and would not meet PocketOS's embedded JNI loading model.

## Pinned source acquisition

`scripts/fetch-android-qemu-source.sh` now provides a reproducible source-only step for QEMU 11.1.2. It downloads the upstream release archive over HTTPS, checks the pinned SHA-256 (`731b5681e4bb18be313231579b8efd0296c5b015fa36dc533874b639ba838016`), validates the archive root and QEMU configure script, and refuses to overwrite an existing destination. CI run [#100](https://github.com/ibrahim1101/PocketOS/actions/runs/39155206068) executes the fetch step and verifies the extracted configure script. This makes the source input reproducible; it does not configure, compile, or package QEMU, and does not change the Android runtime status above.

The next build spike should start from the pinned upstream release and explicitly carry only the required compatibility patches. It should produce a JNI-loadable shared library for `arm64-v8a`, with the QEMU main loop isolated behind start, readiness, and stop entry points. The Termux build output remains a reference for portability fixes, not a runtime dependency.

## First integration spike

Test a PocketOS-owned Android NDK build of QEMU `aarch64-softmmu` as a headless PIE executable and package it as a native library in the APK. Launch and supervise it as a child process of the dedicated `:vm` service; do not call an unmodified command-line `main()` from JNI. Keep an alternate JNI integration only if Android-specific process behavior or QEMU lifecycle constraints require it.

The manifest now assigns `ServerService` to the app's dedicated `:vm` process, and emulator instrumentation verifies the resolved process name. The service still fails closed and does not load or start QEMU. Keep VM execution in this dedicated Android process so native failures do not take down the dashboard process. Expose a narrow host API for start, readiness, graceful stop, force-stop, and diagnostics. Use a serial byte channel between the VM process and the Android terminal; use QMP or another documented QEMU control channel for shutdown. Keep guest disks under app-private persistent storage and never execute copied guest or QEMU binaries from that storage.

## Proof gates

1. Build pinned QEMU 11.1.2 for Android `arm64-v8a` with only required headless dependencies.
2. Verify the output ABI and dynamic dependencies; reject Termux-prefix paths and unresolved libraries.
3. Package the executable in the APK native-library directory and launch it under `:vm` on an ARM64 Android device.
4. Start the Alpine guest and observe a readiness signal from its serial output.
5. Bridge terminal bytes, request graceful shutdown, and verify the VM process exits.
6. Repeat on supported Android API levels and record cold boot time, memory, and native crash diagnostics.

Until these gates pass, PocketOS must keep Android VM startup disabled and must describe the QEMU integration as unimplemented.
