$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "Sanitize-Diagnostics.ps1")

$raw = @"
password=first-run-password with spaces
api_key: private-api-token
Authorization: Bearer private-bearer-token
private_key=private-key-material
guest-ip=192.168.15.4
ipv6=2001:db8::1
timestamp=2026-10-25T17:11:07.322Z
device-mac=02:00:5e:10:00:01
app-path=/data/user/0/com.pocketos.app/files/guest.ext4
host-path=C:\Users\PocketUser\PocketOS\device.log
milestone=guest reboot completed
"@

$sanitized = ConvertTo-PocketOsSanitizedText -Text $raw -PrivatePaths @("C:\Users\PocketUser")
$forbidden = @(
  "first-run-password",
  "with spaces",
  "private-api-token",
  "private-bearer-token",
  "private-key-material",
  "192.168.15.4",
  "2001:db8::1",
  "02:00:5e:10:00:01",
  "/data/user/0/com.pocketos.app",
  "C:\Users\PocketUser"
)
foreach ($value in $forbidden) {
  if ($sanitized.Contains($value)) { throw "Sanitizer leaked a forbidden diagnostic value." }
}
foreach ($marker in @("<ip>", "<mac>", "<app-private>", "<local-private>", "milestone=guest reboot completed", "timestamp=2026-10-25T17:11:07.322Z")) {
  if (-not $sanitized.Contains($marker)) { throw "Sanitizer dropped expected safe marker '$marker'." }
}
Write-Host "PocketOS diagnostic sanitizer regression checks passed."
