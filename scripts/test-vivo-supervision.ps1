$ErrorActionPreference = 'Stop'
$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile(
  (Join-Path $PSScriptRoot 'test-vivo.ps1'), [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw ($errors | Out-String) }
# Load the actual process helper functions without executing the device handoff.
foreach ($name in @('ConvertTo-CommandLineArgument', 'Invoke-Bounded')) {
  $function = $ast.Find({ param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
  }, $true)
  if (-not $function) { throw "Missing function $name" }
  . ([scriptblock]::Create($function.Extent.Text))
}
$exe = (Get-Process -Id $PID).Path
$normal = Invoke-Bounded $exe @('-NoProfile', '-NonInteractive', '-Command', 'Write-Output child-ready') 15
if ($normal.TimedOut -or $normal.ExitCode -ne 0 -or $normal.Output -notmatch 'child-ready') {
  throw 'Normal child output or exit handling failed'
}
$script:pulses = 0
$clock = [Diagnostics.Stopwatch]::StartNew()
$stalled = Invoke-Bounded $exe @('-NoProfile', '-NonInteractive', '-Command', 'Write-Output child-started; Start-Sleep -Seconds 30') 12 { $script:pulses++ }
if (-not $stalled.TimedOut -or $stalled.ExitCode -ne 124 -or $stalled.Output -notmatch 'child-started') {
  throw 'Stalled child did not terminate with retained output and timeout status'
}
if ($script:pulses -lt 1) { throw 'Independent host heartbeat never ran during stalled child wait' }
if ($clock.Elapsed.TotalSeconds -gt 22) { throw 'Host child termination exceeded its bounded cleanup allowance' }
Write-Host 'PASS: process output, independent host heartbeat, timeout and child termination'
