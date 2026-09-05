#Requires -Version 5.1

[CmdletBinding()]
param(
    [string]$AdbPath,
    [string]$DeviceSerial,
    [string]$PackageName = "me.magnum.melonds.dev",
    [string]$RomUri,
    [ValidateRange(-1, 8)]
    [int]$SaveStateSlot = -1,
    [ValidateRange(0, 300)]
    [int]$SettleSeconds = 8,
    [ValidateRange(0, 300)]
    [int]$WarmupSeconds = 10,
    [ValidateRange(1, 3600)]
    [int]$MeasureSeconds = 30,
    [ValidateSet("-1", "1", "2")]
    [string]$FastForwardMultiplier = "-1",
    [ValidateRange(0, 20)]
    [int]$RepeatSoftware = 0,
    [switch]$IncludeOptional,
    [string]$OutputRoot,
    [switch]$SelfTest,
    [switch]$DeviceTransportSelfTest,
    [switch]$LibraryOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$script:AdbExecutable = $null
$script:Serial = $null
$script:Package = $PackageName
$script:PreferencePath = "shared_prefs/${PackageName}_preferences.xml"
$script:PreferenceBackupPath = "${script:PreferencePath}.bak"
$script:ControllerPath = "files/controller_config.json"
$script:RomDataPath = "files/rom_data.json"
$script:Invariant = [Globalization.CultureInfo]::InvariantCulture

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,
        [switch]$WithoutSerial,
        [switch]$AllowFailure
    )

    $allArguments = @()
    if (-not $WithoutSerial -and $script:Serial) {
        $allArguments += @("-s", $script:Serial)
    }
    $allArguments += $Arguments

    # Windows PowerShell 5 wraps native stderr as ErrorRecord objects. adb writes
    # successful transfer progress there, so do not let ErrorActionPreference=Stop
    # turn a zero-exit command into a terminating PowerShell error.
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $script:AdbExecutable @allArguments 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | ForEach-Object { $_.ToString() }) -join "`n"
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        $detail = if ($text) { ": $text" } else { "" }
        throw "adb a échoué (code $exitCode)$detail"
    }

    [pscustomobject]@{
        ExitCode = $exitCode
        Output = $text.Trim()
    }
}

function Quote-RemoteArgument {
    param([Parameter(Mandatory = $true)][string]$Value)
    return "'" + $Value.Replace("'", '''"''"''') + "'"
}

function Get-ByteHash {
    param([Parameter(Mandatory = $true)][AllowEmptyCollection()][byte[]]$Bytes)

    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace("-", "").ToLowerInvariant()
    }
    finally {
        $sha.Dispose()
    }
}

function Get-FileHashHex {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Test-AppFile {
    param([Parameter(Mandatory = $true)][string]$Path)
    $result = Invoke-Adb -Arguments @("shell", "run-as", $script:Package, "test", "-f", $Path) -AllowFailure
    return $result.ExitCode -eq 0
}

function Get-AppFileHash {
    param([Parameter(Mandatory = $true)][string]$Path)
    $result = Invoke-Adb -Arguments @("shell", "run-as", $script:Package, "sha256sum", $Path)
    if ($result.Output -match "^(?<hash>[0-9a-fA-F]{64})\s") {
        return $Matches.hash.ToLowerInvariant()
    }
    throw "Impossible de lire le SHA-256 d'un fichier privé."
}

function Get-AppFileBytes {
    param([Parameter(Mandatory = $true)][string]$Path)

    $result = Invoke-Adb -Arguments @("exec-out", "run-as", $script:Package, "base64", $Path)
    $base64 = $result.Output -replace "\s", ""
    if (-not $base64) {
        return ,[byte[]]@()
    }
    return ,([Convert]::FromBase64String($base64))
}

function Set-AppFileBytes {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][byte[]]$Bytes
    )

    if ($Path -notmatch "^[A-Za-z0-9_./-]+$") {
        throw "Chemin privé non sûr pour le transport adb."
    }
    if ($script:Serial -notmatch "^[A-Za-z0-9_.:-]+$" -or $script:Package -notmatch "^[A-Za-z0-9_.]+$") {
        throw "Identifiant adb ou package non sûr."
    }
    $base64 = [Convert]::ToBase64String($Bytes)
    # Keep payload bytes off the Windows command line and bypass PowerShell 5's
    # native pipeline conversion by writing ASCII directly to redirected stdin.
    $remoteCommand = "run-as $($script:Package) sh -c 'base64 -d > $Path'"
    $startInfo = New-Object Diagnostics.ProcessStartInfo
    $startInfo.FileName = $script:AdbExecutable
    $startInfo.Arguments = "-s $($script:Serial) exec-in `"$remoteCommand`""
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) {
            throw "adb exec-in n'a pas démarré."
        }
        $process.StandardInput.Write($base64)
        $process.StandardInput.Close()
        $standardOutput = $process.StandardOutput.ReadToEnd()
        $standardError = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        $exitCode = $process.ExitCode
    }
    finally {
        $process.Dispose()
    }
    if ($exitCode -ne 0) {
        $detail = ($standardOutput + "`n" + $standardError).Trim()
        throw "adb exec-in a échoué (code $exitCode): $detail"
    }

    $expectedHash = Get-ByteHash $Bytes
    $actualHash = $null
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        $actualHash = Get-AppFileHash $Path
        if ($actualHash -eq $expectedHash) {
            return
        }
        Start-Sleep -Milliseconds 100
    }
    throw "La vérification SHA-256 du fichier privé modifié a échoué (attendu=$expectedHash, lu=$actualHash, fichier=$Path)."
}

function Remove-AppFile {
    param([Parameter(Mandatory = $true)][string]$Path)
    [void](Invoke-Adb -Arguments @("shell", "run-as", $script:Package, "rm", "-f", $Path))
    if (Test-AppFile $Path) {
        throw "Un fichier privé temporaire n'a pas pu être supprimé."
    }
}

function Get-AppFileState {
    param([Parameter(Mandatory = $true)][string]$Path)

    $exists = Test-AppFile $Path
    $bytes = if ($exists) { [byte[]](Get-AppFileBytes $Path) } else { $null }
    if ($exists -and (Get-ByteHash $bytes) -ne (Get-AppFileHash $Path)) {
        throw "La copie locale d'un fichier privé n'est pas identique à l'appareil."
    }
    return [pscustomobject]@{
        Path = $Path
        Exists = $exists
        Bytes = $bytes
        Hash = if ($exists) { Get-ByteHash $bytes } else { $null }
    }
}

function Restore-AppFileState {
    param([Parameter(Mandatory = $true)]$State)

    if ($State.Exists) {
        Set-AppFileBytes -Path $State.Path -Bytes ([byte[]]$State.Bytes)
        if ((Get-AppFileHash $State.Path) -ne $State.Hash) {
            throw "La restauration d'un fichier privé n'est pas identique à l'original."
        }
    }
    else {
        Remove-AppFile $State.Path
    }
}

