# Physical ARM64 Android testing

This handoff is for the first PocketOS run on an unrooted ARM64 Android device. The APKs and instrumentation APK are built together in one GitHub Actions job from one commit. The workflow uploads build hashes and Android emulator test results. The Android NDK and local QEMU compilation are not required.

## Download the APK artifacts

Prerequisites on Windows 11: GitHub CLI gh authenticated for the public PocketOS repository, Android platform-tools adb, and USB debugging enabled on the Vivo I2501. Replace the run ID and commit SHA with the values from a successful CI run.

```powershell
$Repo = "ibrahim1101/PocketOS"
$RunId = "<successful GitHub Actions run ID>"
$Commit = "<full commit SHA from that run>"
$ArtifactDir = Join-Path $PWD "PocketOS-physical-test"
New-Item -ItemType Directory -Force $ArtifactDir | Out-Null

gh run download $RunId --repo $Repo --name "pocketos-android-apk-$Commit" --dir $ArtifactDir
gh run download $RunId --repo $Repo --name "pocketos-instrumentation-apk-$Commit" --dir $ArtifactDir
gh run download $RunId --repo $Repo --name "pocketos-test-handoff-$Commit" --dir $ArtifactDir

Get-FileHash (Join-Path $ArtifactDir "PocketOS-debug.apk") -Algorithm SHA256
Get-FileHash (Join-Path $ArtifactDir "PocketOS-androidTest.apk") -Algorithm SHA256
Get-Content (Join-Path $ArtifactDir "dist\build-info.txt")
Get-Content (Join-Path $ArtifactDir "dist\SHA256SUMS")
```

The workflow must show success before treating the APKs as a validated handoff. Check that build-info.txt contains the same commit SHA used in the artifact names.

## Install and run on the Vivo

Connect the intended phone, enable Developer options → USB debugging, approve the device prompt, and confirm Android reports the expected ARM64 device:

```powershell
$Adb = (Get-Command adb -ErrorAction Stop).Source
$Serial = "<serial shown by adb devices -l>"
$AppApk = Join-Path $ArtifactDir "PocketOS-debug.apk"
$TestApk = Join-Path $ArtifactDir "PocketOS-androidTest.apk"

& $Adb devices -l
& $Adb -s $Serial shell getprop ro.product.cpu.abi
& $Adb -s $Serial shell getprop ro.build.version.release
& $Adb -s $Serial install -r $AppApk
& $Adb -s $Serial install -r $TestApk
```

Expected ABI: arm64-v8a. Android 16 may report release 16 or API level 36.

```powershell
$ResultsDir = Join-Path $ArtifactDir "device-results"
New-Item -ItemType Directory -Force $ResultsDir | Out-Null
$TestOutput = & $Adb -s $Serial shell am instrument -w -r com.pocketos.app.test/androidx.test.runner.AndroidJUnitRunner 2>&1
$TestExitCode = $LASTEXITCODE
$TestText = ($TestOutput -join [Environment]::NewLine) -replace '(?im)(password|passwd|token|secret|private[_ -]?key)(\s*[:=]\s*)\S+', '$1$2[REDACTED]'
$TestText | Set-Content -Encoding utf8 (Join-Path $ResultsDir "instrumentation-results.txt")
$TestText
"Instrumentation exit code: $TestExitCode"
```

A zero exit code and OK (… tests) indicate the runner passed. ARM64 runtime tests must execute rather than show assumption skips. The guest test provisions a random password only on an isolated temporary copy of the packaged seed disk; it does not modify the user's persistent guest disk or print the password.

## Capture sanitized device diagnostics

```powershell
$LogLines = & $Adb -s $Serial logcat -d -v threadtime -s PocketOS:D AndroidRuntime:E TestRunner:I InstrumentationResultPrinter:I 2>&1
$LogText = ($LogLines -join [Environment]::NewLine) -replace '(?im)(password|passwd|token|secret|private[_ -]?key)(\s*[:=]\s*)\S+', '$1$2[REDACTED]'
$LogText | Set-Content -Encoding utf8 (Join-Path $ResultsDir "logcat-sanitized.txt")

$ServiceInfo = & $Adb -s $Serial shell dumpsys activity services com.pocketos.app 2>&1
($ServiceInfo -join [Environment]::NewLine) | Set-Content -Encoding utf8 (Join-Path $ResultsDir "service-state.txt")
```

After tests, open PocketOS → Diagnostics → Export report and save pocketos-diagnostics.txt to Downloads. Retrieve and bundle the results:

```powershell
& $Adb -s $Serial pull /sdcard/Download/pocketos-diagnostics.txt (Join-Path $ResultsDir "pocketos-diagnostics.txt")
Compress-Archive -Path (Join-Path $ResultsDir "*") -DestinationPath (Join-Path $ArtifactDir "device-results.zip") -Force
```

Review the files before sharing. Do not attach passwords, private keys, or an exported guest disk to a bug report.

## Test checklist and expected results

1. QEMU native launch: the executable runs from Android's extracted ARM64 native-library directory, reports QEMU 11.1.2, and exits 0.
2. Asset integrity and disk initialization: the app-private installer initializes a valid ext4 seed; the test verifies the compressed seed hash before creating its isolated copy.
3. Guest boot and serial readiness: Alpine reaches New password: within 180 seconds and the readiness probe recognizes it.
4. First-run setup and login: the test sends an ephemeral random credential over serial, waits for setup completion, logs in as pocketos, and verifies a non-root UID.
5. Persistence: the test performs QMP quit, boots the same isolated disk again, logs in, and verifies the setup marker and non-root account persist.
6. Graceful shutdown: both QMP quit requests complete, QEMU exits with status 0, and disk leases release.
7. Failure diagnostics: a separate Android instrumentation test verifies stderr capture and nonzero child-process exit status.
8. Runtime gate: only successful QEMU execution and guest-boot tests record version-scoped evidence. If either ARM64 test fails or is skipped, the PocketOS start gate remains closed.

Host QEMU and Android x86_64 emulator runs do not establish physical ARM64 compatibility. The Vivo test is the first device-level proof. Report actual outputs and failures before enabling real PocketOS sessions.
