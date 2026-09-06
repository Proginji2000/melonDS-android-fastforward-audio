#Requires -Version 5.1

<#
.SYNOPSIS
Rebuilds the non-production TWiLight widescreen quarantine from the pinned upstream baseline.

.EXAMPLE
powershell -ExecutionPolicy Bypass -File .\tools\import_twilight_widescreen.ps1

.EXAMPLE
powershell -ExecutionPolicy Bypass -File .\tools\import_twilight_widescreen.ps1 -Offline
#>

[CmdletBinding()]
param(
    [string]$SourceCheckout,
    [string]$OutputDirectory,
    [switch]$Offline
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$project = "DS-Homebrew/TWiLightMenu"
$repository = "https://github.com/DS-Homebrew/TWiLightMenu.git"
$version = "v27.24.1"
$commit = "68d04c1a621a8d330e7233efcfcb93c94b30a3a6"
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$git = (Get-Command git -ErrorAction Stop).Source
$gradle = Join-Path $repositoryRoot "gradlew.bat"

if (-not $SourceCheckout) {
    $SourceCheckout = Join-Path $repositoryRoot "build\widescreen-upstream\TWiLightMenu"
}
elseif (-not [IO.Path]::IsPathRooted($SourceCheckout)) {
    $SourceCheckout = Join-Path $repositoryRoot $SourceCheckout
}
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $repositoryRoot "build\widescreen-quarantine"
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

$gitMetadata = Join-Path $SourceCheckout ".git"
if (-not (Test-Path -LiteralPath $gitMetadata)) {
    if (Test-Path -LiteralPath $SourceCheckout) {
        throw "Le chemin upstream existe sans checkout Git valide: $SourceCheckout"
    }
    if ($Offline) {
        throw "The pinned TWiLight checkout is missing and -Offline forbids retrieval."
    }
    $parent = Split-Path -Parent $SourceCheckout
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    Invoke-Git @(
        "clone",
        "--filter=blob:none",
        "--sparse",
        "--depth", "1",
        "--branch", $version,
        "--single-branch",
        "--", $repository, $SourceCheckout
    ) | Out-Null
    Invoke-Git @("-C", $SourceCheckout, "sparse-checkout", "set", "resources/widescreen") | Out-Null
}

$remote = (Invoke-Git @("-C", $SourceCheckout, "remote", "get-url", "origin") | Select-Object -First 1).Trim()
if ($remote.TrimEnd('/') -ne $repository.TrimEnd('/')) {
    throw "Remote upstream inattendu: $remote (attendu: $repository)"
}
$actualCommit = (Invoke-Git @("-C", $SourceCheckout, "rev-parse", "HEAD") | Select-Object -First 1).Trim()
if ($actualCommit -ne $commit) {
    throw "Commit upstream inattendu: $actualCommit (attendu: $commit)"
}
$tagCommit = (Invoke-Git @("-C", $SourceCheckout, "rev-parse", "refs/tags/$version^{commit}") |
    Select-Object -First 1).Trim()
if ($tagCommit -ne $commit) {
    throw "Le tag $version pointe vers $tagCommit au lieu de $commit"
}
$corpus = Join-Path $SourceCheckout "resources\widescreen"
if (-not (Test-Path -LiteralPath $corpus -PathType Container) -and -not $Offline) {
    Invoke-Git @("-C", $SourceCheckout, "sparse-checkout", "set", "resources/widescreen") | Out-Null
}
if (-not (Test-Path -LiteralPath $corpus -PathType Container)) {
    throw "The pinned upstream corpus is missing: $corpus"
}
$upstreamChanges = @(Invoke-Git @(
    "-C", $SourceCheckout, "status", "--porcelain", "--untracked-files=all"
))
if ($upstreamChanges.Count -ne 0) {
    throw "The pinned upstream checkout contains local changes: $($upstreamChanges -join ', ')"
}

Write-Host "Source verified: $project $version $commit"
Write-Host "Checkout: $SourceCheckout"
& $gradle --offline --no-daemon :widescreen-generator:importTWiLightWidescreenCandidates `
    "-PtwilightCheckout=$SourceCheckout" "-PtwilightOutput=$OutputDirectory"
if ($LASTEXITCODE -ne 0) {
    throw "The TWiLight Gradle import failed (exit $LASTEXITCODE)."
}
