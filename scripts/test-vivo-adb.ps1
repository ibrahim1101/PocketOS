param([string]$Serial = '')
$ErrorActionPreference = 'Stop'
$tokens = $null
$errors = $null
$source = Join-Path $PSScriptRoot 'test-vivo.ps1'
$ast = [System.Management.Automation.Language.Parser]::ParseFile($source, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw ($errors | Out-String) }
foreach ($name in @('ConvertTo-CommandLineArgument', 'Invoke-Bounded', 'Invoke-Adb', 'ConvertTo-AndroidShellArgument', 'Invoke-AppShell', 'Get-AppJournalBaseline')) {
  $definition = $ast.Find({ param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
  }, $true)
  if (-not $definition) { throw "Missing function $name" }
  . ([scriptblock]::Create($definition.Extent.Text))
}
$script:CapturedArguments = @()
# Exercise the exact host argv delivered to adb, including Windows native quoting.
function Invoke-Adb([string[]]$Arguments, [int]$TimeoutSeconds = 90) {
  $script:CapturedArguments = $Arguments
  [pscustomobject]@{ ExitCode = 0; TimedOut = $false; Output = '' }
}
$probeValue = 'spaces; $dollar $(no-expansion) apostrophe'' double" slash\'
$probe = "printf '%s\n' " + (ConvertTo-AndroidShellArgument $probeValue)
$null = Invoke-AppShell 'fixture-serial' $probe 5
if ($script:CapturedArguments.Count -ne 4 -or $script:CapturedArguments[2] -ne 'shell') {
  throw 'ADB must receive one complete remote command after shell'
}
$remote = $script:CapturedArguments[3]
$temp = Join-Path ([IO.Path]::GetTempPath()) ('pocketos-adb-argv-' + [guid]::NewGuid().ToString('N') + '.ps1')
try {
  'param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Received); ConvertTo-Json -InputObject @($Received) -Compress' | Set-Content -Encoding utf8 $temp
  $exeName = if ($PSVersionTable.PSVersion.Major -lt 6) { 'powershell.exe' } elseif ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) { 'pwsh.exe' } else { 'pwsh' }
  $exe = Join-Path $PSHOME $exeName
  $result = Invoke-Bounded $exe (@('-NoProfile', '-NonInteractive', '-File', $temp) + $script:CapturedArguments) 15
  if ($result.ExitCode -ne 0) { throw "Native argv round trip failed: $($result.Output)" }
  $actual = ConvertFrom-Json -InputObject $result.Output
  if ($actual.Count -ne 4 -or $actual[3] -cne $remote) { throw 'Windows/native host argument quoting changed the remote script' }
} finally {
  Remove-Item -LiteralPath $temp -ErrorAction SilentlyContinue
}
$windowsHost = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
if ($Serial) {
  # Use real ADB against a disposable CI emulator after APK installation.
  $script:Adb = (Get-Command adb -ErrorAction Stop).Source
  $definition = $ast.Find({ param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Invoke-Adb'
  }, $true)
  . ([scriptblock]::Create($definition.Extent.Text))
} elseif (-not $windowsHost) {
  # Faithfully model ADB's outer Android shell, then run-as's inner shell.
  function Invoke-Adb([string[]]$Arguments, [int]$TimeoutSeconds = 90) {
    if ($Arguments.Count -ne 4 -or $Arguments[2] -ne 'shell') { throw 'Unexpected ADB argv' }
    $command = 'run_as() { shift; "$@"; }; ' + ($Arguments[3] -replace '^run-as ', 'run_as ')
    return Invoke-Bounded '/bin/sh' @('-c', $command) $TimeoutSeconds
  }
  $Serial = 'local-shell-fixture'
} else {
  Write-Host 'PASS: Windows native argv preserves the quoted Android command; POSIX execution runs in emulator CI'
  return
}
$fixture = 'files/pocketos-host-regression-' + [guid]::NewGuid().ToString('N')
function Assert-RemoteSuccess($Result, [string]$Stage) {
  if ($Result.ExitCode -ne 0 -or $Result.TimedOut) { throw "$Stage failed: exit=$($Result.ExitCode), timeout=$($Result.TimedOut), $($Result.Output)" }
}
try {
  $missing = Get-AppJournalBaseline $Serial "$fixture/missing"
  Assert-RemoteSuccess $missing 'Missing journal directory'
  if ($missing.Output.Trim()) { throw 'Absent diagnostics must yield an empty successful baseline' }
  $path = ConvertTo-AndroidShellArgument "$fixture/diagnostics"
  $file = ConvertTo-AndroidShellArgument "$fixture/diagnostics/arm64-guest-fixture.log"
  Assert-RemoteSuccess (Invoke-AppShell $Serial "mkdir -p $path && : > $file" 5) 'Fixture creation'
  $existing = Get-AppJournalBaseline $Serial "$fixture/diagnostics"
  Assert-RemoteSuccess $existing 'Existing journal directory'
  if ($existing.Output.Trim() -ne 'arm64-guest-fixture.log') { throw 'Journal baseline lost the existing filename' }
  $literal = Invoke-AppShell $Serial $probe 5
  Assert-RemoteSuccess $literal 'Literal shell metacharacters'
  if ($literal.Output.Trim() -cne $probeValue) { throw 'Nested shell quoting expanded or changed literal data' }
  $heartbeat = Invoke-AppShell $Serial 'value=heartbeat; for p in "$value"; do echo PROCESS=$p; done' 5
  Assert-RemoteSuccess $heartbeat 'Heartbeat loop'
  if ($heartbeat.Output.Trim() -ne 'PROCESS=heartbeat') { throw 'Heartbeat variables expanded in the wrong shell' }
  Assert-RemoteSuccess (Invoke-AppShell $Serial 'pidof() { :; }; for pid in $(pidof libqemu-system-aarch64.so 2>/dev/null); do kill -KILL "$pid" 2>/dev/null || true; done' 5) 'Empty cleanup loop'
  $failure = Invoke-AppShell $Serial 'echo deliberate-remote-failure >&2; exit 23' 5
  if ($failure.ExitCode -ne 23 -or $failure.Output -notmatch 'deliberate-remote-failure') { throw 'Remote failure status or diagnostics were lost' }
} finally {
  $file = ConvertTo-AndroidShellArgument "$fixture/diagnostics/arm64-guest-fixture.log"
  $directory = ConvertTo-AndroidShellArgument "$fixture/diagnostics"
  $root = ConvertTo-AndroidShellArgument $fixture
  $null = Invoke-AppShell $Serial "rm -f $file; rmdir $directory $root" 5
}
Write-Host 'PASS: ADB nested shell quoting, missing/existing journal baselines, heartbeat, cleanup, and remote failure reporting'