function Set-PreferenceValue {
    param(
        [Parameter(Mandatory = $true)][xml]$Document,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][ValidateSet("string", "boolean")][string]$Type,
        [Parameter(Mandatory = $true)]$Value
    )

    $node = $Document.DocumentElement.ChildNodes |
        Where-Object { $_.Attributes["name"] -and $_.Attributes["name"].Value -eq $Name } |
        Select-Object -First 1

    if ($node -and $node.LocalName -ne $Type) {
        [void]$Document.DocumentElement.RemoveChild($node)
        $node = $null
    }
    if (-not $node) {
        $node = $Document.CreateElement($Type)
        $node.SetAttribute("name", $Name)
        [void]$Document.DocumentElement.AppendChild($node)
    }

    if ($Type -eq "string") {
        $node.InnerText = [string]$Value
    }
    else {
        $node.SetAttribute("value", ([bool]$Value).ToString().ToLowerInvariant())
    }
}

function New-TestPreferenceBytes {
    param(
        [Parameter(Mandatory = $true)][byte[]]$OriginalBytes,
        [Parameter(Mandatory = $true)]$Test
    )

    $text = [Text.Encoding]::UTF8.GetString($OriginalBytes)
    [xml]$document = $text

    Set-PreferenceValue $document "video_renderer" "string" $Test.renderer
    Set-PreferenceValue $document "video_internal_resolution" "string" ([string]$Test.scale)
    Set-PreferenceValue $document "enable_threaded_rendering" "boolean" ([bool]$Test.threaded)
    Set-PreferenceValue $document "enable_jit" "boolean" ([bool]$Test.jit)
    Set-PreferenceValue $document "video_filtering" "string" "none"
    Set-PreferenceValue $document "audio_interpolation" "string" "none"
    Set-PreferenceValue $document "enable_rewind" "boolean" $false
    Set-PreferenceValue $document "fast_forward_speed_multiplier" "string" $FastForwardMultiplier
    Set-PreferenceValue $document "enable_sustained_performance" "boolean" $false
    Set-PreferenceValue $document "sound_enabled" "boolean" $true
    if ($SaveStateSlot -ge 0) {
        Set-PreferenceValue $document "ra_hardcore_enabled" "boolean" $false
    }

    $settings = New-Object Xml.XmlWriterSettings
    $settings.Encoding = New-Object Text.UTF8Encoding($false)
    $settings.Indent = $true
    $settings.NewLineChars = "`n"
    $settings.NewLineHandling = [Xml.NewLineHandling]::Replace

    $stream = New-Object IO.MemoryStream
    $writer = [Xml.XmlWriter]::Create($stream, $settings)
    try {
        $document.Save($writer)
        $writer.Flush()
        return ,$stream.ToArray()
    }
    finally {
        $writer.Dispose()
        $stream.Dispose()
    }
}

function Get-PreferenceSnapshot {
    param([Parameter(Mandatory = $true)][byte[]]$Bytes)

    [xml]$document = [Text.Encoding]::UTF8.GetString($Bytes)
    $defaults = [ordered]@{
        video_renderer = "software"
        video_internal_resolution = "1"
        enable_threaded_rendering = "true"
        enable_jit = "true on a 64-bit ABI"
        video_filtering = "none"
        audio_interpolation = "none"
        enable_rewind = "false"
        fast_forward_speed_multiplier = "-1"
        enable_sustained_performance = "false"
        sound_enabled = "true"
        save_state_location = "save_dir"
        use_rom_dir = "true"
        ra_hardcore_enabled = "false"
    }

    $snapshot = [ordered]@{}
    foreach ($entry in $defaults.GetEnumerator()) {
        $node = $document.DocumentElement.ChildNodes |
            Where-Object { $_.Attributes["name"] -and $_.Attributes["name"].Value -eq $entry.Key } |
            Select-Object -First 1
        if (-not $node) {
            $snapshot[$entry.Key] = "$($entry.Value) (défaut)"
        }
        elseif ($node.LocalName -eq "string") {
            $snapshot[$entry.Key] = $node.InnerText
        }
        else {
            $snapshot[$entry.Key] = $node.Attributes["value"].Value
        }
    }
    return $snapshot
}

function Get-RecentRom {
    $bytes = Get-AppFileBytes $script:RomDataPath
    $roms = [Text.Encoding]::UTF8.GetString($bytes) | ConvertFrom-Json
    $ranked = foreach ($rom in @($roms)) {
        $property = $rom.PSObject.Properties["lastPlayed"]
        if ($property -and $property.Value) {
            try {
                [pscustomobject]@{
                    Rom = $rom
                    LastPlayed = [datetime]::Parse([string]$property.Value, $script:Invariant)
                }
            }
            catch {
                # Ignore malformed historical cache entries.
            }
        }
    }

    $recent = $ranked | Sort-Object LastPlayed -Descending | Select-Object -First 1
    if (-not $recent) {
        throw "Aucune ROM récemment jouée n'a été trouvée; utilisez -RomUri."
    }
    return $recent.Rom
}

function Resolve-PrimaryStoragePaths {
    param([Parameter(Mandatory = $true)]$Rom)

    if (-not $Rom.parentTreeUri) {
        throw "Le répertoire de la ROM ne peut pas être résolu pour protéger SRAM et savestate."
    }
    $parent = [uri][string]$Rom.parentTreeUri
    if ($parent.Authority -ne "com.android.externalstorage.documents") {
        throw "Le fournisseur de stockage de la ROM n'est pas automatisable sans root."
    }

    $marker = $parent.AbsolutePath.IndexOf("/document/")
    if ($marker -lt 0) {
        throw "L'URI du répertoire ROM n'a pas la forme DocumentsContract attendue."
    }
    $documentId = [uri]::UnescapeDataString($parent.AbsolutePath.Substring($marker + 10))
    if (-not $documentId.StartsWith("primary:")) {
        throw "Seul le stockage partagé primaire peut être sauvegardé de façon non-root par adb."
    }

    $directory = "/sdcard/" + $documentId.Substring(8).Trim("/")
    $baseName = [IO.Path]::GetFileNameWithoutExtension([string]$Rom.fileName)
    return [pscustomobject]@{
        Directory = $directory.TrimEnd("/")
        Sram = $directory.TrimEnd("/") + "/" + $baseName + ".sav"
        QuickState = $directory.TrimEnd("/") + "/" + $baseName + ".ml0"
        SelectedState = if ($SaveStateSlot -ge 0) {
            $directory.TrimEnd("/") + "/" + $baseName + ".ml" + $SaveStateSlot
        } else {
            $null
        }
    }
}

function Test-RemoteFile {
    param([Parameter(Mandatory = $true)][string]$Path)
    $command = "test -f " + (Quote-RemoteArgument $Path)
    return (Invoke-Adb -Arguments @("shell", $command) -AllowFailure).ExitCode -eq 0
}

