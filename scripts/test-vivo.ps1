param(
  [Parameter(Mandatory = $true)][string]$RunId,
  [string]$Serial = "",
  [string]$OutputDirectory = (Join-Path $PWD "PocketOS-vivo-results")
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "Sanitize-Diagnostics.ps1")
$Repo = "ibrahim1101/PocketOS"
$Commit = ""
$Failed = [System.Collections.Generic.List[string]]::new()
$Results = [System.Collections.Generic.List[object]]::new()
$TestMayBeRunning = $false
$ExistingJournals = @()
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$ArtifactDirectory = Join-Path $OutputDirectory "artifacts"
New-Item -ItemType Directory -Force -Path $ArtifactDirectory | Out-Null

function ConvertTo-CommandLineArgument([string]$Value) {
  if ($Value.Length -gt 0 -and $Value -notmatch '[\s"]') { return $Value }
  $builder = [System.Text.StringBuilder]::new()
  [void]$builder.Append('"')
  $slashes = 0
  foreach ($character in $Value.ToCharArray()) {
    if ($character -eq '\') { $slashes++; continue }
    if ($character -eq '"') {
      [void]$builder.Append(('\' * (2 * $slashes + 1)))
      [void]$builder.Append('"')
      $slashes = 0
      continue
    }
    [void]$builder.Append(('\' * $slashes))
    [void]$builder.Append($character)
    $slashes = 0
  }
  [void]$builder.Append(('\' * (2 * $slashes)))
  [void]$builder.Append('"')
  return $builder.ToString()
}

function Invoke-Bounded([string]$File, [string[]]$Arguments, [int]$TimeoutSeconds, [scriptblock]$OnHeartbeat = $null) {
  $info = [System.Diagnostics.ProcessStartInfo]::new()
  $info.FileName = $File
  $info.Arguments = (($Arguments | ForEach-Object { ConvertTo-CommandLineArgument $_ }) -join ' ')
  $info.UseShellExecute = $false
  $info.CreateNoWindow = $true
  $info.RedirectStandardOutput = $true
  $info.RedirectStandardError = $true
  $process = [System.Diagnostics.Process]::new()
  $process.StartInfo = $info
  if (-not $process.Start()) { throw "Could not start $File" }
  $stdoutTask = $process.StandardOutput.ReadToEndAsync()
  $stderrTask = $process.StandardError.ReadToEndAsync()
  $clock = [System.Diagnostics.Stopwatch]::StartNew()
  $nextHeartbeatMs = 10000L
  while (-not $process.HasExited -and $clock.ElapsedMilliseconds -lt ($TimeoutSeconds * 1000L)) {
    $remainingMs = ($TimeoutSeconds * 1000L) - $clock.ElapsedMilliseconds
    if ($process.WaitForExit([int][Math]::Max(0L, [Math]::Min(1000L, $remainingMs)))) { break }
    if ($OnHeartbeat -and $clock.ElapsedMilliseconds -ge $nextHeartbeatMs) {
      try { & $OnHeartbeat } catch { Write-Warning 'Host heartbeat collection failed; supervision continues' }
      $nextHeartbeatMs = $clock.ElapsedMilliseconds + 10000L
    }
  }
  $timedOut = -not $process.HasExited
  if ($timedOut) {
    try { $process.Kill() } catch { }
    [void]$process.WaitForExit(5000)
  }
  if (-not $process.HasExited) { throw "$File could not be stopped after its timeout" }
  if (-not $stdoutTask.Wait(5000) -or -not $stderrTask.Wait(5000)) {
    throw "$File output pipes did not close after bounded process termination"
  }
  $stdout = $stdoutTask.GetAwaiter().GetResult()
  $stderr = $stderrTask.GetAwaiter().GetResult()
  [pscustomobject]@{
    ExitCode = if ($timedOut) { 124 } else { $process.ExitCode }
    TimedOut = $timedOut
    Output = (($stdout + [Environment]::NewLine + $stderr) -replace '(?im)(password|passwd|token|secret|private[_ -]?key)(\s*[:=]\s*)\S+', '$1$2[REDACTED]')
  }
}

function Invoke-Adb([string[]]$Arguments, [int]$TimeoutSeconds = 90, [scriptblock]$OnHeartbeat = $null) {
  return Invoke-Bounded $script:Adb $Arguments $TimeoutSeconds $OnHeartbeat
}

# ADB joins remote arguments into a command parsed by Android's outer shell.
# Windows argv quoting alone does not preserve the inner sh -c script.
function ConvertTo-AndroidShellArgument([string]$Value) {
  return "'" + $Value.Replace("'", "'\''") + "'"
}

function Invoke-AppShell([string]$DeviceSerial, [string]$Script, [int]$TimeoutSeconds = 5) {
  $remote = "run-as com.pocketos.app sh -c " + (ConvertTo-AndroidShellArgument $Script)
  return Invoke-Adb @("-s", $DeviceSerial, "shell", $remote) $TimeoutSeconds
}

function Get-AppJournalBaseline([string]$DeviceSerial, [string]$Directory = 'files/diagnostics') {
  $path = ConvertTo-AndroidShellArgument $Directory
  return Invoke-AppShell $DeviceSerial "if [ -d $path ]; then ls -1 $path; fi" 5
}

function Stop-DeviceTestProcesses([string]$DeviceSerial) {
  $actions = [System.Collections.Generic.List[string]]::new()
  foreach ($package in @("com.pocketos.app.test", "com.pocketos.app")) {
    $stop = Invoke-Adb @("-s", $DeviceSerial, "shell", "am", "force-stop", $package) 20
    $actions.Add("force-stop $package exit=$($stop.ExitCode) timedOut=$($stop.TimedOut)")
  }
  # run-as limits this signal to PocketOS's UID, so a host timeout can clear a
  # native child even if force-stopping the app package did not reap it.
  $kill = Invoke-AppShell $DeviceSerial 'for pid in $(pidof libqemu-system-aarch64.so 2>/dev/null); do kill -KILL "$pid" 2>/dev/null || true; done' 20
  $actions.Add("qemu-child-cleanup exit=$($kill.ExitCode) timedOut=$($kill.TimedOut)")
  $state = Invoke-Adb @("-s", $DeviceSerial, "shell", "ps", "-A") 20
  $remaining = @($state.Output -split '\r?\n' | Where-Object { $_ -match 'libqemu-system-aarch64\.so' })
  $actions.Add("remaining-qemu=$($remaining.Count)")
  $actions.AddRange([string[]]$remaining)
  return ($actions -join [Environment]::NewLine)
}

function Save-Text([string]$Name, [string]$Text) {
  $path = Join-Path $OutputDirectory $Name
  $safe = ConvertTo-PocketOsSanitizedText -Text $Text -PrivatePaths @($env:USERPROFILE, $env:LOCALAPPDATA, $OutputDirectory)
  $safe | Set-Content -Encoding utf8 $path
  return $path
}

try {
  $gh = (Get-Command gh -ErrorAction Stop).Source
  $script:Adb = (Get-Command adb -ErrorAction Stop).Source
  $runResult = Invoke-Bounded $gh @("run", "view", $RunId, "--repo", $Repo, "--json", "headSha,headBranch,conclusion,event") 90
  if ($runResult.ExitCode -ne 0) { throw "Could not inspect the selected GitHub Actions run: $($runResult.Output)" }
  $run = $runResult.Output | ConvertFrom-Json
  if ($run.conclusion -ne "success" -or $run.headBranch -ne "feat/m1-linux-boot" -or $run.event -ne "push") {
    throw "Select a successful push run from feat/m1-linux-boot; this run is $($run.event)/$($run.headBranch)/$($run.conclusion)."
  }
  $Commit = $run.headSha

  foreach ($name in @("pocketos-android-apk-$Commit", "pocketos-instrumentation-apk-$Commit", "pocketos-test-handoff-$Commit")) {
    $download = Invoke-Bounded $gh @("run", "download", $RunId, "--repo", $Repo, "--name", $name, "--dir", $ArtifactDirectory) 300
    if ($download.ExitCode -ne 0) { throw "Artifact download failed for $name`: $($download.Output)" }
  }

  $buildInfoFile = Get-ChildItem -Path $ArtifactDirectory -Filter "build-info.txt" -Recurse | Select-Object -First 1
  $sumsFile = Get-ChildItem -Path $ArtifactDirectory -Filter "SHA256SUMS" -Recurse | Select-Object -First 1
  $appFile = Get-ChildItem -Path $ArtifactDirectory -Filter "PocketOS-debug.apk" -Recurse | Select-Object -First 1
  $testFile = Get-ChildItem -Path $ArtifactDirectory -Filter "PocketOS-androidTest.apk" -Recurse | Select-Object -First 1
  if (-not ($buildInfoFile -and $sumsFile -and $appFile -and $testFile)) { throw "Downloaded artifacts are missing APKs or build identity files." }
  $buildInfo = @{}
  Get-Content $buildInfoFile.FullName | ForEach-Object {
    if ($_ -match '^([^=]+)=(.*)$') { $buildInfo[$matches[1]] = $matches[2] }
  }
  if ($buildInfo.commit -ne $Commit -or $buildInfo.'signing-kind' -ne "cached-branch-debug") {
    throw "Artifact commit or signing kind does not match the trusted branch run."
  }
  foreach ($apk in @($appFile, $testFile)) {
    $expectedLine = Get-Content $sumsFile.FullName | Where-Object { $_ -match ([regex]::Escape($apk.Name) + '$') } | Select-Object -First 1
    if (-not $expectedLine -or $expectedLine -notmatch '^([0-9a-fA-F]{64})\s+') { throw "No SHA-256 entry found for $($apk.Name)." }
    $expectedHash = $matches[1].ToLowerInvariant()
    $actualHash = (Get-FileHash $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($expectedHash -ne $actualHash) { throw "SHA-256 verification failed for $($apk.Name)." }
  }

  $sdkRoot = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
  $apksigner = Get-ChildItem -Path (Join-Path $sdkRoot "build-tools") -Filter "apksigner.bat" -Recurse | Sort-Object { [version]($_.Directory.Name -replace '-.*$', '') } -Descending | Select-Object -First 1
  if (-not $apksigner) { throw "Android SDK apksigner.bat was not found under $sdkRoot." }
  $apksignerJar = Join-Path $apksigner.Directory.FullName "lib\apksigner.jar"
  if (-not (Test-Path $apksignerJar)) { throw "Android SDK apksigner.jar was not found beside apksigner.bat." }
  $java = (Get-Command java -ErrorAction Stop).Source
  $signatures = @{}
  foreach ($apk in @($appFile, $testFile)) {
    $verify = Invoke-Bounded $java @("-jar", $apksignerJar, "verify", "--print-certs", $apk.FullName) 60
    if ($verify.ExitCode -ne 0 -or $verify.Output -notmatch '(?im)Signer #1 certificate SHA-256 digest:\s*([0-9a-f]+)') {
      throw "APK signature verification failed for $($apk.Name)."
    }
    $signatures[$apk.Name] = $matches[1].ToLowerInvariant()
  }
  $expectedCert = ($buildInfo.'signing-certificate-sha256' -replace '\s', '').ToLowerInvariant()
  if ($signatures[$appFile.Name] -ne $expectedCert -or $signatures[$testFile.Name] -ne $expectedCert) {
    throw "App/test APK signing certificates do not match the CI signing metadata."
  }
  Save-Text "artifact-verification.txt" ("commit=$Commit`nsigning-kind=$($buildInfo.'signing-kind')`nsigning-certificate-sha256=$expectedCert`napp-sha256=$((Get-FileHash $appFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant())`ntest-sha256=$((Get-FileHash $testFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant())") | Out-Null

  $devices = Invoke-Adb @("devices", "-l") 30
  if ($devices.ExitCode -ne 0) { throw "ADB device discovery failed: $($devices.Output)" }
  if (-not $Serial) {
    $online = @($devices.Output -split '\r?\n' | Where-Object { $_ -match '^\S+\s+device\b' })
    if ($online.Count -ne 1) { throw "ADB reports $($online.Count) online devices; connect exactly one or pass -Serial." }
    $Serial = ($online[0] -split '\s+')[0]
  }
  $abi = Invoke-Adb @("-s", $Serial, "shell", "getprop", "ro.product.cpu.abi") 30
  if ($abi.ExitCode -ne 0 -or $abi.Output.Trim() -ne "arm64-v8a") { throw "Expected an arm64-v8a device; ADB reported '$($abi.Output.Trim())'." }
  $androidRelease = (Invoke-Adb @("-s", $Serial, "shell", "getprop", "ro.build.version.release") 30).Output.Trim()
  Save-Text "device-info.txt" "abi=$($abi.Output.Trim())`nandroid-release=$androidRelease" | Out-Null

  foreach ($apk in @($appFile, $testFile)) {
    $install = Invoke-Adb @("-s", $Serial, "install", "-r", $apk.FullName) 180
    Save-Text "install-$($apk.Name).txt" $install.Output | Out-Null
    if ($install.ExitCode -ne 0) { throw "Install failed for $($apk.Name); no packages were uninstalled. $($install.Output)" }
  }

  $tests = @(
    # One end-to-end run validates native QEMU launch, first boot, authenticated
    # non-root access, synced disk persistence, same-disk reboot, and cleanup.
    @{ Name = "alpine-guest-workflow"; Class = "bootsFreshAlpineSetsPasswordLogsInPersistsAndPowersOffGuest"; Timeout = 300 }
  )
  foreach ($test in $tests) {
    # Snapshot journal names before this run so a preflight failure cannot export
    # older boot logs as if they belonged to the current candidate.
    $baseline = Get-AppJournalBaseline $Serial
    Save-Text "journal-baseline.txt" "exit=$($baseline.ExitCode) timedOut=$($baseline.TimedOut)`n$($baseline.Output)" | Out-Null
    if ($baseline.ExitCode -ne 0) {
      throw "Could not establish the diagnostic journal baseline before testing (exit=$($baseline.ExitCode), timedOut=$($baseline.TimedOut)); see journal-baseline.txt for the sanitized ADB error."
    }
    $ExistingJournals = @($baseline.Output -split '\r?\n' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    Write-Host 'Unlock the Vivo now and keep the PocketOS validation screen visible. The test waits up to 30 seconds for normal unlocking.'
    $wake = Invoke-Adb @("-s", $Serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP") 5
    if ($wake.ExitCode -ne 0) { throw "Could not wake the display before validation. No guest was launched." }
    $hostProgress = [System.Text.StringBuilder]::new()
    $hostHeartbeat = {
      # This ADB request runs independently of the app's Java timers. run-as scopes
      # procfs access to the target app; no global freezer or battery setting changes.
      $probe = Invoke-AppShell $Serial 'for p in $(pidof com.pocketos.app libqemu-system-aarch64.so 2>/dev/null); do echo PROCESS=$p; cat /proc/$p/stat /proc/$p/cgroup 2>/dev/null; done' 3
      [void]$hostProgress.AppendLine("utc=$([DateTime]::UtcNow.ToString('o')) exit=$($probe.ExitCode) timeout=$($probe.TimedOut)")
      [void]$hostProgress.AppendLine($probe.Output)
      if ($hostProgress.Length -gt 65536) { [void]$hostProgress.Remove(0, $hostProgress.Length - 65536) }
      Save-Text "android-host-progress.txt" $hostProgress.ToString() | Out-Null
    }
    $TestMayBeRunning = $true
    $output = Invoke-Adb @("-s", $Serial, "shell", "am", "instrument", "-w", "-r", "-e", "class", "com.pocketos.app.QemuAndroidRuntimeInstrumentationTest#$($test.Class)", "com.pocketos.app.test/androidx.test.runner.AndroidJUnitRunner") $test.Timeout $hostHeartbeat
    Save-Text "$($test.Name).txt" $output.Output | Out-Null
    $passed = -not $output.TimedOut -and $output.ExitCode -eq 0 -and $output.Output -match 'OK \(1 test\)'
    $Results.Add([pscustomobject]@{ Test = $test.Name; Result = if ($passed) { "PASS" } else { "FAIL" }; ExitCode = $output.ExitCode; TimedOut = $output.TimedOut })
    if (-not $passed) {
      $Failed.Add($test.Name)
      # Capture Android scheduling/freezer state before force-stop destroys it.
      $activityState = Invoke-Adb @("-s", $Serial, "shell", "dumpsys", "activity", "processes", "com.pocketos.app") 3
      # Some OEMs include global phantom-process lists despite the package filter.
      $processSummary = ($activityState.Output -split '\r?\n' | Where-Object {
        $_ -match 'com\.pocketos\.app' -or
        $_ -match '^\s*(mCurRawAdj|mSetRawAdj|mCurAdj|mSetAdj|mCurProcState|mSetProcState|curSchedGroup|setSchedGroup|isFrozen|mFrozen|hasForegroundActivities|hasActiveInstrumentation)\s*='
      }) -join [Environment]::NewLine
      if (-not $processSummary) { $processSummary = 'No PocketOS scheduling record was present after the test ended.' }
      Save-Text "android-process-scheduling.txt" $processSummary | Out-Null
      $powerState = Invoke-Adb @("-s", $Serial, "shell", "dumpsys", "power") 3
      $powerSummary = ($powerState.Output -split '\r?\n' | Where-Object { $_ -match 'mWakefulness=|mIsPowered=|mDisplayReady=|mWakeLockSummary=|mUserActivitySummary=|PocketOS' }) -join [Environment]::NewLine
      Save-Text "android-power-state.txt" $powerSummary | Out-Null
      Save-Text "timeout-cleanup-$($test.Name).txt" (Stop-DeviceTestProcesses $Serial) | Out-Null
    }
    $TestMayBeRunning = $false
  }

  $logcat = Invoke-Adb @("-s", $Serial, "logcat", "-d", "-v", "threadtime") 60
  $relevant = $logcat.Output -split '\r?\n' | Where-Object {
    $_ -match 'PocketOSRuntimeTest|TestRunner|AndroidJUnitRunner|InstrumentationResultPrinter' -or
    $_ -match '(?i)lmkd|lowmemory|out of memory|fatal signal|avc: denied|ANR in' -or
    $_ -match 'ActivityManager.*(com\.pocketos\.app|libqemu-system-aarch64)'
  }
  Save-Text "logcat-sanitized.txt" (($relevant -join [Environment]::NewLine) -replace '(?im)(password|passwd|token|secret|private[_ -]?key)(\s*[:=]\s*)\S+', '$1$2[REDACTED]') | Out-Null
  $service = Invoke-Adb @("-s", $Serial, "shell", "dumpsys", "activity", "services", "com.pocketos.app") 60
  Save-Text "service-state.txt" $service.Output | Out-Null
  $processState = Invoke-Adb @("-s", $Serial, "shell", "ps", "-A") 30
  Save-Text "process-state.txt" (($processState.Output -split '\r?\n' | Where-Object { $_ -match 'pocketos|qemu-system-aarch64' }) -join [Environment]::NewLine) | Out-Null

  $latest = Invoke-Adb @("-s", $Serial, "shell", "run-as", "com.pocketos.app", "ls", "-t", "files/diagnostics") 30
  if ($latest.ExitCode -eq 0) {
    $newJournals = @($latest.Output -split '\r?\n' | ForEach-Object { $_.Trim() } | Where-Object { $_ -match '^arm64-guest-.*\.log(?:\.previous)?$' -and $ExistingJournals -notcontains $_ } | Select-Object -First 4)
    Save-Text "journal-collection.txt" "new-journals=$($newJournals.Count); only journals created by this test are included" | Out-Null
    foreach ($journal in $newJournals) {
      $journalData = Invoke-Adb @("-s", $Serial, "exec-out", "run-as", "com.pocketos.app", "cat", "files/diagnostics/$journal") 30
      if ($journalData.ExitCode -eq 0) { Save-Text $journal $journalData.Output | Out-Null }
    }
  }
} catch {
  if ($TestMayBeRunning) {
    try { Save-Text "exception-cleanup.txt" (Stop-DeviceTestProcesses $Serial) | Out-Null } catch { }
  }
  $Failed.Add("handoff/setup: $($_.Exception.Message)")
  Save-Text "script-error.txt" $_.Exception.Message | Out-Null
} finally {
  $Results | Export-Csv -NoTypeInformation -Encoding UTF8 -Path (Join-Path $OutputDirectory "summary.csv")
  if ($Results.Count -eq 0) {
    "Test,Result,ExitCode,TimedOut" | Set-Content -Encoding utf8 (Join-Path $OutputDirectory "summary.csv")
  }
  $zip = Join-Path (Split-Path -Parent $OutputDirectory) "$(Split-Path -Leaf $OutputDirectory).zip"
  $resultFiles = Get-ChildItem -Path $OutputDirectory -File -Recurse | Where-Object { $_.FullName -notlike "$ArtifactDirectory\*" }
  Compress-Archive -Path $resultFiles.FullName -DestinationPath $zip -Force
}

Write-Host "PocketOS physical test candidate: $Commit"
$Results | Format-Table -AutoSize
Write-Host "Sanitized results: $zip"
if ($Failed.Count -gt 0) {
  Write-Host ("FAIL: " + ($Failed -join ", ")) -ForegroundColor Red
  exit 1
}
Write-Host "PASS: all physical ARM64 instrumentation tests completed."
