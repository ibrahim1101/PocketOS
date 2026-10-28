function ConvertTo-PocketOsSanitizedText {
  param(
    [AllowNull()][string]$Text,
    [string[]]$PrivatePaths = @()
  )

  if ($null -eq $Text) { return "" }
  $safe = $Text -replace '(?im)(password|passwd|token|secret|private[_ -]?key|api[_ -]?key)(\s*[:=]\s*).+$', '$1$2[REDACTED]'
  $safe = $safe -replace '(?im)(authorization\s*:\s*bearer\s+).+$', '$1[REDACTED]'
  $safe = $safe -replace '(?i)(?<![0-9a-f])(?:[0-9a-f]{2}:){5}[0-9a-f]{2}(?![0-9a-f])', '<mac>'
  $safe = $safe -replace '(?<![0-9.])(?:[0-9]{1,3}\.){3}[0-9]{1,3}(?![0-9.])', '<ip>'
  $safe = [regex]::Replace($safe, '(?i)(?<![0-9a-f:])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?![0-9a-f:])', {
    param($match)
    $parsedAddress = $null
    if ([System.Net.IPAddress]::TryParse($match.Value, [ref]$parsedAddress) -and
        $parsedAddress.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetworkV6) {
      return '<ip>'
    }
    return $match.Value
  })
  $safe = $safe -replace '(?i)/(?:data/user(?:_de)?/\d+|data/data)/com\.pocketos\.app(?:[^\s,;]*)?', '<app-private>'

  foreach ($privatePath in $PrivatePaths) {
    if ($privatePath) {
      $safe = [regex]::Replace($safe, [regex]::Escape($privatePath), '<local-private>', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    }
  }
  return $safe
}