function Get-RemoteFileHash {
    param([Parameter(Mandatory = $true)][string]$Path)
    $command = "sha256sum " + (Quote-RemoteArgument $Path)
    $output = (Invoke-Adb -Arguments @("shell", $command)).Output
    if ($output -match "^(?<hash>[0-9a-fA-F]{64})\s") {
        return $Matches.hash.ToLowerInvariant()
    }
    throw "Impossible de lire le SHA-256 d'un fichier de sauvegarde."
}

function Backup-RemoteFile {
    param(
        [Parameter(Mandatory = $true)][string]$RemotePath,
        [Parameter(Mandatory = $true)][string]$BackupPath
    )

    $exists = Test-RemoteFile $RemotePath
    if ($exists) {
        [void](Invoke-Adb -Arguments @("pull", $RemotePath, $BackupPath))
    }
    return [pscustomobject]@{
        Path = $RemotePath
        Exists = $exists
        BackupPath = if ($exists) { $BackupPath } else { $null }
        Hash = if ($exists) { Get-FileHashHex $BackupPath } else { $null }
    }
}

function Restore-RemoteFile {
    param([Parameter(Mandatory = $true)]$State)

    if ($State.Exists) {
        [void](Invoke-Adb -Arguments @("push", $State.BackupPath, $State.Path))
        if ((Get-RemoteFileHash $State.Path) -ne $State.Hash) {
            throw "La restauration du SRAM n'est pas identique à l'original."
        }
    }
    elseif (Test-RemoteFile $State.Path) {
        $command = "rm -- " + (Quote-RemoteArgument $State.Path)
        [void](Invoke-Adb -Arguments @("shell", $command))
        if (Test-RemoteFile $State.Path) {
            throw "Le SRAM créé par le benchmark n'a pas pu être retiré."
        }
    }
}

function New-TemporaryQuickState {
    param([Parameter(Mandatory = $true)]$Paths)

    if ($SaveStateSlot -lt 0) {
        return $null
    }
    if (-not (Test-RemoteFile $Paths.SelectedState)) {
        throw "Le savestate demandé n'existe pas."
    }
    if ($SaveStateSlot -eq 0) {
        return [pscustomobject]@{
            Created = $false
            Path = $Paths.QuickState
            Hash = Get-RemoteFileHash $Paths.QuickState
        }
    }
    if (Test-RemoteFile $Paths.QuickState) {
        throw "Le slot rapide existe déjà; refus de l'écraser pour charger un autre slot."
    }

    $source = Quote-RemoteArgument $Paths.SelectedState
    $target = Quote-RemoteArgument $Paths.QuickState
    [void](Invoke-Adb -Arguments @("shell", "cp -- $source $target"))
    $sourceHash = Get-RemoteFileHash $Paths.SelectedState
    $targetHash = Get-RemoteFileHash $Paths.QuickState
    if ($sourceHash -ne $targetHash) {
        throw "La copie temporaire du savestate n'est pas identique à sa source."
    }

    return [pscustomobject]@{
        Created = $true
        Path = $Paths.QuickState
        Hash = $targetHash
    }
}

function Remove-TemporaryQuickState {
    param($State)

    if (-not $State -or -not $State.Created) {
        return
    }
    if (-not (Test-RemoteFile $State.Path)) {
        return
    }
    if ((Get-RemoteFileHash $State.Path) -ne $State.Hash) {
        throw "Le slot rapide temporaire a changé; il est conservé par sécurité."
    }

    [void](Invoke-Adb -Arguments @("shell", "rm -- " + (Quote-RemoteArgument $State.Path)))
    if (Test-RemoteFile $State.Path) {
        throw "Le slot rapide temporaire n'a pas pu être retiré."
    }
}

function Get-DisplayInfo {
    $output = (Invoke-Adb -Arguments @("shell", "cmd", "display", "get-displays")).Output
    $infos = @()
    foreach ($line in ($output -split "`r?`n")) {
        if ($line -notmatch "Display id (?<id>\d+):") {
            continue
        }
        $id = [int]$Matches.id
        if ($line -notmatch ", mode (?<mode>\d+),") {
            continue
        }
        $modeId = [int]$Matches.mode
        $modePattern = "\{id=$modeId, width=(?<width>\d+), height=(?<height>\d+), fps=(?<fps>[0-9.]+)"
        if ($line -notmatch $modePattern) {
            continue
        }
        $activeFps = [double]::Parse($Matches.fps, $script:Invariant)

        $sixty = $null
        foreach ($modeMatch in [regex]::Matches($line, "\{id=\d+, width=(?<width>\d+), height=(?<height>\d+), fps=(?<fps>[0-9.]+)")) {
            $fps = [double]::Parse($modeMatch.Groups["fps"].Value, $script:Invariant)
            if ([math]::Abs($fps - 60.0) -lt 0.2) {
                $sixty = [pscustomobject]@{
                    Width = [int]$modeMatch.Groups["width"].Value
                    Height = [int]$modeMatch.Groups["height"].Value
                    Fps = $fps
                }
                break
            }
        }

        $infos += [pscustomobject]@{
            Id = $id
            ActiveFps = $activeFps
            SixtyHzMode = $sixty
        }
    }
    if (-not $infos) {
        throw "Aucun écran Android actif n'a pu être analysé."
    }
    return $infos
}

function Get-DisplayModeStates {
    param([Parameter(Mandatory = $true)]$DisplayInfos)

    $states = @()
    foreach ($display in $DisplayInfos) {
        $output = (Invoke-Adb -Arguments @("shell", "cmd", "display", "get-user-preferred-display-mode", [string]$display.Id)).Output
        if ($output -match "null|User preferred display mode:\s+-1\s+-1\s+0(?:\.0+)?") {
            $states += [pscustomobject]@{ Id = $display.Id; IsNull = $true; Width = $null; Height = $null; Fps = $null }
        }
        elseif ($output -match "mode:\s+(?<width>\d+)\s+(?<height>\d+)\s+(?<fps>[0-9.]+)") {
            $states += [pscustomobject]@{
                Id = $display.Id
                IsNull = $false
                Width = [int]$Matches.width
                Height = [int]$Matches.height
                Fps = [double]::Parse($Matches.fps, $script:Invariant)
            }
        }
        else {
            throw "Le mode utilisateur de l'écran $($display.Id) n'a pas pu être interprété."
        }
    }
    return $states
}

function Restore-DisplayModes {
    param([Parameter(Mandatory = $true)]$States)

    foreach ($state in $States) {
        if ($state.IsNull) {
            [void](Invoke-Adb -Arguments @("shell", "cmd", "display", "clear-user-preferred-display-mode", [string]$state.Id))
        }
        else {
            $fps = ([double]$state.Fps).ToString("0.######", $script:Invariant)
            [void](Invoke-Adb -Arguments @(
                "shell", "cmd", "display", "set-user-preferred-display-mode",
                [string]$state.Width, [string]$state.Height, $fps, [string]$state.Id
            ))
        }
    }
}

function Get-AndroidSettingState {
    param(
        [Parameter(Mandatory = $true)][string]$Namespace,
        [Parameter(Mandatory = $true)][string]$Name
    )

    $value = (Invoke-Adb -Arguments @("shell", "settings", "get", $Namespace, $Name)).Output.Trim()
    return [pscustomobject]@{
        Namespace = $Namespace
        Name = $Name
        Exists = $value -ne "null"
        Value = if ($value -eq "null") { $null } else { $value }
    }
}

