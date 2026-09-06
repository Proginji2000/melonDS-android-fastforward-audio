#Requires -Version 5.1

<#
.SYNOPSIS
Builds a non-production TWiLight-to-usrcheat identity-resolution report from pinned local inputs.

.EXAMPLE
powershell -ExecutionPolicy Bypass -File .\tools\resolve_twilight_widescreen_identities.ps1

.EXAMPLE
powershell -ExecutionPolicy Bypass -File .\tools\resolve_twilight_widescreen_identities.ps1 -Offline
#>

[CmdletBinding()]
param(
    [string]$UsrcheatCheckout,
    [string]$TWiLightCandidates,
    [string]$OutputDirectory,
    [switch]$Offline
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$officialRepository = "https://bitbucket.org/DeadSkullzJr/nds-i-cheat-databases"
$distributionRepository = "https://github.com/szTheory/NDS-Cheat-Databases.git"
$version = "DeadSkullzJr changelog 2021-12-25"
$commit = "0173af14d33e2c045e7c5e30c71d369624a33020"
$artifactPath = "Cheat Databases\usrcheat.dat"
$artifactSize = 42983604
$artifactSha256 = "28ad3272d3578ba2f493e78990a3d6defe9dfebe37d9f53501679be9498eafae"
$twilightCandidatesSha256 = "ccb1d0c2177df73653224716017364c1d9fbbf3d667425fa799fe70f7af5d000"
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$git = (Get-Command git -ErrorAction Stop).Source
$gradle = Join-Path $repositoryRoot "gradlew.bat"

if (-not $UsrcheatCheckout) {
    $UsrcheatCheckout = Join-Path $repositoryRoot "build\widescreen-identity-upstream\NDS-Cheat-Databases"
}
elseif (-not [IO.Path]::IsPathRooted($UsrcheatCheckout)) {
    $UsrcheatCheckout = Join-Path $repositoryRoot $UsrcheatCheckout
}
if (-not $TWiLightCandidates) {
    $TWiLightCandidates = Join-Path $repositoryRoot "build\widescreen-quarantine\twilight-widescreen-candidates.json"
}
elseif (-not [IO.Path]::IsPathRooted($TWiLightCandidates)) {
    $TWiLightCandidates = Join-Path $repositoryRoot $TWiLightCandidates
}
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $repositoryRoot "build\widescreen-identity-resolution"
}
elseif (-not [IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory = Join-Path $repositoryRoot $OutputDirectory
}

function Invoke-Git {
    param([Parameter(Mandatory = $true)][string[]]$GitArguments)

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $git @GitArguments 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0) {
        throw "git $($GitArguments -join ' ') failed (exit $exitCode): $($output -join "`n")"
    }
    return @($output | ForEach-Object { $_.ToString() })
}

$gitMetadata = Join-Path $UsrcheatCheckout ".git"
if (-not (Test-Path -LiteralPath $gitMetadata)) {
    if (Test-Path -LiteralPath $UsrcheatCheckout) {
        throw "Le chemin usrcheat existe sans checkout Git valide: $UsrcheatCheckout"
    }
    if ($Offline) {
        throw "The pinned usrcheat checkout is missing and -Offline forbids retrieval."
    }
    $parent = Split-Path -Parent $UsrcheatCheckout
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    Invoke-Git @("clone", "--filter=blob:none", "--no-checkout", "--", $distributionRepository, $UsrcheatCheckout) |
        Out-Null
    Invoke-Git @("-C", $UsrcheatCheckout, "sparse-checkout", "init", "--no-cone") | Out-Null
    Invoke-Git @(
        "-C", $UsrcheatCheckout, "sparse-checkout", "set",
        "/Cheat Databases/usrcheat.dat", "/LICENSE", "/README.md", "/Changelog.txt"
    ) | Out-Null
    Invoke-Git @("-C", $UsrcheatCheckout, "checkout", "--detach", $commit) | Out-Null
}

$remote = (Invoke-Git @("-C", $UsrcheatCheckout, "remote", "get-url", "origin") |
    Select-Object -First 1).Trim()
if ($remote.TrimEnd('/') -ne $distributionRepository.TrimEnd('/')) {
    throw "Remote usrcheat inattendu: $remote (attendu: $distributionRepository)"
}
$actualCommit = (Invoke-Git @("-C", $UsrcheatCheckout, "rev-parse", "HEAD") |
    Select-Object -First 1).Trim()
if ($actualCommit -ne $commit) {
    throw "Commit usrcheat inattendu: $actualCommit (attendu: $commit)"
}

& $git -C $UsrcheatCheckout symbolic-ref -q HEAD 2>$null | Out-Null
$symbolicRefExitCode = $LASTEXITCODE
if ($symbolicRefExitCode -eq 0) {
    throw "The pinned usrcheat checkout must use a detached HEAD."
}
if ($symbolicRefExitCode -ne 1) {
    throw "Unable to verify detached HEAD (git exit $symbolicRefExitCode)."
}
$upstreamChanges = @(Invoke-Git @(
    "-C", $UsrcheatCheckout, "status", "--porcelain", "--untracked-files=all"
))
if ($upstreamChanges.Count -ne 0) {
    throw "The pinned usrcheat checkout contains local changes: $($upstreamChanges -join ', ')"
}

$usrcheatArtifact = Join-Path $UsrcheatCheckout $artifactPath
if (-not (Test-Path -LiteralPath $usrcheatArtifact -PathType Leaf)) {
    throw "The pinned usrcheat artifact is missing: $usrcheatArtifact"
}
$actualArtifact = Get-Item -LiteralPath $usrcheatArtifact
if ($actualArtifact.Length -ne $artifactSize) {
    throw "usrcheat.dat size mismatch: $($actualArtifact.Length) (expected $artifactSize)"
}
$actualArtifactSha256 = (Get-FileHash -LiteralPath $usrcheatArtifact -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualArtifactSha256 -ne $artifactSha256) {
    throw "usrcheat.dat SHA-256 mismatch: $actualArtifactSha256 (expected $artifactSha256)"
}
if (-not (Test-Path -LiteralPath $TWiLightCandidates -PathType Leaf)) {
    throw "The pinned TWiLight quarantine candidates are missing: $TWiLightCandidates"
}
$actualCandidatesSha256 = (Get-FileHash -LiteralPath $TWiLightCandidates -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualCandidatesSha256 -ne $twilightCandidatesSha256) {
    throw "TWiLight candidates SHA-256 mismatch: $actualCandidatesSha256 (expected $twilightCandidatesSha256)"
}

Write-Host "Official source reference (not used for retrieval): $officialRepository"
Write-Host "Pinned distribution: $distributionRepository @ $commit ($version)"
Write-Host "usrcheat.dat: $artifactSize bytes, sha256=$artifactSha256"
& $gradle --offline --no-daemon :widescreen-generator:resolveTWiLightWidescreenIdentities `
    "-PusrcheatArtifact=$usrcheatArtifact" `
    "-PtwilightCandidates=$TWiLightCandidates" `
    "-PidentityOutput=$OutputDirectory"
if ($LASTEXITCODE -ne 0) {
    throw "The TWiLight identity resolver failed (exit $LASTEXITCODE)."
}
