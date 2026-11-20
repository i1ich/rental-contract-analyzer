<#
.SYNOPSIS
  Creates a usage-limited license key (T18) in the leaselens-analyses DynamoDB table.

.DESCRIPTION
  Generates a random key with a cryptographically secure RNG, stores it under the
  "license#<key>" partition key with the given number of uses, and prints the key.
  Refuses to overwrite an existing key. Requires the AWS CLI with credentials for the
  account/region the stack is deployed to.

  Every key expires: it stops validating at 00:00 UTC on the next 15th after it was created
  (created 2026-11-18 -> expires 2026-12-15; created 2026-11-10 -> expires 2026-11-15; created on
  the 15th itself -> next month's 15th). There is no auto-renewal: after the 15th, run this script
  again for a fresh key. Same rule as LicenseStore.nextExpiry in the backend.

  -Prefix is a human-readable label at the start of the key (def, clh55, ...); it identifies
  whose key it is in logs (first 4 characters) but grants nothing by itself. -Model, if given,
  is an OpenRouter model id stored on the license and used instead of the default model for
  analyses run with this key.

.EXAMPLE
  ./scripts/new-license-key.ps1 -Owner "friend" -Uses 300
.EXAMPLE
  ./scripts/new-license-key.ps1 -Owner "test haiku" -Prefix clh55 -Model anthropic/claude-haiku-5.5
#>
param(
    [Parameter(Mandatory = $true)][string]$Owner,
    [string]$Prefix = "ll",
    [string]$Model = "",
    [int]$Uses = 300,
    [string]$Table = "leaselens-analyses",
    [string]$Region = "sa-east-1"
)

$ErrorActionPreference = "Stop"

# Base32 without look-alike characters (no 0/O/1/I): easy to read out loud or retype.
$alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
function New-Chunk([int]$length) {
    $bytes = New-Object byte[] $length
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    -join ($bytes | ForEach-Object { $alphabet[$_ % $alphabet.Length] })
}

$key = "$($Prefix.ToLowerInvariant())_$(New-Chunk 8)-$(New-Chunk 8)"
$nowUtc = (Get-Date).ToUniversalTime()
$now = $nowUtc.ToString("o")

# Next 15th, 00:00 UTC, strictly after today's date (mirrors LicenseStore.nextExpiry).
$fifteenth = [DateTime]::new($nowUtc.Year, $nowUtc.Month, 15, 0, 0, 0, [DateTimeKind]::Utc)
if ($nowUtc.Day -ge 15) { $fifteenth = $fifteenth.AddMonths(1) }
$expiresAt = $fifteenth.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")

# The backend upper-cases whatever a user types before looking it up, so the stored key must be
# upper-case too; the lower-case prefix is only how the key is displayed and logged.
$itemTable = @{
    contentHash   = @{ S = "license#$($key.ToUpperInvariant())" }
    totalUses     = @{ N = "$Uses" }
    remainingUses = @{ N = "$Uses" }
    status        = @{ S = "active" }
    ownerLabel    = @{ S = $Owner }
    createdAt     = @{ S = $now }
    expiresAt     = @{ S = $expiresAt }
}
if ($Model) { $itemTable.model = @{ S = $Model } }
$item = $itemTable | ConvertTo-Json -Depth 4 -Compress

# Through a file: PowerShell 5.1 mangles quotes when passing JSON to native executables.
$tmp = New-TemporaryFile
try {
    [System.IO.File]::WriteAllText($tmp.FullName, $item, (New-Object System.Text.UTF8Encoding($false)))
    aws dynamodb put-item `
        --table-name $Table `
        --region $Region `
        --item "file://$($tmp.FullName)" `
        --condition-expression "attribute_not_exists(contentHash)"
    if ($LASTEXITCODE -ne 0) { throw "aws dynamodb put-item failed (exit $LASTEXITCODE)" }
} finally {
    Remove-Item $tmp.FullName -Force
}

Write-Host "License key created for '$Owner' ($Uses uses, expires $expiresAt):"
Write-Host $key