function Restore-AndroidSettingState {
    param([Parameter(Mandatory = $true)]$State)

    if ($State.Exists) {
        [void](Invoke-Adb -Arguments @("shell", "settings", "put", $State.Namespace, $State.Name, [string]$State.Value))
    }
    else {
        [void](Invoke-Adb -Arguments @("shell", "settings", "delete", $State.Namespace, $State.Name))
    }

    $restored = Get-AndroidSettingState -Namespace $State.Namespace -Name $State.Name
    if ($restored.Exists -ne $State.Exists -or ($State.Exists -and $restored.Value -ne $State.Value)) {
        throw "Le réglage Android $($State.Namespace)/$($State.Name) n'a pas été restauré exactement."
    }
}

function Test-DeviceAwake {
    $powerDump = (Invoke-Adb -Arguments @("shell", "dumpsys", "power")).Output
    return $powerDump -match "(?m)^\s*mWakefulness=Awake\s*$"
}

function Ensure-DeviceAwake {
    if (Test-DeviceAwake) {
        return
    }

    [void](Invoke-Adb -Arguments @("shell", "input", "keyevent", "224"))
    $deadline = [datetime]::UtcNow.AddSeconds(5)
    do {
        Start-Sleep -Milliseconds 250
        if (Test-DeviceAwake) {
            return
        }
    } while ([datetime]::UtcNow -lt $deadline)
    throw "L'écran de l'appareil ne s'est pas réveillé."
}

function Get-PowerState {
    return [pscustomobject]@{
        ScreenOffTimeout = Get-AndroidSettingState -Namespace "system" -Name "screen_off_timeout"
        StayOnWhilePluggedIn = Get-AndroidSettingState -Namespace "global" -Name "stay_on_while_plugged_in"
        WasAwake = Test-DeviceAwake
    }
}

function Enable-BenchmarkAwake {
    [void](Invoke-Adb -Arguments @("shell", "settings", "put", "system", "screen_off_timeout", "1800000"))
    [void](Invoke-Adb -Arguments @("shell", "settings", "put", "global", "stay_on_while_plugged_in", "7"))
    Ensure-DeviceAwake
}

function Restore-PowerState {
    param([Parameter(Mandatory = $true)]$State)

    Restore-AndroidSettingState $State.ScreenOffTimeout
    Restore-AndroidSettingState $State.StayOnWhilePluggedIn
    if ($State.WasAwake) {
        Ensure-DeviceAwake
    }
    elseif (Test-DeviceAwake) {
        [void](Invoke-Adb -Arguments @("shell", "input", "keyevent", "223"))
    }
}

function Set-DisplaysTo60Hz {
    param([Parameter(Mandatory = $true)]$DisplayInfos)

    foreach ($display in $DisplayInfos) {
        if (-not $display.SixtyHzMode) {
            throw "L'écran $($display.Id) n'annonce aucun mode 60 Hz."
        }
        [void](Invoke-Adb -Arguments @(
            "shell", "cmd", "display", "set-user-preferred-display-mode",
            [string]$display.SixtyHzMode.Width,
            [string]$display.SixtyHzMode.Height,
            "60",
            [string]$display.Id
        ))
    }
    Start-Sleep -Seconds 2
}

function Get-PrimaryRefreshRate {
    $primary = Get-DisplayInfo | Where-Object { $_.Id -eq 0 } | Select-Object -First 1
    if (-not $primary) {
        $primary = Get-DisplayInfo | Select-Object -First 1
    }
    return [double]$primary.ActiveFps
}

function Get-FFAudioLog {
    param([Parameter(Mandatory = $true)][int]$ProcessId)
    return (Invoke-Adb -Arguments @("logcat", "-d", "-v", "epoch", "--pid=$ProcessId", "FFAudioDiag:V", "*:S")).Output
}

function Get-TransitionLines {
    param(
        [Parameter(Mandatory = $true)][string]$Log,
        [Parameter(Mandatory = $true)][ValidateSet("OFF_TO_ON", "ON_TO_OFF")][string]$Transition
    )
    return ,@(($Log -split "`r?`n") | Where-Object { $_ -match "transition=$Transition(?:\s|$)" })
}

function Wait-ForAudioOpen {
    param(
        [Parameter(Mandatory = $true)][int]$ProcessId,
        [int]$TimeoutSeconds = 45
    )

    $deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $log = Get-FFAudioLog $ProcessId
        if ($log -match "open_failed") {
            throw "FFAudioDiag signale l'échec d'ouverture du flux audio."
        }
        if ($log -match "open_success") {
            return
        }
        Start-Sleep -Milliseconds 500
    } while ([datetime]::UtcNow -lt $deadline)

    throw "Aucun open_success FFAudioDiag reçu en $TimeoutSeconds secondes."
}

function Wait-ForNewTransition {
    param(
        [Parameter(Mandatory = $true)][int]$ProcessId,
        [Parameter(Mandatory = $true)][ValidateSet("OFF_TO_ON", "ON_TO_OFF")][string]$Transition,
        [Parameter(Mandatory = $true)][int]$PreviousCount,
        [Parameter(Mandatory = $true)][int]$RetryKeyCode,
        [int]$TimeoutSeconds = 12
    )

    $deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
    $nextRetry = [datetime]::UtcNow.AddSeconds(1)
    do {
        $log = Get-FFAudioLog $ProcessId
        $lines = Get-TransitionLines -Log $log -Transition $Transition
        if ($lines.Count -gt $PreviousCount) {
            return [string]$lines[-1]
        }
        if ([datetime]::UtcNow -ge $nextRetry) {
            Send-KeyEvent $RetryKeyCode
            $nextRetry = [datetime]::UtcNow.AddSeconds(1)
        }
        Start-Sleep -Milliseconds 250
    } while ([datetime]::UtcNow -lt $deadline)

    throw "La transition $Transition n'a pas été observée; vérifiez l'input temporaire et l'APK instrumenté."
}

function ConvertFrom-TransitionLine {
    param([Parameter(Mandatory = $true)][string]$Line)

    if ($Line -notmatch "^\s*(?<timestamp>[0-9]+(?:\.[0-9]+)?)\s+") {
        throw "Le timestamp epoch de FFAudioDiag est absent."
    }
    $values = @{}
    foreach ($match in [regex]::Matches($Line, "(?<key>[a-z_]+)=(?<value>\S+)")) {
        $values[$match.Groups["key"].Value] = $match.Groups["value"].Value
    }
    return [pscustomobject]@{
        Timestamp = [double]::Parse($Matches.timestamp, $script:Invariant)
        Values = $values
    }
}

