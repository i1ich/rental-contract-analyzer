<#
.SYNOPSIS
  Creates a usage-limited license key (T18) in the leaselens-analyses DynamoDB table.

.DESCRIPTION
  Generates a random key with a cryptographically secure RNG, stores it under the
  "license#<key>" partition key with the given number of uses, and prints the key.
  Refuses to overwrite an existing key. Requires the AWS CLI with credentials for the
  account/region the stack is deployed to.

.EXAMPLE
  ./scripts/new-license-key.ps1 -Owner "friend" -Uses 300
#>
param(
    [Parameter(Mandatory = $true)][string]$Owner,
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

$key = "LL-$(New-Chunk 8)-$(New-Chunk 8)"
$now = (Get-Date).ToUniversalTime().ToString("o")

$item = @{
    contentHash   = @{ S = "license#$key" }
    totalUses     = @{ N = "$Uses" }
    remainingUses = @{ N = "$Uses" }
    status        = @{ S = "active" }
    ownerLabel    = @{ S = $Owner }
    createdAt     = @{ S = $now }
} | ConvertTo-Json -Depth 4 -Compress

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

Write-Host "License key created for '$Owner' ($Uses uses):"
Write-Host $key