function Wait-ForPid {
    param([int]$TimeoutSeconds = 20)

    $deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $result = Invoke-Adb -Arguments @("shell", "pidof", $script:Package) -AllowFailure
        if ($result.ExitCode -eq 0 -and $result.Output -match "(?<pid>\d+)") {
            return [int]$Matches.pid
        }
        Start-Sleep -Milliseconds 300
    } while ([datetime]::UtcNow -lt $deadline)
    throw "Le processus melonDS n'a pas démarré."
}

function Stop-App {
    [void](Invoke-Adb -Arguments @("shell", "am", "force-stop", $script:Package) -AllowFailure)
    $deadline = [datetime]::UtcNow.AddSeconds(5)
    do {
        $processCheck = Invoke-Adb -Arguments @("shell", "pidof", $script:Package) -AllowFailure
        if ($processCheck.ExitCode -ne 0 -or -not $processCheck.Output) {
            return
        }
        Start-Sleep -Milliseconds 100
    } while ([datetime]::UtcNow -lt $deadline)
    throw "Le processus melonDS ne s'est pas arrêté après force-stop."
}

function Start-Rom {
    param([Parameter(Mandatory = $true)][string]$Uri)

    Ensure-DeviceAwake
    $action = "$($script:Package).LAUNCH_ROM"
    $component = "$($script:Package)/me.magnum.melonds.ui.emulator.EmulatorActivity"
    $command = "am start -W -a " + (Quote-RemoteArgument $action) +
        " -n " + (Quote-RemoteArgument $component) +
        " --es uri " + (Quote-RemoteArgument $Uri)
    $result = Invoke-Adb -Arguments @("shell", $command)
    if ($result.Output -notmatch "Status:\s+ok") {
        throw "L'Intent ROM n'a pas retourné Status: ok."
    }
}

function Send-KeyEvent {
    param([Parameter(Mandatory = $true)][int]$KeyCode)
    Ensure-DeviceAwake
    [void](Invoke-Adb -Arguments @("shell", "input", "keyevent", [string]$KeyCode))
}

function New-FailedResult {
    param($Test, [string]$Message)
    return [pscustomobject][ordered]@{
        test = $Test.test
        renderer = $Test.renderer
        scale = $Test.scale
        threaded = $Test.threaded
        jit = $Test.jit
        refresh_rate = $null
        duration_s = $null
        produced = $null
        read = $null
        overwritten = $null
        produced_per_s = $null
        read_per_s = $null
        overwritten_per_s = $null
        effective_speed = $null
        overwrite_percent = $null
        fully_underfed = $null
        partially_underfed = $null
        current_fifo = $null
        max_fifo = $null
        requested = $null
        multiplier = $null
        target_fps = $null
        limit_fps = $null
        status = "FAILED"
        error = $Message
    }
}

function Invoke-BenchmarkTest {
    param(
        [Parameter(Mandatory = $true)]$Test,
        [Parameter(Mandatory = $true)][byte[]]$OriginalPreferenceBytes,
        [Parameter(Mandatory = $true)]$DisplayInfos,
        [Parameter(Mandatory = $true)]$DisplayStates,
        [Parameter(Mandatory = $true)][string]$TargetRomUri,
        [Parameter(Mandatory = $true)][string]$LogDirectory
    )

    Write-Host "[$($Test.test)] renderer=$($Test.renderer), scale=$($Test.scale)x, threaded=$($Test.threaded), jit=$($Test.jit), refresh=$($Test.refresh)"
    if ($Test.refresh -eq "60") {
        Set-DisplaysTo60Hz $DisplayInfos
    }
    else {
        Restore-DisplayModes $DisplayStates
        Start-Sleep -Seconds 1
    }

    Stop-App
    if (Test-AppFile $script:PreferenceBackupPath) {
        Remove-AppFile $script:PreferenceBackupPath
    }
    $testPreferences = New-TestPreferenceBytes -OriginalBytes $OriginalPreferenceBytes -Test $Test
    Set-AppFileBytes -Path $script:PreferencePath -Bytes $testPreferences

    $processId = $null
    $ffEnabled = $false
    try {
        Start-Rom $TargetRomUri
        $processId = Wait-ForPid
        Wait-ForAudioOpen $processId
        if ($SettleSeconds -gt 0) {
            Start-Sleep -Seconds $SettleSeconds
        }

        if ($SaveStateSlot -ge 0) {
            Send-KeyEvent 29 # Android KEYCODE_A -> QUICK_LOAD in temporary controller config.
            Start-Sleep -Seconds 3
        }
        if ($WarmupSeconds -gt 0) {
            Start-Sleep -Seconds $WarmupSeconds
        }

        $beforeOn = (Get-TransitionLines -Log (Get-FFAudioLog $processId) -Transition "OFF_TO_ON").Count
        Send-KeyEvent 30 # Android KEYCODE_B -> FAST_FORWARD in temporary controller config.
        $onLine = Wait-ForNewTransition -ProcessId $processId -Transition "OFF_TO_ON" -PreviousCount $beforeOn -RetryKeyCode 30
        $ffEnabled = $true

        Start-Sleep -Seconds $MeasureSeconds

        $beforeOff = (Get-TransitionLines -Log (Get-FFAudioLog $processId) -Transition "ON_TO_OFF").Count
        Send-KeyEvent 30
        $offLine = Wait-ForNewTransition -ProcessId $processId -Transition "ON_TO_OFF" -PreviousCount $beforeOff -RetryKeyCode 30
        $ffEnabled = $false

        $log = Get-FFAudioLog $processId
        $logPath = Join-Path $LogDirectory ("{0}_FFAudioDiag.log" -f $Test.test)
        [IO.File]::WriteAllText($logPath, $log + "`n", (New-Object Text.UTF8Encoding($false)))

        $on = ConvertFrom-TransitionLine $onLine
        $off = ConvertFrom-TransitionLine $offLine
        $duration = $off.Timestamp - $on.Timestamp
        if ($duration -le 0) {
            throw "La durée FFAudioDiag calculée n'est pas positive."
        }

        $required = @(
            "stereo_frames_produced", "stereo_frames_read", "stereo_frames_overwritten",
            "stereo_frames_requested", "fully_underfed_callbacks", "partially_underfed_callbacks",
            "current_fifo_frames", "max_fifo_frames", "multiplier", "target_fps", "limit_fps"
        )
        foreach ($key in $required) {
            if (-not $off.Values.ContainsKey($key)) {
                throw "La métrique FFAudioDiag '$key' est absente."
            }
        }

        $produced = [long]$off.Values.stereo_frames_produced
        $read = [long]$off.Values.stereo_frames_read
        $overwritten = [long]$off.Values.stereo_frames_overwritten
        $producedPerSecond = $produced / $duration
        $refreshRate = Get-PrimaryRefreshRate
        if ($Test.refresh -eq "60" -and [math]::Abs($refreshRate - 60.0) -ge 0.2) {
            throw "Le test 60 Hz a été mesuré à $refreshRate Hz."
        }

        return [pscustomobject][ordered]@{
            test = $Test.test
            renderer = $Test.renderer
            scale = [int]$Test.scale
            threaded = [bool]$Test.threaded
            jit = [bool]$Test.jit
            refresh_rate = [math]::Round($refreshRate, 3)
            duration_s = [math]::Round($duration, 6)
            produced = $produced
            read = $read
            overwritten = $overwritten
            produced_per_s = [math]::Round($producedPerSecond, 3)
            read_per_s = [math]::Round($read / $duration, 3)
            overwritten_per_s = [math]::Round($overwritten / $duration, 3)
            effective_speed = [math]::Round($producedPerSecond / 48000.0, 6)
            overwrite_percent = if ($produced -gt 0) { [math]::Round(100.0 * $overwritten / $produced, 6) } else { 0.0 }
            fully_underfed = [long]$off.Values.fully_underfed_callbacks
            partially_underfed = [long]$off.Values.partially_underfed_callbacks
            current_fifo = [long]$off.Values.current_fifo_frames
            max_fifo = [long]$off.Values.max_fifo_frames
            requested = [long]$off.Values.stereo_frames_requested
            multiplier = [double]::Parse([string]$off.Values.multiplier, $script:Invariant)
            target_fps = [int]$off.Values.target_fps
            limit_fps = [string]$off.Values.limit_fps
            status = "PASS"
            error = ""
        }
    }
    finally {
        if ($ffEnabled) {
            [void](Invoke-Adb -Arguments @("shell", "input", "keyevent", "30") -AllowFailure)
            Start-Sleep -Milliseconds 300
        }
        Stop-App
    }
}

function Write-BenchmarkOutputs {
    param(
        [Parameter(Mandatory = $true)]$Results,
        [Parameter(Mandatory = $true)][string]$ResultDirectory,
        [Parameter(Mandatory = $true)]$BaselinePreferences,
        [Parameter(Mandatory = $true)][double]$BaselineRefreshRate,
        [Parameter(Mandatory = $true)][bool]$RestorationSucceeded,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$RestorationErrors,
        [string]$FatalError,
        $BestResult
    )

    # Windows PowerShell 5 has a binder bug for @($genericList); materialize
    # explicitly before CSV/report operations.
    $resultArray = $Results.ToArray()
    $csvPath = Join-Path $ResultDirectory "results.csv"
    $previousCulture = [Threading.Thread]::CurrentThread.CurrentCulture
    try {
        [Threading.Thread]::CurrentThread.CurrentCulture = $script:Invariant
        $resultArray | Export-Csv -LiteralPath $csvPath -NoTypeInformation -Encoding UTF8
    }
    finally {
        [Threading.Thread]::CurrentThread.CurrentCulture = $previousCulture
    }

    $passed = @($resultArray | Where-Object { $_.status -eq "PASS" } | Sort-Object effective_speed -Descending)
    $failed = @($resultArray | Where-Object { $_.status -ne "PASS" })
    $lines = New-Object Collections.Generic.List[string]
    $lines.Add("# Benchmark fast-forward AYN Thor")
    $lines.Add("")
    $lines.Add("- Généré : $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')")
    $lines.Add("- Appareil : AYN Thor ($($script:Serial))")
    $lines.Add("- Package : $($script:Package)")
    $lines.Add("- Protocole : $SettleSeconds s de stabilisation, $WarmupSeconds s de warm-up, fenêtre demandée de $MeasureSeconds s")
    $lines.Add("- Savestate : $(if ($SaveStateSlot -ge 0) { "slot $SaveStateSlot via QUICK_LOAD" } else { "aucun, démarrage déterministe de la ROM" })")
    $lines.Add("- Refresh initial mesuré : $([math]::Round($BaselineRefreshRate, 3)) Hz")
    $lines.Add("- Restauration : $(if ($RestorationSucceeded) { "PASS (hashes vérifiés)" } else { "FAIL" })")
    if ($BestResult) {
        $lines.Add("- Renderer retenu pour E : $($BestResult.renderer) (test $($BestResult.test))")
    }
    $lines.Add("")
    $lines.Add("## Résultats triés par vitesse effective")
    $lines.Add("")
    $lines.Add("| test | renderer | scale | threaded | JIT | Hz | durée (s) | produced/s | vitesse | écrasé (%) | underflows F/P | FIFO cur/max |")
    $lines.Add("|---|---|---:|:---:|:---:|---:|---:|---:|---:|---:|---:|---:|")
    foreach ($result in $passed) {
        $lines.Add(("| {0} | {1} | {2}x | {3} | {4} | {5:F3} | {6:F3} | {7:F1} | {8:F4}x | {9:F3} | {10}/{11} | {12}/{13} |" -f
            $result.test, $result.renderer, $result.scale, $result.threaded, $result.jit,
            $result.refresh_rate, $result.duration_s, $result.produced_per_s, $result.effective_speed,
            $result.overwrite_percent, $result.fully_underfed, $result.partially_underfed,
            $result.current_fifo, $result.max_fifo))
    }
    if (-not $passed) {
        $lines.Add("| _aucun test valide_ | | | | | | | | | | | |")
    }

    $lines.Add("")
    $lines.Add("## Réglages initiaux détectés")
    $lines.Add("")
    foreach ($entry in $BaselinePreferences.GetEnumerator()) {
        $lines.Add("- ``$($entry.Key)`` = ``$($entry.Value)``")
    }
    $lines.Add("")
    $lines.Add("Réglages fixes pendant la matrice : filtre ``none``, interpolation ``none``, rewind OFF, multiplicateur FF ``$FastForwardMultiplier``, performance soutenue OFF et audio ON.")

    if ($failed.Count -gt 0 -or $FatalError) {
        $lines.Add("")
        $lines.Add("## Erreurs")
        $lines.Add("")
        foreach ($result in $failed) {
            $safeError = ([string]$result.error).Replace("|", "\\|")
            $lines.Add("- Test $($result.test) : $safeError")
        }
        if ($FatalError) {
            $lines.Add("- Fatal : $($FatalError.Replace('|', '\\|'))")
        }
    }

    if ($RestorationErrors.Count -gt 0) {
        $lines.Add("")
        $lines.Add("## Erreurs de restauration")
        $lines.Add("")
        foreach ($errorText in $RestorationErrors) {
            $lines.Add("- $errorText")
        }
    }

    $lines.Add("")
    $lines.Add('Les logs bruts par test sont dans `logs/`. Les URI de ROM et les sauvegardes privées ne sont jamais inscrites dans ce rapport.')
    [IO.File]::WriteAllLines((Join-Path $ResultDirectory "report.md"), $lines, (New-Object Text.UTF8Encoding($false)))
}

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) {
        throw "Self-test: $Message"
    }
}

function Invoke-SelfTest {
    $fixture = "1000.000 1 2 I FFAudioDiag: transition=ON_TO_OFF multiplier=-1.000 target_fps=-60 limit_fps=false stereo_frames_produced=1440000 stereo_frames_read=100 stereo_frames_overwritten=200 stereo_frames_requested=300 fully_underfed_callbacks=4 partially_underfed_callbacks=5 current_fifo_frames=6 max_fifo_frames=7"
    $parsed = ConvertFrom-TransitionLine $fixture
    Assert-True ($parsed.Timestamp -eq 1000.0) "timestamp"
    Assert-True ([long]$parsed.Values.stereo_frames_produced -eq 1440000) "produced"

    $test = [pscustomobject]@{ renderer = "compute"; scale = 4; threaded = $true; jit = $false }
    $xml = [Text.Encoding]::UTF8.GetBytes('<?xml version="1.0" encoding="utf-8"?><map><string name="video_renderer">software</string></map>')
    [xml]$mutated = [Text.Encoding]::UTF8.GetString((New-TestPreferenceBytes $xml $test))
    Assert-True ($mutated.SelectSingleNode('/map/string[@name="video_renderer"]').InnerText -eq "compute") "renderer XML"
    Assert-True ($mutated.SelectSingleNode('/map/string[@name="video_internal_resolution"]').InnerText -eq "4") "scale XML"
    Assert-True ($mutated.SelectSingleNode('/map/boolean[@name="enable_jit"]').value -eq "false") "JIT XML"
    Assert-True ($mutated.SelectSingleNode('/map/string[@name="fast_forward_speed_multiplier"]').InnerText -eq $FastForwardMultiplier) "multiplicateur fast-forward XML"
    $expectedQuote = "'" + "a" + '''"''"''' + "b" + "'"
    Assert-True ((Quote-RemoteArgument "a'b") -eq $expectedQuote) "shell quoting"
    Write-Host "Self-test PASS"
}

if ($LibraryOnly) {
    return
}

if ($SelfTest) {
    Invoke-SelfTest
    return
}

if (-not $AdbPath) {
    if ($env:LOCALAPPDATA) {
        $candidate = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
        if (Test-Path -LiteralPath $candidate) {
            $AdbPath = $candidate
        }
    }
    if (-not $AdbPath) {
        $command = Get-Command adb -ErrorAction SilentlyContinue
        if ($command) {
            $AdbPath = $command.Source
        }
    }
}
if (-not $AdbPath -or -not (Test-Path -LiteralPath $AdbPath)) {
    throw "adb est introuvable."
}
$script:AdbExecutable = (Resolve-Path -LiteralPath $AdbPath).Path

$deviceOutput = (Invoke-Adb -Arguments @("devices", "-l") -WithoutSerial).Output
$deviceRows = @(($deviceOutput -split "`r?`n") |
    Where-Object { $_ -and $_ -notmatch "^List of devices" -and $_ -match "^(?<serial>\S+)\s+(?<state>\S+)(?:\s|$)" } |
    ForEach-Object {
    [pscustomobject]@{ Serial = $Matches.serial; State = $Matches.state; Raw = $_ }
})
if ($deviceRows.Count -ne 1 -or $deviceRows[0].State -ne "device") {
    throw "Il faut exactement un appareil adb présent et autorisé; détecté: $($deviceRows.Count)."
}
if ($DeviceSerial -and $deviceRows[0].Serial -ne $DeviceSerial) {
    throw "L'appareil autorisé ne correspond pas à -DeviceSerial."
}
$script:Serial = $deviceRows[0].Serial

$packageCheck = Invoke-Adb -Arguments @("shell", "pm", "path", $script:Package) -AllowFailure
if ($packageCheck.ExitCode -ne 0 -or $packageCheck.Output -notmatch "^package:") {
    throw "Le package $($script:Package) n'est pas installé."
}
$runAsCheck = Invoke-Adb -Arguments @("shell", "run-as", $script:Package, "id") -AllowFailure
if ($runAsCheck.ExitCode -ne 0 -or $runAsCheck.Output -notmatch "uid=") {
    throw "run-as $($script:Package) n'est pas disponible; un build debug est requis."
}
if (-not (Test-AppFile $script:PreferencePath)) {
    throw "Le fichier SharedPreferences attendu '$($script:PreferencePath)' est absent."
}

if ($DeviceTransportSelfTest) {
    $transportPath = "files/.thor_ff_transport"
    $transportBytes = New-Object byte[] 40000
    for ($index = 0; $index -lt $transportBytes.Length; $index++) {
        $transportBytes[$index] = [byte]($index % 251)
    }
    try {
        Set-AppFileBytes -Path $transportPath -Bytes $transportBytes
        $roundTripBytes = Get-AppFileBytes $transportPath
        Assert-True ((Get-ByteHash $roundTripBytes) -eq (Get-ByteHash $transportBytes)) "device transport"
        Write-Host "Device transport self-test PASS"
    }
    finally {
        Remove-AppFile $transportPath
    }
    return
}

if (-not $OutputRoot) {
    $repositoryRoot = Split-Path -Parent $PSScriptRoot
    $OutputRoot = Join-Path $repositoryRoot "build\thor_ff_results"
}
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$resultDirectory = Join-Path ([IO.Path]::GetFullPath($OutputRoot)) $timestamp
$logDirectory = Join-Path $resultDirectory "logs"
$recoveryDirectory = Join-Path $resultDirectory "recovery"
[void](New-Item -ItemType Directory -Path $logDirectory -Force)
[void](New-Item -ItemType Directory -Path $recoveryDirectory -Force)

$results = New-Object Collections.Generic.List[object]
$restorationErrors = New-Object Collections.Generic.List[string]
$fatalError = $null
$restorationSucceeded = $false
$backupReady = $false
$preferenceState = $null
$preferenceBakState = $null
$controllerState = $null
$romDataState = $null
$sramState = $null
$quickState = $null
$displayInfos = $null
$displayStates = $null
$powerState = $null
$baselinePreferences = [ordered]@{}
$baselineRefreshRate = 0.0
$bestResult = $null

try {
    $selectedRom = if ($RomUri) { $null } else { Get-RecentRom }
    $targetRomUri = if ($RomUri) { $RomUri } else { [string]$selectedRom.uri }
    if (-not $targetRomUri) {
        throw "L'URI de ROM est vide."
    }
    if ($SaveStateSlot -ge 0 -and -not $selectedRom) {
        throw "-SaveStateSlot nécessite la ROM récente du cache; omettez -RomUri."
    }

    Stop-App
    $preferenceState = Get-AppFileState $script:PreferencePath
    $preferenceBakState = Get-AppFileState $script:PreferenceBackupPath
    $controllerState = Get-AppFileState $script:ControllerPath
    $romDataState = Get-AppFileState $script:RomDataPath
    $baselinePreferences = Get-PreferenceSnapshot $preferenceState.Bytes

    [IO.File]::WriteAllBytes((Join-Path $recoveryDirectory "preferences.xml"), [byte[]]$preferenceState.Bytes)
    if ($preferenceBakState.Exists) {
        [IO.File]::WriteAllBytes((Join-Path $recoveryDirectory "preferences.xml.bak"), [byte[]]$preferenceBakState.Bytes)
    }
    if ($controllerState.Exists) {
        [IO.File]::WriteAllBytes((Join-Path $recoveryDirectory "controller_config.json"), [byte[]]$controllerState.Bytes)
    }
    [IO.File]::WriteAllBytes((Join-Path $recoveryDirectory "rom_data.json"), [byte[]]$romDataState.Bytes)

    $storagePaths = if ($selectedRom) { Resolve-PrimaryStoragePaths $selectedRom } else { $null }
    if ($storagePaths) {
        $sramState = Backup-RemoteFile -RemotePath $storagePaths.Sram -BackupPath (Join-Path $recoveryDirectory "sram.bin")
    }
    elseif ($SaveStateSlot -ge 0) {
        throw "Le chemin savestate ne peut pas être protégé avec une URI explicite."
    }

    $displayInfos = Get-DisplayInfo
    $displayStates = Get-DisplayModeStates $displayInfos
    $baselineRefreshRate = Get-PrimaryRefreshRate
    $powerState = Get-PowerState
    try {
        Enable-BenchmarkAwake
    }
    catch {
        Restore-PowerState $powerState
        throw
    }
    $backupReady = $true

    if ($SaveStateSlot -ge 0) {
        $quickState = New-TemporaryQuickState $storagePaths
    }

    $controllerJson = '{"inputMapper":[{"input":"FAST_FORWARD","assignment":{"type":"key","deviceId":null,"keyCode":30}},{"input":"QUICK_LOAD","assignment":{"type":"key","deviceId":null,"keyCode":29}},{"input":"QUICK_SAVE","assignment":{"type":"key","deviceId":null,"keyCode":31}}]}'
    Set-AppFileBytes -Path $script:ControllerPath -Bytes ([Text.Encoding]::UTF8.GetBytes($controllerJson))

    $matrix = if ($RepeatSoftware -gt 0) {
        @(1..$RepeatSoftware | ForEach-Object {
            [pscustomobject]@{ test = "R$_"; renderer = "software"; scale = 1; threaded = $true; jit = $true; refresh = "current" }
        })
    }
    else {
        @(
            [pscustomobject]@{ test = "A"; renderer = "software"; scale = 1; threaded = $true; jit = $true; refresh = "current" },
            [pscustomobject]@{ test = "B"; renderer = "software"; scale = 1; threaded = $false; jit = $true; refresh = "current" },
            [pscustomobject]@{ test = "C"; renderer = "opengl"; scale = 1; threaded = $true; jit = $true; refresh = "current" },
            [pscustomobject]@{ test = "D"; renderer = "compute"; scale = 1; threaded = $true; jit = $true; refresh = "current" }
        )
    }

    foreach ($test in $matrix) {
        try {
            $result = Invoke-BenchmarkTest $test $preferenceState.Bytes $displayInfos $displayStates $targetRomUri $logDirectory
            $results.Add($result)
        }
        catch {
            $message = $_.Exception.Message
            Write-Warning "Test $($test.test) échoué: $message"
            $results.Add((New-FailedResult $test $message))
        }
    }

    if ($RepeatSoftware -eq 0) {
        $bestResult = $results |
            Where-Object { $_.status -eq "PASS" -and $_.test -in @("A", "C", "D") } |
            Sort-Object effective_speed -Descending |
            Select-Object -First 1
        if (-not $bestResult) {
            throw "Aucun résultat A/C/D valide ne permet de sélectionner le renderer de E."
        }

        $testE = [pscustomobject]@{
            test = "E"; renderer = $bestResult.renderer; scale = 1; threaded = $bestResult.threaded; jit = $true; refresh = "60"
        }
        try {
            $results.Add((Invoke-BenchmarkTest $testE $preferenceState.Bytes $displayInfos $displayStates $targetRomUri $logDirectory))
        }
        catch {
            Write-Warning "Test E échoué: $($_.Exception.Message)"
            $results.Add((New-FailedResult $testE $_.Exception.Message))
        }
    }

    Restore-DisplayModes $displayStates
    if ($IncludeOptional -and $RepeatSoftware -eq 0) {
        $optionalMatrix = @(
            [pscustomobject]@{ test = "F"; renderer = $bestResult.renderer; scale = 1; threaded = $bestResult.threaded; jit = $false; refresh = "current" },
            [pscustomobject]@{ test = "G"; renderer = $bestResult.renderer; scale = 4; threaded = $bestResult.threaded; jit = $true; refresh = "current" }
        )
        foreach ($test in $optionalMatrix) {
            try {
                $results.Add((Invoke-BenchmarkTest $test $preferenceState.Bytes $displayInfos $displayStates $targetRomUri $logDirectory))
            }
            catch {
                Write-Warning "Test $($test.test) échoué: $($_.Exception.Message)"
                $results.Add((New-FailedResult $test $_.Exception.Message))
            }
        }
    }
}
catch {
    $fatalError = $_.Exception.Message
    Write-Warning "Benchmark interrompu: $fatalError"
}
finally {
    Stop-App
    if ($backupReady) {
        foreach ($restoreAction in @(
            @{ Name = "préférences"; Action = { Restore-AppFileState $preferenceState } },
            @{ Name = "backup SharedPreferences"; Action = { Restore-AppFileState $preferenceBakState } },
            @{ Name = "contrôleur"; Action = { Restore-AppFileState $controllerState } },
            @{ Name = "métadonnées ROM"; Action = { Restore-AppFileState $romDataState } },
            @{ Name = "SRAM"; Action = { if ($sramState) { Restore-RemoteFile $sramState } } },
            @{ Name = "savestate rapide temporaire"; Action = { Remove-TemporaryQuickState $quickState } },
            @{ Name = "refresh rate"; Action = { Restore-DisplayModes $displayStates } },
            @{ Name = "veille écran"; Action = { Restore-PowerState $powerState } }
        )) {
            try {
                & $restoreAction.Action
            }
            catch {
                $restorationErrors.Add("$($restoreAction.Name): $($_.Exception.Message)")
            }
        }
        $restorationSucceeded = $restorationErrors.Count -eq 0
    }
    else {
        $restorationErrors.Add("La sauvegarde initiale complète n'avait pas encore été établie.")
    }
}

Write-BenchmarkOutputs -Results $results -ResultDirectory $resultDirectory `
    -BaselinePreferences $baselinePreferences -BaselineRefreshRate $baselineRefreshRate `
    -RestorationSucceeded $restorationSucceeded -RestorationErrors $restorationErrors.ToArray() `
    -FatalError $fatalError -BestResult $bestResult

if ($restorationSucceeded) {
    $resultFull = [IO.Path]::GetFullPath($resultDirectory).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $recoveryFull = [IO.Path]::GetFullPath($recoveryDirectory)
    if (-not $recoveryFull.StartsWith($resultFull + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refus de supprimer un dossier de récupération hors du résultat courant."
    }
    Remove-Item -LiteralPath $recoveryFull -Recurse -Force
}

Write-Host "Résultats: $resultDirectory"
Write-Host "Restauration: $(if ($restorationSucceeded) { 'PASS' } else { 'FAIL' })"
if ($fatalError) {
    throw $fatalError
}
if (-not $restorationSucceeded) {
    throw "La restauration est incomplète; conservez le dossier recovery."
}
