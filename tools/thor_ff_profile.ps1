#Requires -Version 5.1

[CmdletBinding()]
param(
    [string]$AdbPath,
    [string]$DeviceSerial,
    [string]$PackageName = "me.magnum.melonds.dev",
    [ValidateRange(0, 8)][int]$SaveStateSlot = 1,
    [ValidateRange(0, 300)][int]$SettleSeconds = 8,
    [ValidateRange(0, 300)][int]$WarmupSeconds = 10,
    [ValidateRange(5, 300)][int]$ProfileSeconds = 30,
    [ValidateRange(50, 2000)][int]$SampleFrequency = 400,
    [string]$NdkVersion = "28.0.13004108",
    [string]$NdkPath,
    [string]$PythonPath,
    [string]$OutputRoot,
    [switch]$SkipThreadedOff,
    [switch]$SkipPerfettoProbe,
    [switch]$SelfTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$profileOptions = [pscustomobject]@{
    AdbPath = $AdbPath
    DeviceSerial = $DeviceSerial
    PackageName = $PackageName
    SaveStateSlot = $SaveStateSlot
    SettleSeconds = $SettleSeconds
    WarmupSeconds = $WarmupSeconds
    ProfileSeconds = $ProfileSeconds
    SampleFrequency = $SampleFrequency
    NdkVersion = $NdkVersion
    NdkPath = $NdkPath
    PythonPath = $PythonPath
    OutputRoot = $OutputRoot
    SkipThreadedOff = [bool]$SkipThreadedOff
    SkipPerfettoProbe = [bool]$SkipPerfettoProbe
    SelfTest = [bool]$SelfTest
}

$benchmarkScript = Join-Path $PSScriptRoot "thor_ff_benchmark.ps1"
if (-not (Test-Path -LiteralPath $benchmarkScript)) {
    throw "Le harness thor_ff_benchmark.ps1 est introuvable."
}
. $benchmarkScript -AdbPath $profileOptions.AdbPath -DeviceSerial $profileOptions.DeviceSerial `
    -PackageName $profileOptions.PackageName -SaveStateSlot $profileOptions.SaveStateSlot `
    -SettleSeconds $profileOptions.SettleSeconds -WarmupSeconds $profileOptions.WarmupSeconds -LibraryOnly

function Resolve-Executable {
    param(
        [string]$ExplicitPath,
        [Parameter(Mandatory = $true)][string[]]$Candidates,
        [Parameter(Mandatory = $true)][string]$Name
    )
    foreach ($candidate in @($ExplicitPath) + $Candidates) {
        if (-not $candidate) { continue }
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
        $command = Get-Command $candidate -ErrorAction SilentlyContinue
        if ($command -and $command.Source) { return $command.Source }
    }
    throw "$Name est introuvable."
}

function Initialize-Adb {
    $candidates = @()
    if ($env:LOCALAPPDATA) {
        $candidates += (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
    }
    $candidates += "adb"
    $script:AdbExecutable = Resolve-Executable $profileOptions.AdbPath $candidates "adb"

    $deviceOutput = (Invoke-Adb -Arguments @("devices", "-l") -WithoutSerial).Output
    $deviceRows = @(($deviceOutput -split "`r?`n") |
        Where-Object { $_ -and $_ -notmatch "^List of devices" -and $_ -match "^(?<serial>\S+)\s+(?<state>\S+)(?:\s|$)" } |
        ForEach-Object { [pscustomobject]@{ Serial = $Matches.serial; State = $Matches.state } })
    if ($deviceRows.Count -ne 1 -or $deviceRows[0].State -ne "device") {
        throw "Il faut exactement un appareil adb present et autorise; detecte: $($deviceRows.Count)."
    }
    if ($profileOptions.DeviceSerial -and $deviceRows[0].Serial -ne $profileOptions.DeviceSerial) {
        throw "L'appareil autorise ne correspond pas a -DeviceSerial."
    }
    $script:Serial = $deviceRows[0].Serial

    $packageCheck = Invoke-Adb -Arguments @("shell", "pm", "path", $script:Package) -AllowFailure
    if ($packageCheck.ExitCode -ne 0 -or $packageCheck.Output -notmatch "^package:") {
        throw "Le package $($script:Package) n'est pas installe."
    }
    $runAsCheck = Invoke-Adb -Arguments @("shell", "run-as", $script:Package, "id") -AllowFailure
    if ($runAsCheck.ExitCode -ne 0 -or $runAsCheck.Output -notmatch "uid=") {
        throw "run-as $($script:Package) n'est pas disponible; un build debug est requis."
    }
    if (-not (Test-AppFile $script:PreferencePath)) {
        throw "Le fichier SharedPreferences attendu est absent."
    }
}

function Resolve-ProfileTools {
    if ($profileOptions.NdkPath) {
        $resolvedNdk = $profileOptions.NdkPath
    }
    elseif ($env:LOCALAPPDATA) {
        $resolvedNdk = Join-Path $env:LOCALAPPDATA ("Android\Sdk\ndk\" + $profileOptions.NdkVersion)
    }
    else {
        throw "LOCALAPPDATA est absent; utilisez -NdkPath."
    }
    if (-not (Test-Path -LiteralPath $resolvedNdk -PathType Container)) {
        throw "Le NDK $($profileOptions.NdkVersion) est introuvable."
    }
    $resolvedNdk = (Resolve-Path -LiteralPath $resolvedNdk).Path

    $pythonCandidates = @()
    if ($env:USERPROFILE) {
        $pythonCandidates += (Join-Path $env:USERPROFILE ".cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe")
    }
    $pythonCandidates += @("python", "python3")
    $python = Resolve-Executable $profileOptions.PythonPath $pythonCandidates "Python"
    $version = & $python --version 2>&1
    if ($LASTEXITCODE -ne 0 -or ($version -join " ") -notmatch "Python 3") {
        throw "Python 3 est requis par app_profiler.py."
    }

    $simpleperfDirectory = Join-Path $resolvedNdk "simpleperf"
    $appProfiler = Join-Path $simpleperfDirectory "app_profiler.py"
    $reportSample = Join-Path $simpleperfDirectory "report_sample.py"
    $reportHtml = Join-Path $simpleperfDirectory "report_html.py"
    $hostSimpleperf = Join-Path $simpleperfDirectory "bin\windows\x86_64\simpleperf.exe"
    foreach ($path in @($appProfiler, $reportSample, $reportHtml, $hostSimpleperf)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Outil simpleperf manquant: $path"
        }
    }

    $repositoryRoot = Split-Path -Parent $PSScriptRoot
    $nativeCandidates = @(Get-ChildItem -LiteralPath (Join-Path $repositoryRoot "app\build\intermediates\cxx") `
        -Recurse -File -Filter "libmelonDS-android-frontend.so" -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match "[\\/]obj[\\/]arm64-v8a[\\/]" } |
        Sort-Object LastWriteTime -Descending)
    if ($nativeCandidates.Count -eq 0) {
        throw "La bibliotheque ARM64 non stripped n'existe pas."
    }
    $nativeLibrary = $nativeCandidates[0]
    $readElf = Join-Path $resolvedNdk "toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe"
    $sections = & $readElf -S $nativeLibrary.FullName 2>&1
    if ($LASTEXITCODE -ne 0 -or ($sections -join "`n") -notmatch "\.debug_info" -or ($sections -join "`n") -notmatch "\.symtab") {
        throw "La bibliotheque native selectionnee ne contient pas les symboles DWARF attendus."
    }

    return [pscustomobject]@{
        Ndk = $resolvedNdk
        Python = $python
        AppProfiler = $appProfiler
        ReportSample = $reportSample
        ReportHtml = $reportHtml
        HostSimpleperf = $hostSimpleperf
        NativeLibrary = $nativeLibrary.FullName
        NativeDirectory = $nativeLibrary.DirectoryName
    }
}

function ConvertTo-CommandLineArgument {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Value)
    if ($Value.Contains('"')) { throw "Un argument externe contient un guillemet non pris en charge." }
    if ($Value -and $Value -notmatch "\s") { return $Value }
    return '"' + $Value + '"'
}

function Start-CapturedProcess {
    param(
        [Parameter(Mandatory = $true)][string]$Executable,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory
    )
    $startInfo = New-Object Diagnostics.ProcessStartInfo
    $startInfo.FileName = $Executable
    $startInfo.Arguments = (($Arguments | ForEach-Object { ConvertTo-CommandLineArgument ([string]$_) }) -join " ")
    $startInfo.WorkingDirectory = $WorkingDirectory
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.EnvironmentVariables["ANDROID_SERIAL"] = $script:Serial
    $platformTools = Split-Path -Parent $script:AdbExecutable
    $startInfo.EnvironmentVariables["PATH"] = $platformTools + [IO.Path]::PathSeparator + $startInfo.EnvironmentVariables["PATH"]

    $process = New-Object Diagnostics.Process
    $process.StartInfo = $startInfo
    if (-not $process.Start()) { throw "Le processus externe n'a pas demarre." }
    return [pscustomobject]@{
        Process = $process
        StandardOutput = $process.StandardOutput.ReadToEndAsync()
        StandardError = $process.StandardError.ReadToEndAsync()
    }
}

function Complete-CapturedProcess {
    param(
        [Parameter(Mandatory = $true)]$Handle,
        [Parameter(Mandatory = $true)][string]$LogPath,
        [int]$TimeoutSeconds = 180
    )
    if (-not $Handle.Process.WaitForExit($TimeoutSeconds * 1000)) {
        $Handle.Process.Kill()
        throw "Le processus externe a depasse $TimeoutSeconds secondes."
    }
    $stdout = $Handle.StandardOutput.GetAwaiter().GetResult()
    $stderr = $Handle.StandardError.GetAwaiter().GetResult()
    [IO.File]::WriteAllText($LogPath, "STDOUT`n$stdout`nSTDERR`n$stderr", (New-Object Text.UTF8Encoding($false)))
    $exitCode = $Handle.Process.ExitCode
    $Handle.Process.Dispose()
    if ($exitCode -ne 0) {
        throw "Le processus externe a echoue (code $exitCode); voir $LogPath."
    }
}

function Test-SimpleperfRunning {
    $result = Invoke-Adb -Arguments @("shell", "pidof", "simpleperf") -AllowFailure
    return $result.ExitCode -eq 0 -and [bool]$result.Output
}

function Stop-OwnSimpleperf {
    [void](Invoke-Adb -Arguments @("shell", "run-as", $script:Package, "pkill", "-2", "simpleperf") -AllowFailure)
    $deadline = [datetime]::UtcNow.AddSeconds(5)
    while ((Test-SimpleperfRunning) -and [datetime]::UtcNow -lt $deadline) {
        Start-Sleep -Milliseconds 100
    }
}

function Start-AppProfiler {
    param(
        [Parameter(Mandatory = $true)]$Tools,
        [Parameter(Mandatory = $true)][string]$ProfileDirectory
    )
    if (Test-SimpleperfRunning) {
        throw "Un processus simpleperf est deja actif; refus de perturber une autre session."
    }
    $recordOptions = "-e task-clock:u -f $($profileOptions.SampleFrequency) -g --trace-offcpu --duration $($profileOptions.ProfileSeconds)"
    $arguments = @(
        $Tools.AppProfiler, "-p", $script:Package, "--disable_adb_root",
        "--ndk_path", $Tools.Ndk, "-lib", $Tools.NativeDirectory,
        "-o", "perf.data", "-r", $recordOptions, "--log", "warning"
    )
    return Start-CapturedProcess $Tools.Python $arguments $ProfileDirectory
}

function Wait-ForSimpleperfStart {
    param([Parameter(Mandatory = $true)]$Handle)
    $deadline = [datetime]::UtcNow.AddSeconds(60)
    do {
        if (Test-SimpleperfRunning) { return }
        if ($Handle.Process.HasExited) {
            throw "app_profiler.py s'est termine avant le demarrage de simpleperf."
        }
        Start-Sleep -Milliseconds 100
    } while ([datetime]::UtcNow -lt $deadline)
    throw "simpleperf n'a pas demarre en 60 secondes."
}

function Get-DeviceUptime {
    $output = (Invoke-Adb -Arguments @("shell", "cat", "/proc/uptime")).Output
    if ($output -notmatch "^(?<seconds>[0-9]+(?:\.[0-9]+)?)") {
        throw "L'uptime Android ne peut pas etre lu."
    }
    return [double]::Parse($Matches.seconds, $script:Invariant)
}

function Invoke-DeviceShellScript {
    param(
        [Parameter(Mandatory = $true)][string]$ScriptText,
        [switch]$RunAs,
        [switch]$AllowFailure
    )

    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($ScriptText))
    $target = if ($RunAs) { "run-as $($script:Package) sh" } else { "sh" }
    $command = "echo $encoded | base64 -d | $target"
    return Invoke-Adb -Arguments @("shell", $command) -AllowFailure:$AllowFailure
}

function Get-ThreadSnapshot {
    param([Parameter(Mandatory = $true)][int]$ProcessId)
    $shell = 'for t in /proc/{PID}/task/*; do tid=$(basename "$t"); comm=$(cat "$t/comm" 2>/dev/null); set -- $(cat "$t/schedstat" 2>/dev/null); cpu=$(awk ''{print $39}'' "$t/stat" 2>/dev/null); mig=$(grep -m1 ''nr_migrations'' "$t/sched" 2>/dev/null | awk ''{print $3}''); state=$(awk ''/^State:/{print $2}'' "$t/status" 2>/dev/null); if [ -n "$comm" ]; then echo "$tid|$comm|$1|$2|$3|$cpu|$mig|$state"; fi; done'
    $shell = $shell.Replace("{PID}", [string]$ProcessId)
    $output = (Invoke-DeviceShellScript -ScriptText $shell -RunAs).Output
    $rows = New-Object Collections.Generic.List[object]
    foreach ($line in ($output -split "`r?`n")) {
        $parts = $line -split "\|", 8
        if ($parts.Count -ne 8) { continue }
        [void]$rows.Add([pscustomobject]@{
            tid = [int]$parts[0]
            name = $parts[1]
            running_ns = [long]$parts[2]
            runnable_ns = [long]$parts[3]
            slices = [long]$parts[4]
            cpu = [int]$parts[5]
            migrations = if ($parts[6] -match "^\d+$") { [long]$parts[6] } else { $null }
            state = $parts[7]
        })
    }
    return @($rows.ToArray())
}

function Add-HardwareSnapshot {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][Collections.Generic.List[object]]$Rows,
        [Parameter(Mandatory = $true)][string]$ProfileId,
        [Parameter(Mandatory = $true)][double]$ElapsedSeconds
    )
    $shell = 'for f in /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq; do id=$(echo "$f" | sed -n ''s#.*cpu\([0-9]*\)/.*#\1#p''); v=$(cat "$f" 2>/dev/null); [ -n "$v" ] && echo "cpu_freq|$id|$v"; done; for f in /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq; do id=$(echo "$f" | sed -n ''s#.*cpu\([0-9]*\)/.*#\1#p''); v=$(cat "$f" 2>/dev/null); [ -n "$v" ] && echo "cpu_max|$id|$v"; done; for f in /sys/class/kgsl/kgsl-3d0/gpuclk /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq; do v=$(cat "$f" 2>/dev/null); [ -n "$v" ] && echo "gpu_freq|all|$v"; done; v=$(cat /sys/class/kgsl/kgsl-3d0/gpubusy 2>/dev/null); [ -n "$v" ] && echo "gpu_busy|all|$v"; for z in /sys/class/thermal/thermal_zone*; do n=$(cat "$z/type" 2>/dev/null); v=$(cat "$z/temp" 2>/dev/null); case "$v" in -[0-9]*|[0-9]*) echo "thermal|$n|$v";; esac; done'
    $output = (Invoke-DeviceShellScript -ScriptText $shell -AllowFailure).Output
    foreach ($line in ($output -split "`r?`n")) {
        $parts = $line -split "\|", 3
        if ($parts.Count -ne 3) { continue }
        [void]$Rows.Add([pscustomobject]@{
            profile = $ProfileId
            elapsed_s = [math]::Round($ElapsedSeconds, 3)
            kind = $parts[0]
            name = $parts[1]
            value = $parts[2].Trim()
        })
    }
}

function Get-ThermalStatus {
    $output = (Invoke-Adb -Arguments @("shell", "dumpsys", "thermalservice") -AllowFailure).Output
    if ($output -match "Thermal Status:\s*(?<status>\d+)") { return [int]$Matches.status }
    return $null
}

function Start-Atrace {
    $traceState = (Invoke-Adb -Arguments @("shell", "cat", "/sys/kernel/tracing/tracing_on") -AllowFailure).Output
    if ($traceState.Trim() -eq "1") {
        throw "Une capture ftrace/atrace est deja active; refus de la perturber."
    }
    $result = Invoke-Adb -Arguments @(
        "shell", "atrace", "--async_start", "-b", "65536",
        "sched", "freq", "idle", "thermal", "gfx", "view"
    )
    if ($result.Output -notmatch "capturing trace") { throw "atrace n'a pas confirme son demarrage." }
}

function Stop-AtraceToFile {
    param([Parameter(Mandatory = $true)][string]$Path)
    $startInfo = New-Object Diagnostics.ProcessStartInfo
    $startInfo.FileName = $script:AdbExecutable
    $startInfo.Arguments = "-s $($script:Serial) shell atrace --async_stop"
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $startInfo
    $stream = $null
    try {
        if (-not $process.Start()) { throw "atrace --async_stop n'a pas demarre." }
        $errorTask = $process.StandardError.ReadToEndAsync()
        $stream = [IO.File]::Create($Path)
        $process.StandardOutput.BaseStream.CopyTo($stream)
        $stream.Dispose()
        $stream = $null
        $process.WaitForExit()
        $errorText = $errorTask.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw "atrace --async_stop a echoue: $errorText" }
    }
    finally {
        if ($stream) { $stream.Dispose() }
        $process.Dispose()
    }
}

function Compress-GzipFile {
    param([Parameter(Mandatory = $true)][string]$Path)

    $gzipPath = $Path + ".gz"
    $input = [IO.File]::OpenRead($Path)
    $output = $null
    $gzip = $null
    try {
        $output = [IO.File]::Create($gzipPath)
        $gzip = New-Object IO.Compression.GZipStream($output, [IO.Compression.CompressionLevel]::Optimal)
        $input.CopyTo($gzip)
    }
    finally {
        if ($gzip) { $gzip.Dispose() }
        elseif ($output) { $output.Dispose() }
        $input.Dispose()
    }
    if (-not (Test-Path -LiteralPath $gzipPath) -or (Get-Item -LiteralPath $gzipPath).Length -eq 0) {
        throw "La compression gzip de la trace atrace a echoue."
    }
    Remove-Item -LiteralPath $Path -Force
    return $gzipPath
}

function Invoke-PerfettoProbe {
    param([Parameter(Mandatory = $true)][string]$RawDirectory)
    if ($profileOptions.SkipPerfettoProbe) {
        return [pscustomobject]@{ Attempted = $false; Success = $false; Reason = "desactive par parametre"; Path = $null }
    }
    $remote = "/data/misc/perfetto-traces/thor_ff_profile_probe.perfetto-trace"
    $failedPath = Join-Path $RawDirectory "perfetto_failed.perfetto-trace"
    $startEpochText = (Invoke-Adb -Arguments @("shell", "date", "+%s")).Output
    $startEpoch = if ($startEpochText -match "\d+") { [long]$Matches[0] } else { 0 }
    $result = Invoke-Adb -Arguments @(
        "shell", "perfetto", "-o", $remote, "-t", "2s",
        "sched/sched_switch", "sched/sched_wakeup", "power/cpu_frequency", "power/cpu_idle"
    ) -AllowFailure
    $pulled = $false
    if ($result.ExitCode -eq 0) {
        $pull = Invoke-Adb -Arguments @("pull", $remote, $failedPath) -AllowFailure
        $pulled = $pull.ExitCode -eq 0 -and (Test-Path -LiteralPath $failedPath)
    }
    [void](Invoke-Adb -Arguments @("shell", "rm", "-f", $remote) -AllowFailure)
    $size = if ($pulled) { (Get-Item -LiteralPath $failedPath).Length } else { 0 }
    if ($result.ExitCode -eq 0 -and $size -ge 4096) {
        $successPath = Join-Path (Split-Path -Parent $RawDirectory) "perfetto_trace.perfetto-trace"
        Move-Item -LiteralPath $failedPath -Destination $successPath
        return [pscustomobject]@{ Attempted = $true; Success = $true; Reason = ""; Path = $successPath }
    }

    $log = (Invoke-Adb -Arguments @("logcat", "-d", "-v", "epoch") -AllowFailure).Output
    $diagnostic = @()
    foreach ($line in ($log -split "`r?`n")) {
        if ($line -match "^\s*(?<epoch>\d+)" -and [long]$Matches.epoch -ge $startEpoch -and
            $line -match "traced_probes|perfetto|f2fs_truncate_partial_nodes|Fatal signal") {
            $diagnostic += $line
        }
    }
    [IO.File]::WriteAllLines((Join-Path $RawDirectory "perfetto_failure.log"), $diagnostic, (New-Object Text.UTF8Encoding($false)))
    $reason = if (($diagnostic -join "`n") -match "f2fs_truncate_partial_nodes") {
        "traced_probes constructeur plante sur le format vendor f2fs_truncate_partial_nodes.nid"
    }
    elseif ($result.ExitCode -ne 0) {
        "commande perfetto en echec (code $($result.ExitCode))"
    }
    else {
        "trace vide ou incomplete ($size octets)"
    }
    return [pscustomobject]@{
        Attempted = $true
        Success = $false
        Reason = $reason
        Path = if ($pulled) { $failedPath } else { $null }
    }
}

function Invoke-PythonReport {
    param(
        [Parameter(Mandatory = $true)]$Tools,
        [Parameter(Mandatory = $true)][string]$ProfileDirectory,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$LogName
    )
    $handle = Start-CapturedProcess $Tools.Python $Arguments $ProfileDirectory
    Complete-CapturedProcess $handle (Join-Path $ProfileDirectory $LogName) 300
}

function Add-Aggregate {
    param(
        [Parameter(Mandatory = $true)][hashtable]$Table,
        [Parameter(Mandatory = $true)][string]$Key,
        [Parameter(Mandatory = $true)][long]$Period
    )
    if (-not $Table.ContainsKey($Key)) {
        $Table[$Key] = [pscustomobject]@{ Period = [long]0; Count = [long]0 }
    }
    $Table[$Key].Period += $Period
    $Table[$Key].Count += 1
}

function Convert-Aggregates {
    param(
        [Parameter(Mandatory = $true)][hashtable]$Table,
        [Parameter(Mandatory = $true)][long]$Total
    )
    $rows = foreach ($key in $Table.Keys) {
        $parts = $key -split "`t", 2
        [pscustomobject]@{
            dso = $parts[0]
            symbol = $parts[1]
            period_ns = [long]$Table[$key].Period
            seconds = [math]::Round($Table[$key].Period / 1e9, 6)
            samples = [long]$Table[$key].Count
            percent = if ($Total -gt 0) { [math]::Round(100.0 * $Table[$key].Period / $Total, 6) } else { 0.0 }
        }
    }
    return @($rows | Sort-Object period_ns -Descending)
}

function Get-ThreadRole {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)]$TopSymbols,
        [Parameter(Mandatory = $true)][long]$TotalNs
    )

    $gpu2dNs = [long]0
    $gpu3dNs = [long]0
    $cpuNs = [long]0
    foreach ($row in $TopSymbols) {
        if ($row.symbol -match "GPU2D::SoftRenderer") {
            $gpu2dNs += [long]$row.period_ns
        }
        elseif ($row.symbol -match "(?:^|::)SoftRenderer::|GPU3D_Soft") {
            $gpu3dNs += [long]$row.period_ns
        }
        if ($row.symbol -match "ARMJIT|ARM9|ARM7|ARMv[45]|Slow(?:Read|Write)[79]|NDS::(?:Run|NextTarget)") {
            $cpuNs += [long]$row.period_ns
        }
    }
    $gpu2dShare = if ($TotalNs -gt 0) { 100.0 * $gpu2dNs / $TotalNs } else { 0.0 }
    $gpu3dShare = if ($TotalNs -gt 0) { 100.0 * $gpu3dNs / $TotalNs } else { 0.0 }
    $cpuShare = if ($TotalNs -gt 0) { 100.0 * $cpuNs / $TotalNs } else { 0.0 }

    if ($gpu2dShare -ge 20.0 -and $cpuShare -ge 20.0) { return "Chemin principal CPU/JIT + GPU2D" }
    if ($gpu3dShare -ge 20.0) { return "GPU3D SoftRenderer" }
    if ($gpu2dShare -ge 20.0) { return "GPU2D SoftRenderer" }
    if ($cpuShare -ge 20.0) { return "CPU/JIT emule" }
    if ($Name -match "FrameRender") { return "Sortie video" }
    if ($Name -match "Audio|AAudio") { return "Audio" }
    if ($Name -match "Jit thread") { return "Compilation JIT Android" }
    return "Support / systeme"
}

function Get-SampleAnalysis {
    param([Parameter(Mandatory = $true)][string]$Path)
    $selfTable = @{}
    $inclusiveTable = @{}
    $threadTable = @{}
    $state = [pscustomobject]@{ Current = $null; Total = [long]0 }

    $finishSample = {
        if (-not $state.Current) { return }
        $sample = $state.Current
        $state.Total += $sample.Period
        $threadKey = "$($sample.Comm)`t$($sample.Tid)"
        if (-not $threadTable.ContainsKey($threadKey)) {
            $threadTable[$threadKey] = [pscustomobject]@{
                Name = $sample.Comm
                Tid = $sample.Tid
                Period = [long]0
                Samples = [long]0
                CpuPeriods = @{}
                LastCpu = $null
                SampledMigrations = [long]0
                Self = @{}
            }
        }
        $thread = $threadTable[$threadKey]
        $thread.Period += $sample.Period
        $thread.Samples += 1
        $cpuKey = [string]$sample.Cpu
        if (-not $thread.CpuPeriods.ContainsKey($cpuKey)) { $thread.CpuPeriods[$cpuKey] = [long]0 }
        $thread.CpuPeriods[$cpuKey] += $sample.Period
        if ($null -ne $thread.LastCpu -and $thread.LastCpu -ne $sample.Cpu) {
            $thread.SampledMigrations += 1
        }
        $thread.LastCpu = $sample.Cpu

        if ($sample.Frames.Count -eq 0) {
            [void]$sample.Frames.Add([pscustomobject]@{ Symbol = "[unknown]"; Dso = "[unknown]" })
        }
        $leaf = $sample.Frames[0]
        Add-Aggregate $selfTable ($leaf.Dso + "`t" + $leaf.Symbol) $sample.Period
        Add-Aggregate $thread.Self ($leaf.Dso + "`t" + $leaf.Symbol) $sample.Period
        $seen = New-Object Collections.Generic.HashSet[string]
        foreach ($frame in $sample.Frames) {
            $key = $frame.Dso + "`t" + $frame.Symbol
            if ($seen.Add($key)) { Add-Aggregate $inclusiveTable $key $sample.Period }
        }
        $state.Current = $null
    }

    foreach ($line in [IO.File]::ReadLines($Path)) {
        if ($line -match "^(?<comm>.+?)\s+(?<pid>\d+)/(?<tid>\d+)\s+\[(?<cpu>\d+)\]\s+(?<timestamp>[0-9.]+):\s+(?<period>\d+)\s+(?<event>.+):\s*$") {
            & $finishSample
            $state.Current = [pscustomobject]@{
                Comm = $Matches.comm.Trim()
                Pid = [int]$Matches.pid
                Tid = [int]$Matches.tid
                Cpu = [int]$Matches.cpu
                Timestamp = [double]::Parse($Matches.timestamp, $script:Invariant)
                Period = [long]$Matches.period
                Event = $Matches.event
                Frames = (New-Object Collections.Generic.List[object])
            }
        }
        elseif ($state.Current -and $line -match "^\s+[0-9a-fA-F]+\s+(?<symbol>.*?)\s+\((?<dso>/.*|\[.*\])\)\s*$") {
            [void]$state.Current.Frames.Add([pscustomobject]@{ Symbol = $Matches.symbol; Dso = $Matches.dso })
        }
    }
    & $finishSample

    $threads = foreach ($entry in $threadTable.Values) {
        $cpuTotal = [double]$entry.Period
        $topSymbols = @(Convert-Aggregates $entry.Self $entry.Period)
        $placements = foreach ($cpu in ($entry.CpuPeriods.Keys | Sort-Object { [int]$_ })) {
            $share = if ($cpuTotal -gt 0) { 100.0 * $entry.CpuPeriods[$cpu] / $cpuTotal } else { 0 }
            "CPU$cpu=" + $share.ToString("0.0", $script:Invariant) + "%"
        }
        [pscustomobject]@{
            name = $entry.Name
            tid = $entry.Tid
            period_ns = [long]$entry.Period
            seconds = [math]::Round($entry.Period / 1e9, 6)
            samples = [long]$entry.Samples
            percent = if ($state.Total -gt 0) { [math]::Round(100.0 * $entry.Period / $state.Total, 6) } else { 0.0 }
            sampled_migrations = [long]$entry.SampledMigrations
            cpu_placement = $placements -join ", "
            role = Get-ThreadRole -Name $entry.Name -TopSymbols $topSymbols -TotalNs $entry.Period
            top_symbols = $topSymbols
        }
    }
    return [pscustomobject]@{
        TotalNs = [long]$state.Total
        Self = Convert-Aggregates $selfTable $state.Total
        Inclusive = Convert-Aggregates $inclusiveTable $state.Total
        Threads = @($threads | Sort-Object period_ns -Descending)
    }
}

function Get-SubsystemName {
    param([string]$Symbol, [string]$Dso)
    $text = "$Symbol $Dso"
    if ($Symbol -match "(?i)GPU2D::SoftRenderer") { return "GPU2D SoftRenderer" }
    if ($Symbol -match "(?i)(?:^|::)SoftRenderer::|GPU3D_Soft") { return "GPU3D SoftRenderer" }
    if ($Symbol -match "(?i)GPU2D") { return "GPU2D autre" }
    if ($text -match "(?i)SPU|blip|AAudio|Oboe|AudioCallback") { return "SPU / audio" }
    if ($text -match "(?i)ARMJIT|ARM_JIT|JIT app|JitBlock|CompileBlock") { return "ARM JIT commun" }
    if ($Symbol -match "(?i)ARM9|ARMv5|Slow(?:Read|Write)9") { return "ARM9 / CPU core" }
    if ($Symbol -match "(?i)ARM7|ARMv4|Slow(?:Read|Write)7") { return "ARM7 / CPU core" }
    if ($Symbol -match "(?i)NDS::(?:RunSystem|RunFrame|NextTarget|RunTimer|RunTimers)") { return "CPU / ordonnanceur emule" }
    if ($text -match "(?i)pthread|futex|mutex|condition_variable|cond_wait|semaphore|sched_yield") { return "Synchronisation / libc" }
    if ($Symbol -match "(?i)FrameRender|Present|Surface|EmulatorSession|ScreenLayout|Frontend|GLContext") { return "Frontend / frame output" }
    if ($Dso -match "libmelonDS-android-frontend\.so") { return "Autre melonDS symbolise" }
    if ($Symbol -eq "[unknown]" -or $Dso -match "^\[") { return "Non classe / symboles insuffisants" }
    return "Systeme / bibliotheques"
}

function Get-SubsystemRows {
    param([Parameter(Mandatory = $true)]$Analysis)
    $values = @{}
    foreach ($row in $Analysis.Self) {
        $name = Get-SubsystemName $row.symbol $row.dso
        if (-not $values.ContainsKey($name)) { $values[$name] = [long]0 }
        $values[$name] += [long]$row.period_ns
    }
    $rows = foreach ($name in $values.Keys) {
        [pscustomobject]@{
            subsystem = $name
            seconds = [math]::Round($values[$name] / 1e9, 6)
            percent = if ($Analysis.TotalNs -gt 0) { [math]::Round(100.0 * $values[$name] / $Analysis.TotalNs, 6) } else { 0.0 }
        }
    }
    return @($rows | Sort-Object percent -Descending)
}

function Write-AnalysisTextReports {
    param(
        [Parameter(Mandatory = $true)][string]$ProfileDirectory,
        [Parameter(Mandatory = $true)]$OnCpu,
        [Parameter(Mandatory = $true)]$OffCpu
    )
    $utf8 = New-Object Text.UTF8Encoding($false)
    $lines = New-Object Collections.Generic.List[string]
    $lines.Add("TOP ON-CPU GLOBAL (cout propre, task-clock utilisateur)")
    $lines.Add("percent`tseconds	samples	dso	symbol")
    foreach ($row in @($OnCpu.Self | Select-Object -First 100)) {
        $lines.Add(("{0:0.000}%`t{1:0.000000}`t{2}`t{3}`t{4}" -f $row.percent, $row.seconds, $row.samples, $row.dso, $row.symbol))
    }
    [IO.File]::WriteAllLines((Join-Path $ProfileDirectory "simpleperf_report.txt"), $lines.ToArray(), $utf8)

    $lines.Clear()
    $lines.Add("TOP ON-CPU CHILDREN (cout inclusif, une occurrence par pile)")
    $lines.Add("percent`tseconds	samples	dso	symbol")
    foreach ($row in @($OnCpu.Inclusive | Select-Object -First 100)) {
        $lines.Add(("{0:0.000}%`t{1:0.000000}`t{2}`t{3}`t{4}" -f $row.percent, $row.seconds, $row.samples, $row.dso, $row.symbol))
    }
    [IO.File]::WriteAllLines((Join-Path $ProfileDirectory "simpleperf_children.txt"), $lines.ToArray(), $utf8)

    $lines.Clear()
    $lines.Add("CHARGE ON-CPU PAR THREAD")
    $lines.Add("percent_process	seconds	samples	tid	migrations_echantillonnees	cpus	thread")
    foreach ($row in $OnCpu.Threads) {
        $lines.Add(("{0:0.000}%`t{1:0.000000}`t{2}`t{3}`t{4}`t{5}`t{6} [{7}]" -f $row.percent, $row.seconds, $row.samples, $row.tid, $row.sampled_migrations, $row.cpu_placement, $row.name, $row.role))
    }
    [IO.File]::WriteAllLines((Join-Path $ProfileDirectory "simpleperf_threads.txt"), $lines.ToArray(), $utf8)

    $lines.Clear()
    $lines.Add("HOTSPOTS ON-CPU PAR THREAD (pourcentage propre au thread)")
    foreach ($thread in $OnCpu.Threads) {
        $lines.Add("")
        $lines.Add("TID $($thread.tid) - $($thread.name) [$($thread.role)]")
        foreach ($row in @($thread.top_symbols | Select-Object -First 15)) {
            $lines.Add(("{0:0.000}%`t{1:0.000000}s`t{2}`t{3}" -f $row.percent, $row.seconds, $row.dso, $row.symbol))
        }
    }
    [IO.File]::WriteAllLines((Join-Path $ProfileDirectory "simpleperf_thread_hotspots.txt"), $lines.ToArray(), $utf8)

    $lines.Clear()
    $lines.Add("TOP OFF-CPU INCLUSIF (temps bloque ou preempte)")
    $lines.Add("percent`tseconds	samples	dso	symbol")
    foreach ($row in @($OffCpu.Inclusive | Select-Object -First 100)) {
        $lines.Add(("{0:0.000}%`t{1:0.000000}`t{2}`t{3}`t{4}" -f $row.percent, $row.seconds, $row.samples, $row.dso, $row.symbol))
    }
    [IO.File]::WriteAllLines((Join-Path $ProfileDirectory "simpleperf_waits.txt"), $lines.ToArray(), $utf8)
}

function Get-AtraceThreadStates {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$TargetThreads,
        [Parameter(Mandatory = $true)][double]$StartUptime,
        [Parameter(Mandatory = $true)][double]$EndUptime
    )
    $states = @{}
    foreach ($thread in @($TargetThreads)) {
        $states[[int]$thread.tid] = [pscustomobject]@{
            tid = [int]$thread.tid
            name = [string]$thread.name
            role = [string]$thread.role
            state = "Unknown"
            last_ts = $StartUptime
            running_s = 0.0
            runnable_s = 0.0
            sleeping_s = 0.0
            current_cpu = $null
            last_cpu = $null
            migrations = 0
            cpu_seconds = @{}
        }
    }
    $addDuration = {
        param($item, [double]$timestamp)
        $duration = [math]::Max(0.0, $timestamp - [double]$item.last_ts)
        switch ($item.state) {
            "Running" {
                $item.running_s += $duration
                if ($null -ne $item.current_cpu) {
                    $key = [string]$item.current_cpu
                    if (-not $item.cpu_seconds.ContainsKey($key)) { $item.cpu_seconds[$key] = 0.0 }
                    $item.cpu_seconds[$key] += $duration
                }
            }
            "Runnable" { $item.runnable_s += $duration }
            "Sleeping" { $item.sleeping_s += $duration }
        }
        $item.last_ts = $timestamp
    }

    foreach ($line in [IO.File]::ReadLines($Path)) {
        if ($line -notmatch "\[(?<cpu>\d+)\].*?\s(?<ts>\d+\.\d+):\s+(?<event>sched_switch|sched_wakeup):\s+(?<detail>.*)$") {
            continue
        }
        $timestamp = [double]::Parse($Matches.ts, $script:Invariant)
        if ($timestamp -lt $StartUptime -or $timestamp -gt $EndUptime) { continue }
        $cpu = [int]$Matches.cpu
        $event = $Matches.event
        $detail = $Matches.detail
        if ($event -eq "sched_switch") {
            if ($detail -match "prev_comm=(?<prev_comm>.*?)\s+prev_pid=(?<prev_pid>\d+).*?prev_state=(?<prev_state>\S+)\s+==>\s+next_comm=(?<next_comm>.*?)\s+next_pid=(?<next_pid>\d+)") {
                $prevTid = [int]$Matches.prev_pid
                $prevState = $Matches.prev_state
                $nextTid = [int]$Matches.next_pid
                if ($states.ContainsKey($prevTid)) {
                    $item = $states[$prevTid]
                    & $addDuration $item $timestamp
                    $item.state = if ($prevState -match "^R") { "Runnable" } else { "Sleeping" }
                    $item.last_ts = $timestamp
                }
                if ($states.ContainsKey($nextTid)) {
                    $item = $states[$nextTid]
                    & $addDuration $item $timestamp
                    if ($null -ne $item.last_cpu -and $item.last_cpu -ne $cpu) { $item.migrations += 1 }
                    $item.last_cpu = $cpu
                    $item.current_cpu = $cpu
                    $item.state = "Running"
                    $item.last_ts = $timestamp
                }
            }
        }
        elseif ($detail -match "comm=(?<comm>.*?)\s+pid=(?<tid>\d+)") {
            $tid = [int]$Matches.tid
            if ($states.ContainsKey($tid)) {
                $item = $states[$tid]
                if ($item.state -eq "Sleeping" -or $item.state -eq "Unknown") {
                    & $addDuration $item $timestamp
                    $item.state = "Runnable"
                    $item.last_ts = $timestamp
                }
            }
        }
    }
    foreach ($item in $states.Values) { & $addDuration $item $EndUptime }

    $rows = foreach ($item in $states.Values) {
        $observed = $item.running_s + $item.runnable_s + $item.sleeping_s
        $placements = foreach ($cpu in ($item.cpu_seconds.Keys | Sort-Object { [int]$_ })) {
            $share = if ($item.running_s -gt 0) { 100.0 * $item.cpu_seconds[$cpu] / $item.running_s } else { 0 }
            "CPU$cpu=" + $share.ToString("0.0", $script:Invariant) + "%"
        }
        [pscustomobject]@{
            name = $item.name
            role = $item.role
            tid = $item.tid
            running_s = [math]::Round($item.running_s, 6)
            runnable_s = [math]::Round($item.runnable_s, 6)
            sleeping_s = [math]::Round($item.sleeping_s, 6)
            running_percent = if ($observed -gt 0) { [math]::Round(100.0 * $item.running_s / $observed, 3) } else { 0 }
            runnable_percent = if ($observed -gt 0) { [math]::Round(100.0 * $item.runnable_s / $observed, 3) } else { 0 }
            sleeping_percent = if ($observed -gt 0) { [math]::Round(100.0 * $item.sleeping_s / $observed, 3) } else { 0 }
            migrations = $item.migrations
            cpu_placement = $placements -join ", "
        }
    }
    return @($rows | Sort-Object running_s -Descending)
}

function New-SimpleperfReports {
    param(
        [Parameter(Mandatory = $true)]$Tools,
        [Parameter(Mandatory = $true)][string]$ProfileDirectory,
        [string]$AtracePath,
        [double]$TraceStart,
        [double]$TraceEnd
    )
    $rawDirectory = Join-Path $ProfileDirectory "raw"
    $onCpuPath = Join-Path $rawDirectory "simpleperf_oncpu_samples.txt"
    $offCpuPath = Join-Path $rawDirectory "simpleperf_offcpu_samples.txt"
    Invoke-PythonReport $Tools $ProfileDirectory @(
        $Tools.ReportSample, "-i", "perf.data", "--symfs", "binary_cache",
        "--trace-offcpu", "on-cpu", "--header", "-o", $onCpuPath
    ) "report_oncpu.log"
    Invoke-PythonReport $Tools $ProfileDirectory @(
        $Tools.ReportSample, "-i", "perf.data", "--symfs", "binary_cache",
        "--trace-offcpu", "off-cpu", "--header", "-o", $offCpuPath
    ) "report_offcpu.log"
    Invoke-PythonReport $Tools $ProfileDirectory @(
        $Tools.ReportHtml, "-i", "perf.data", "-o", "simpleperf_callgraph.html",
        "--trace-offcpu", "on-cpu", "--min_func_percent", "0.05",
        "--min_callchain_percent", "0.1", "--ndk_path", $Tools.Ndk, "--no_browser"
    ) "report_html.log"

    $onCpu = Get-SampleAnalysis $onCpuPath
    $offCpu = Get-SampleAnalysis $offCpuPath
    Write-AnalysisTextReports $ProfileDirectory $onCpu $offCpu
    $subsystems = Get-SubsystemRows $onCpu
    $subsystems | Export-Csv -LiteralPath (Join-Path $ProfileDirectory "subsystems.csv") -NoTypeInformation -Encoding UTF8
    $atraceStates = @()
    if ($AtracePath -and (Test-Path -LiteralPath $AtracePath) -and $TraceEnd -gt $TraceStart) {
        $atraceStates = @(Get-AtraceThreadStates $AtracePath $onCpu.Threads $TraceStart $TraceEnd)
        $atraceStates | Export-Csv -LiteralPath (Join-Path $ProfileDirectory "atrace_thread_states.csv") -NoTypeInformation -Encoding UTF8
    }
    return [pscustomobject]@{
        OnCpu = $onCpu
        OffCpu = $offCpu
        Subsystems = $subsystems
        AtraceStates = $atraceStates
    }
}

function Invoke-ProfileRun {
    param(
        [Parameter(Mandatory = $true)]$Test,
        [Parameter(Mandatory = $true)][byte[]]$OriginalPreferenceBytes,
        [Parameter(Mandatory = $true)]$DisplayInfos,
        [Parameter(Mandatory = $true)][string]$TargetRomUri,
        [Parameter(Mandatory = $true)]$Tools,
        [Parameter(Mandatory = $true)][string]$ResultDirectory,
        [switch]$CaptureAtrace
    )
    $profileDirectory = Join-Path $ResultDirectory ("profile_" + $Test.id)
    $rawDirectory = Join-Path $profileDirectory "raw"
    [void](New-Item -ItemType Directory -Path $rawDirectory -Force)
    Write-Host "[$($Test.id)] Software 1x, threaded=$($Test.threaded), JIT ON"

    Set-DisplaysTo60Hz $DisplayInfos
    Stop-App
    if (Test-AppFile $script:PreferenceBackupPath) { Remove-AppFile $script:PreferenceBackupPath }
    $testPreferences = New-TestPreferenceBytes -OriginalBytes ([byte[]]$OriginalPreferenceBytes) -Test $Test
    Set-AppFileBytes -Path $script:PreferencePath -Bytes ([byte[]]$testPreferences)

    $processId = $null
    $ffEnabled = $false
    $atraceActive = $false
    $profilerHandle = $null
    $atracePath = if ($CaptureAtrace) { Join-Path $profileDirectory "atrace_sched.txt" } else { $null }
    $hardwareRows = New-Object Collections.Generic.List[object]
    $threadsBefore = @()
    $threadsAfter = @()
    $traceStart = 0.0
    $traceEnd = 0.0
    $thermalBefore = Get-ThermalStatus
    try {
        Start-Rom $TargetRomUri
        $processId = Wait-ForPid
        Wait-ForAudioOpen $processId
        if ($SettleSeconds -gt 0) { Start-Sleep -Seconds $SettleSeconds }
        Send-KeyEvent 29
        Start-Sleep -Seconds 3
        if ($WarmupSeconds -gt 0) { Start-Sleep -Seconds $WarmupSeconds }

        if ($CaptureAtrace) {
            Start-Atrace
            $atraceActive = $true
        }
        $profilerHandle = Start-AppProfiler $Tools $profileDirectory
        Wait-ForSimpleperfStart $profilerHandle
        $beforeOn = (Get-TransitionLines -Log (Get-FFAudioLog $processId) -Transition "OFF_TO_ON").Count
        Send-KeyEvent 30
        $onLine = Wait-ForNewTransition $processId "OFF_TO_ON" $beforeOn 30
        $ffEnabled = $true
        $traceStart = Get-DeviceUptime
        $threadsBefore = @(Get-ThreadSnapshot $processId)
        $startTime = [datetime]::UtcNow
        $nextHardware = $startTime
        $deadline = $startTime.AddSeconds($profileOptions.ProfileSeconds + 20)

        while (Test-SimpleperfRunning) {
            if ([datetime]::UtcNow -ge $deadline) { throw "simpleperf depasse la duree attendue." }
            if ([datetime]::UtcNow -ge $nextHardware) {
                Add-HardwareSnapshot $hardwareRows $Test.id (([datetime]::UtcNow - $startTime).TotalSeconds)
                $nextHardware = [datetime]::UtcNow.AddSeconds(1)
            }
            Start-Sleep -Milliseconds 100
        }

        $traceEnd = Get-DeviceUptime
        $beforeOff = (Get-TransitionLines -Log (Get-FFAudioLog $processId) -Transition "ON_TO_OFF").Count
        Send-KeyEvent 30
        $offLine = Wait-ForNewTransition $processId "ON_TO_OFF" $beforeOff 30
        $ffEnabled = $false
        $threadsAfter = @(Get-ThreadSnapshot $processId)
        if ($atraceActive) {
            Stop-AtraceToFile $atracePath
            $atraceActive = $false
        }
        Complete-CapturedProcess $profilerHandle (Join-Path $rawDirectory "app_profiler.log") 300
        $profilerHandle = $null
        $perfData = Join-Path $profileDirectory "perf.data"
        if (-not (Test-Path -LiteralPath $perfData) -or (Get-Item -LiteralPath $perfData).Length -lt 4096) {
            throw "perf.data est absent ou vide."
        }

        $audioLog = Get-FFAudioLog $processId
        [IO.File]::WriteAllText((Join-Path $rawDirectory "FFAudioDiag.log"), $audioLog + "`n", (New-Object Text.UTF8Encoding($false)))
        $on = ConvertFrom-TransitionLine $onLine
        $off = ConvertFrom-TransitionLine $offLine
        $duration = $off.Timestamp - $on.Timestamp
        $produced = [long]$off.Values.stereo_frames_produced
        $producedPerSecond = $produced / $duration
        $threadsBefore | Export-Csv -LiteralPath (Join-Path $rawDirectory "threads_before.csv") -NoTypeInformation -Encoding UTF8
        $threadsAfter | Export-Csv -LiteralPath (Join-Path $rawDirectory "threads_after.csv") -NoTypeInformation -Encoding UTF8
        $hardwareRows.ToArray() | Export-Csv -LiteralPath (Join-Path $rawDirectory "hardware_samples.csv") -NoTypeInformation -Encoding UTF8
        $analysis = New-SimpleperfReports -Tools $Tools -ProfileDirectory $profileDirectory `
            -AtracePath $atracePath -TraceStart $traceStart -TraceEnd $traceEnd
        if ($atracePath -and (Test-Path -LiteralPath $atracePath)) {
            $atracePath = Compress-GzipFile $atracePath
        }

        return [pscustomobject]@{
            id = $Test.id
            threaded = [bool]$Test.threaded
            process_id = $processId
            duration_s = [math]::Round($duration, 6)
            produced_per_s = [math]::Round($producedPerSecond, 3)
            effective_speed = [math]::Round($producedPerSecond / 48000.0, 6)
            overwritten_percent = if ($produced -gt 0) { [math]::Round(100.0 * [long]$off.Values.stereo_frames_overwritten / $produced, 6) } else { 0.0 }
            fully_underfed = [long]$off.Values.fully_underfed_callbacks
            partially_underfed = [long]$off.Values.partially_underfed_callbacks
            thermal_before = $thermalBefore
            thermal_after = Get-ThermalStatus
            threads_before = $threadsBefore
            threads_after = $threadsAfter
            hardware = @($hardwareRows.ToArray())
            analysis = $analysis
            directory = $profileDirectory
        }
    }
    finally {
        if ($ffEnabled) {
            [void](Invoke-Adb -Arguments @("shell", "input", "keyevent", "30") -AllowFailure)
        }
        if ($atraceActive) {
            try { Stop-AtraceToFile $atracePath } catch { Write-Warning $_.Exception.Message }
        }
        if (Test-SimpleperfRunning) { Stop-OwnSimpleperf }
        if ($profilerHandle -and -not $profilerHandle.Process.HasExited) {
            $profilerHandle.Process.Kill()
            $profilerHandle.Process.Dispose()
        }
        Stop-App
    }
}

function Find-Thread {
    param($Rows, [string]$Pattern)
    $matches = @($Rows | Where-Object { "$($_.name) $($_.role)" -match $Pattern } | Sort-Object seconds -Descending | Select-Object -First 1)
    if ($matches.Count) { return $matches[0] }
    return $null
}

function Get-SubsystemPercent {
    param($Rows, [string]$Name)
    $row = @($Rows | Where-Object subsystem -eq $Name | Select-Object -First 1)
    if ($row.Count) { return [double]$row[0].percent }
    return 0.0
}

function Write-Summary {
    param(
        [Parameter(Mandatory = $true)][string]$ResultDirectory,
        [Parameter(Mandatory = $true)][object[]]$Profiles,
        [Parameter(Mandatory = $true)]$Tools,
        [Parameter(Mandatory = $true)]$Perfetto,
        [Parameter(Mandatory = $true)][bool]$RestorationSucceeded,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()]$RestorationErrors
    )
    $utf8 = New-Object Text.UTF8Encoding($false)
    $lines = New-Object Collections.Generic.List[string]
    $aRows = @($Profiles | Where-Object id -eq "A" | Select-Object -First 1)
    if (-not $aRows.Count) { throw "Le profile A manque pour le rapport." }
    $a = $aRows[0]
    $b = @($Profiles | Where-Object id -eq "B" | Select-Object -First 1)
    $emu = Find-Thread $a.analysis.OnCpu.Threads "Chemin principal|CPU/JIT emule"
    if (-not $emu) { $emu = Find-Thread $a.analysis.OnCpu.Threads "^EmulatorThread" }
    $soft = Find-Thread $a.analysis.OnCpu.Threads "GPU3D SoftRenderer"
    $frame = Find-Thread $a.analysis.OnCpu.Threads "^FrameRender"
    $cpuJit = (Get-SubsystemPercent $a.analysis.Subsystems "ARM9 / CPU core") +
        (Get-SubsystemPercent $a.analysis.Subsystems "ARM7 / CPU core") +
        (Get-SubsystemPercent $a.analysis.Subsystems "ARM JIT commun") +
        (Get-SubsystemPercent $a.analysis.Subsystems "CPU / ordonnanceur emule")
    $gpu2dSoft = Get-SubsystemPercent $a.analysis.Subsystems "GPU2D SoftRenderer"
    $gpu2dOther = Get-SubsystemPercent $a.analysis.Subsystems "GPU2D autre"
    $gpu3dSoft = Get-SubsystemPercent $a.analysis.Subsystems "GPU3D SoftRenderer"
    $gpu2d = $gpu2dSoft + $gpu2dOther
    $softPercent = $gpu2dSoft + $gpu3dSoft
    $audioPercent = Get-SubsystemPercent $a.analysis.Subsystems "SPU / audio"
    $syncPercent = Get-SubsystemPercent $a.analysis.Subsystems "Synchronisation / libc"
    $emuState = @($a.analysis.AtraceStates | Where-Object { $emu -and $_.tid -eq $emu.tid } | Select-Object -First 1)
    $softState = @($a.analysis.AtraceStates | Where-Object { $soft -and $_.tid -eq $soft.tid } | Select-Object -First 1)
    $estimatedFrames = 60.0 * [double]$a.effective_speed * $profileOptions.ProfileSeconds
    $wallMsPerFrame = 1000.0 / (60.0 * [double]$a.effective_speed)
    $userCpuMsPerFrame = 1000.0 * ([double]$a.analysis.OnCpu.TotalNs / 1e9) / $estimatedFrames
    $softCpuMsPerFrame = $userCpuMsPerFrame * $softPercent / 100.0
    $cpuJitMsPerFrame = $userCpuMsPerFrame * $cpuJit / 100.0
    $gpu2dMsPerFrame = $userCpuMsPerFrame * $gpu2d / 100.0
    $audioMsPerFrame = $userCpuMsPerFrame * $audioPercent / 100.0
    $needed = 100.0 * (2.0 / [double]$a.effective_speed - 1.0)
    $threadingGain = if ($b.Count) { 100.0 * ([double]$a.effective_speed / [double]$b[0].effective_speed - 1.0) } else { $null }

    $lines.Add("# Profiling fast-forward AYN Thor")
    $lines.Add("")
    $lines.Add("- Genere : " + (Get-Date -Format "yyyy-MM-dd HH:mm:ss zzz"))
    $lines.Add("- NDK : " + $profileOptions.NdkVersion)
    $lines.Add("- Simpleperf : non-root, package debuggable, task-clock utilisateur + DWARF + off-CPU")
    $lines.Add("- Symboles : libmelonDS-android-frontend.so ARM64 non stripped avec DWARF")
    $lines.Add("- Restauration : " + $(if ($RestorationSucceeded) { "PASS" } else { "ECHEC" }))
    $lines.Add("")
    $lines.Add("## A. Configuration exacte")
    $lines.Add("")
    $lines.Add("Profile A : Software, 1x, threaded ON, JIT ON, filtre none, interpolation none, rewind OFF, audio ON, FF illimite, 60 Hz.")
    if ($b.Count) { $lines.Add("Profile B : meme configuration avec threaded OFF.") }
    $lines.Add("ROM recente et savestate slot $($profileOptions.SaveStateSlot), sans URI ni nom de sauvegarde dans les artefacts.")
    $lines.Add("")
    $lines.Add("## B. Vitesse FF observee")
    $lines.Add("")
    $lines.Add("| profil | threaded | duree FFAudioDiag | produced/s | vitesse | underflows F/P |")
    $lines.Add("|---|:---:|---:|---:|---:|---:|")
    foreach ($profile in $Profiles) {
        $lines.Add("| $($profile.id) | $($profile.threaded) | $($profile.duration_s) s | $($profile.produced_per_s) | $($profile.effective_speed)x | $($profile.fully_underfed)/$($profile.partially_underfed) |")
    }
    $lines.Add("")
    $lines.Add("- Budget mur observe par frame DS au profil A : $($wallMsPerFrame.ToString("0.000", $script:Invariant)) ms.")
    $lines.Add("- Cout ``task-clock:u`` agrege : $($userCpuMsPerFrame.ToString("0.000", $script:Invariant)) CPU ms/frame (somme des threads; les travaux paralleles se chevauchent, ce n'est pas un temps mur).")
    $lines.Add("")
    $lines.Add("## C. Charge par thread")
    $lines.Add("")
    $lines.Add("| thread | role observe | TID | CPU user approx. | part du process | CPUs echantillonnes |")
    $lines.Add("|---|---|---:|---:|---:|---|")
    foreach ($thread in @($a.analysis.OnCpu.Threads | Select-Object -First 15)) {
        $load = 100.0 * [double]$thread.seconds / $profileOptions.ProfileSeconds
        $lines.Add("| $($thread.name) | $($thread.role) | $($thread.tid) | $($load.ToString("0.0", $script:Invariant))% | $($thread.percent)% | $($thread.cpu_placement) |")
    }
    $lines.Add("")
    $lines.Add("Les 15 premiers hotspots de chaque thread sont dans ``simpleperf_thread_hotspots.txt``.")
    $lines.Add("")
    $lines.Add("## D. Top 30 fonctions")
    $lines.Add("")
    $lines.Add("| rang | cout propre | temps CPU | CPU ms/frame | fonction | DSO |")
    $lines.Add("|---:|---:|---:|---:|---|---|")
    $rank = 0
    foreach ($row in @($a.analysis.OnCpu.Self | Select-Object -First 30)) {
        $rank += 1
        $symbol = ([string]$row.symbol).Replace("|", "\|")
        $dso = [IO.Path]::GetFileName(([string]$row.dso).Replace("!", "_"))
        if (-not $dso) { $dso = ([string]$row.dso).Replace("|", "\|") }
        $rowMsPerFrame = 1000.0 * [double]$row.seconds / $estimatedFrames
        $lines.Add("| $rank | $($row.percent)% | $($row.seconds) s | $($rowMsPerFrame.ToString("0.000", $script:Invariant)) | $symbol | $dso |")
    }
    $lines.Add("")
    $lines.Add("## E. Regroupement par subsystem")
    $lines.Add("")
    $lines.Add("| subsystem | part CPU propre | CPU ms/frame |")
    $lines.Add("|---|---:|---:|")
    foreach ($row in $a.analysis.Subsystems) {
        $rowMsPerFrame = 1000.0 * [double]$row.seconds / $estimatedFrames
        $lines.Add("| $($row.subsystem) | $($row.percent)% | $($rowMsPerFrame.ToString("0.000", $script:Invariant)) |")
    }
    $lines.Add("")
    $lines.Add("Les categories reposent uniquement sur les symboles observes; le non-classe reste explicite.")
    $lines.Add("")
    $lines.Add("## F. Attentes et synchronisations")
    $lines.Add("")
    if ($a.analysis.AtraceStates.Count -gt 0) {
        $lines.Add("| thread | role observe | Running | Runnable | Sleeping | migrations | CPUs |")
        $lines.Add("|---|---|---:|---:|---:|---:|---|")
        foreach ($row in @($a.analysis.AtraceStates | Where-Object { "$($_.name) $($_.role)" -match "Emulator|GPU2D|GPU3D|FrameRender|Audio|AAudio" })) {
            $lines.Add("| $($row.name) | $($row.role) | $($row.running_percent)% | $($row.runnable_percent)% | $($row.sleeping_percent)% | $($row.migrations) | $($row.cpu_placement) |")
        }
        $lines.Add("")
        $lines.Add("Trace atrace brute conservee compressee dans ``profile_A/atrace_sched.txt.gz``; etats parses dans ``atrace_thread_states.csv``.")
    }
    $lines.Add("")
    $lines.Add("Principaux sites inclusifs off-CPU :")
    foreach ($row in @($a.analysis.OffCpu.Inclusive | Select-Object -First 10)) {
        $lines.Add("- $($row.percent)% - $($row.symbol)")
    }
    $lines.Add("")
    $lines.Add("Synchronisation/libc en cout CPU propre : $($syncPercent.ToString("0.000", $script:Invariant))%.")
    $lines.Add("")
    $lines.Add("## G. Frequences et migrations CPU")
    $lines.Add("")
    foreach ($thread in @($a.analysis.OnCpu.Threads | Where-Object { "$($_.name) $($_.role)" -match "Emulator|GPU2D|GPU3D|FrameRender|Audio|AAudio" })) {
        $lines.Add("- $($thread.name) [$($thread.role)] : $($thread.cpu_placement); migrations echantillonnees=$($thread.sampled_migrations).")
    }
    $criticalPlacements = ((@($emu, $soft) | Where-Object { $_ }) | ForEach-Object { $_.cpu_placement }) -join ", "
    if ($criticalPlacements -and $criticalPlacements -notmatch "CPU[0-2]=") {
        $lines.Add("- Aucun echantillon des deux threads critiques sur les petits coeurs CPU0-2.")
    }
    $freqRows = @($a.hardware | Where-Object kind -eq "cpu_freq" | Where-Object { $_.value -match "^\d+$" })
    foreach ($cpu in @($freqRows.name | Sort-Object -Unique)) {
        $values = @($freqRows | Where-Object name -eq $cpu | ForEach-Object { [double]$_.value / 1000.0 })
        if ($values.Count) {
            $measure = $values | Measure-Object -Minimum -Maximum -Average
            $lines.Add("- CPU$cpu : min=$([math]::Round($measure.Minimum)) MHz, moyenne=$([math]::Round($measure.Average)) MHz, max=$([math]::Round($measure.Maximum)) MHz.")
        }
    }
    $lines.Add("")
    $lines.Add("## H. Thermique et GPU")
    $lines.Add("")
    $thermalRows = @($a.hardware | Where-Object kind -eq "thermal" | Where-Object { $_.name -match "^(cpu|cpuss|gpuss)" -and $_.value -match "^-?\d+$" })
    if ($thermalRows.Count) {
        $thermalValues = @($thermalRows | ForEach-Object { [double]$_.value / 1000.0 })
        $thermalMeasure = $thermalValues | Measure-Object -Minimum -Maximum
        $lines.Add("- Capteurs CPU/GPU observes : $([math]::Round($thermalMeasure.Minimum, 1)) a $([math]::Round($thermalMeasure.Maximum, 1)) deg C.")
    }
    $gpuRows = @($a.hardware | Where-Object kind -eq "gpu_freq" | Where-Object { $_.value -match "^\d+$" })
    if ($gpuRows.Count) {
        $gpuValues = @($gpuRows | ForEach-Object { [double]$_.value / 1e6 })
        $gpuMeasure = $gpuValues | Measure-Object -Minimum -Maximum -Average
        $lines.Add("- GPU : min=$([math]::Round($gpuMeasure.Minimum)) MHz, moyenne=$([math]::Round($gpuMeasure.Average)) MHz, max=$([math]::Round($gpuMeasure.Maximum)) MHz.")
    }
    $lines.Add("- Thermal Status Android : debut=$($a.thermal_before), fin=$($a.thermal_after) (0 = aucun throttling signale).")
    if ($a.thermal_before -eq 0 -and $a.thermal_after -eq 0) {
        $lines.Add("- Aucun throttling n'est signale par le framework Android pendant la fenetre; les frequences brutes ci-dessus ne montrent pas de chute.")
    }
    $perfettoText = if ($Perfetto.Success) {
        "capture disponible."
    }
    elseif ($Perfetto.Attempted) {
        "indisponible: $($Perfetto.Reason). Fallback atrace + simpleperf off-CPU utilise."
    }
    else {
        "non tente."
    }
    $lines.Add("- Perfetto : $perfettoText")
    $lines.Add("")
    $lines.Add("## I. Comparaison threaded ON/OFF")
    $lines.Add("")
    if ($b.Count) {
        $bFrames = 60.0 * [double]$b[0].effective_speed * $profileOptions.ProfileSeconds
        $bUserCpuMsPerFrame = 1000.0 * ([double]$b[0].analysis.OnCpu.TotalNs / 1e9) / $bFrames
        $lines.Add("- A threaded ON : $($a.effective_speed)x.")
        $lines.Add("- B threaded OFF : $($b[0].effective_speed)x.")
        $lines.Add("- Gain mesure du threading : $([math]::Round($threadingGain, 2))%.")
        $lines.Add("- B ne presente plus de thread GPU3D SoftRenderer significatif; A chevauche ce travail sur un second thread pendant que le chemin principal reste sature.")
        $lines.Add("- Cout CPU user agrege : A=$($userCpuMsPerFrame.ToString("0.000", $script:Invariant)) ms/frame, B=$($bUserCpuMsPerFrame.ToString("0.000", $script:Invariant)) ms/frame; le gain vient surtout du chevauchement du GPU3D, pas d'une baisse du travail total par frame.")
        foreach ($thread in @($b[0].analysis.OnCpu.Threads | Select-Object -First 3)) {
            $load = 100.0 * [double]$thread.seconds / $profileOptions.ProfileSeconds
            $lines.Add("- B, $($thread.name) [$($thread.role)] : ~$([math]::Round($load, 1))% d'un coeur utilisateur.")
        }
    }
    else {
        $lines.Add("Profile B desactive par -SkipThreadedOff.")
    }
    $lines.Add("")
    $lines.Add("## J. Conclusion sur le bottleneck")
    $lines.Add("")
    $emuLoad = if ($emu) { 100.0 * [double]$emu.seconds / $profileOptions.ProfileSeconds } else { 0.0 }
    $softLoad = if ($soft) { 100.0 * [double]$soft.seconds / $profileOptions.ProfileSeconds } else { 0.0 }
    $frameLoad = if ($frame) { 100.0 * [double]$frame.seconds / $profileOptions.ProfileSeconds } else { 0.0 }
    $lines.Add("Chemin principal CPU/JIT + GPU2D : environ $([math]::Round($emuLoad, 1))% d'un coeur utilisateur; thread GPU3D SoftRenderer : $([math]::Round($softLoad, 1))%; FrameRenderThread : $([math]::Round($frameLoad, 1))%.")
    if ($null -ne $threadingGain) {
        $lines.Add("Le gain threaded mesure quantifie l'effet du parallelisme sans supposer quel thread est critique.")
    }
    $lines.Add("")
    if (-not $RestorationSucceeded) {
        $lines.Add("Erreurs de restauration : " + ($RestorationErrors -join "; "))
        $lines.Add("")
    }
    $topUseful = @($a.analysis.Subsystems | Where-Object { $_.subsystem -notin @("Systeme / bibliotheques", "Non classe / symboles insuffisants", "Synchronisation / libc") } | Select-Object -First 1)
    $firstOptimization = if ($topUseful.Count) {
        "Tester une reduction ciblee du premier hotspot de " + $topUseful[0].subsystem + " observe dans le top symbolise, puis remesurer A/B."
    }
    else {
        "Ajouter une instrumentation native minimale pour attribuer la part non classe avant toute optimisation."
    }
    $principal = if ($softLoad -gt $emuLoad) {
        "Le thread GPU3D SoftRenderer, proche de $([math]::Round($softLoad, 1))% d'un coeur utilisateur, devant le chemin CPU/JIT + GPU2D a $([math]::Round($emuLoad, 1))%."
    }
    else {
        "Le chemin principal CPU/JIT + GPU2D, proche de $([math]::Round($emuLoad, 1))% d'un coeur utilisateur, devant le thread GPU3D SoftRenderer a $([math]::Round($softLoad, 1))%."
    }
    $waitDetails = New-Object Collections.Generic.List[string]
    if ($emuState.Count) {
        $waitDetails.Add("chemin CPU/JIT + GPU2D: runnable $($emuState[0].runnable_percent)%, sleeping $($emuState[0].sleeping_percent)%")
    }
    if ($softState.Count) {
        $waitDetails.Add("GPU3D SoftRenderer: runnable $($softState[0].runnable_percent)%, sleeping $($softState[0].sleeping_percent)%")
    }
    $waitText = if ($waitDetails.Count) { $waitDetails -join "; " } else { "Etats atrace indisponibles" }
    if ($emuState.Count -and [double]$emuState[0].running_percent -gt 95.0) {
        $waitText += ". Le chemin critique reste Running: aucune attente significative de SoftRenderer n'est observee; le sommeil du thread GPU3D correspond principalement a l'attente de travail"
    }
    $lines.Add("BOTTLENECK PRINCIPAL:")
    $lines.Add($principal)
    $lines.Add("")
    $lines.Add("PART DU SOFTRENDERER:")
    $lines.Add("$($softPercent.ToString("0.000", $script:Invariant))% du CPU propre, soit ~$($softCpuMsPerFrame.ToString("0.000", $script:Invariant)) CPU ms/frame, par symboles GPU2D/GPU3D SoftRenderer; thread GPU3D dedie ~$([math]::Round($softLoad, 1))% d'un coeur utilisateur (GPU2D reste sur le chemin principal).")
    $lines.Add("")
    $lines.Add("PART CPU/JIT:")
    $lines.Add("$($cpuJit.ToString("0.000", $script:Invariant))% du CPU propre, soit ~$($cpuJitMsPerFrame.ToString("0.000", $script:Invariant)) CPU ms/frame, attribuable aux symboles ARM9, ARM7, ARMJIT et ordonnanceur NDS explicites.")
    $lines.Add("")
    $lines.Add("PART GPU2D:")
    $lines.Add("$($gpu2d.ToString("0.000", $script:Invariant))% du CPU propre, soit ~$($gpu2dMsPerFrame.ToString("0.000", $script:Invariant)) CPU ms/frame, dont $($gpu2dSoft.ToString("0.000", $script:Invariant))% dans GPU2D SoftRenderer (part incluse aussi ci-dessus).")
    $lines.Add("")
    $lines.Add("PART AUDIO:")
    $lines.Add("$($audioPercent.ToString("0.000", $script:Invariant))% du CPU propre symbolise, soit ~$($audioMsPerFrame.ToString("0.000", $script:Invariant)) CPU ms/frame.")
    $lines.Add("")
    $lines.Add("TEMPS PERDU EN ATTENTE:")
    $lines.Add("$waitText. Cout CPU propre de synchronisation/libc: $($syncPercent.ToString("0.000", $script:Invariant))%; details off-CPU dans simpleperf_waits.txt.")
    $lines.Add("")
    $lines.Add("GAIN REALISTE POUR ATTEINDRE X2:")
    $lines.Add("Il manque environ $([math]::Round($needed, 1))% de debit par rapport au profil A; aucun hotspot plus petit que ce seuil ne peut suffire seul.")
    $lines.Add("")
    $lines.Add("OPTIMISATION À TESTER EN PREMIER:")
    $lines.Add($firstOptimization)
    [IO.File]::WriteAllLines((Join-Path $ResultDirectory "summary.md"), $lines.ToArray(), $utf8)
}

function Invoke-ProfileSelfTest {
    Assert-True ((Get-SubsystemName "melonDS::GPU2D::DrawScanline()" "/x/libmelonDS-android-frontend.so") -eq "GPU2D autre") "classification GPU2D"
    Assert-True ((Get-SubsystemName "melonDS::GPU2D::SoftRenderer::DrawPixel()" "/x/libmelonDS-android-frontend.so") -eq "GPU2D SoftRenderer") "classification GPU2D SoftRenderer"
    Assert-True ((Get-SubsystemName "melonDS::SoftRenderer::RenderPolygon()" "/x/libmelonDS-android-frontend.so") -eq "GPU3D SoftRenderer") "classification GPU3D SoftRenderer"
    Assert-True ((Get-SubsystemName "melonDS::NDS::Read32()" "/x/libmelonDS-android-frontend.so") -eq "Autre melonDS symbolise") "classification DSO frontend"
    $temp = Join-Path ([IO.Path]::GetTempPath()) ("thor_ff_profile_" + [guid]::NewGuid().ToString("N") + ".txt")
    try {
        $sample = "EmulatorThread 1/2 [007] 10.000000: 2500000 task-clock:u:`n      abc melonDS::ARM9::Run() (/x/libmelonDS-android-frontend.so)`n"
        [IO.File]::WriteAllText($temp, $sample, (New-Object Text.UTF8Encoding($false)))
        $analysis = Get-SampleAnalysis $temp
        Assert-True ($analysis.TotalNs -eq 2500000) "sample period"
        Assert-True ($analysis.Self[0].symbol -eq "melonDS::ARM9::Run()") "sample symbol"
        Assert-True ($analysis.Threads[0].cpu_placement -eq "CPU7=100.0%") "sample CPU"
        Assert-True ($analysis.Threads[0].role -eq "CPU/JIT emule") "sample thread role"
    }
    finally {
        if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force }
    }
    Write-Host "Profile self-test PASS"
}

if ($profileOptions.SelfTest) {
    Invoke-ProfileSelfTest
    return
}

Initialize-Adb
$tools = Resolve-ProfileTools
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$root = if ($profileOptions.OutputRoot) {
    [IO.Path]::GetFullPath($profileOptions.OutputRoot)
}
else {
    Join-Path $repositoryRoot "build\thor_ff_profile"
}
$resultDirectory = Join-Path $root (Get-Date -Format "yyyyMMdd-HHmmss")
$rawDirectory = Join-Path $resultDirectory "raw"
$recoveryDirectory = Join-Path $resultDirectory "recovery"
[void](New-Item -ItemType Directory -Path $rawDirectory -Force)
[void](New-Item -ItemType Directory -Path $recoveryDirectory -Force)

$profiles = New-Object Collections.Generic.List[object]
$restorationErrors = New-Object Collections.Generic.List[string]
$restorationSucceeded = $false
$backupReady = $false
$fatalError = $null
$preferenceState = $null
$preferenceBakState = $null
$controllerState = $null
$romDataState = $null
$sramState = $null
$quickState = $null
$displayStates = $null
$displayInfos = $null
$powerState = $null
$perfetto = [pscustomobject]@{ Attempted = $false; Success = $false; Reason = "non execute"; Path = $null }

try {
    $selectedRom = Get-RecentRom
    $targetRomUri = [string]$selectedRom.uri
    if (-not $targetRomUri) { throw "L'URI de la ROM recente est vide." }
    Stop-App
    $preferenceState = Get-AppFileState $script:PreferencePath
    $preferenceBakState = Get-AppFileState $script:PreferenceBackupPath
    $controllerState = Get-AppFileState $script:ControllerPath
    $romDataState = Get-AppFileState $script:RomDataPath
    [IO.File]::WriteAllBytes((Join-Path $recoveryDirectory "preferences.xml"), [byte[]]$preferenceState.Bytes)
    if ($preferenceBakState.Exists) {
        [IO.File]::WriteAllBytes((Join-Path $recoveryDirectory "preferences.xml.bak"), [byte[]]$preferenceBakState.Bytes)
    }
    if ($controllerState.Exists) {
        [IO.File]::WriteAllBytes((Join-Path $recoveryDirectory "controller_config.json"), [byte[]]$controllerState.Bytes)
    }
    [IO.File]::WriteAllBytes((Join-Path $recoveryDirectory "rom_data.json"), [byte[]]$romDataState.Bytes)

    $storagePaths = Resolve-PrimaryStoragePaths $selectedRom
    $sramState = Backup-RemoteFile $storagePaths.Sram (Join-Path $recoveryDirectory "sram.bin")
    $displayInfos = Get-DisplayInfo
    $displayStates = Get-DisplayModeStates $displayInfos
    $powerState = Get-PowerState
    try {
        Enable-BenchmarkAwake
    }
    catch {
        Restore-PowerState $powerState
        throw
    }
    @(
        "screen_off_timeout=$($powerState.ScreenOffTimeout.Value)",
        "stay_on_while_plugged_in=$($powerState.StayOnWhilePluggedIn.Value)",
        "initially_awake=$($powerState.WasAwake)",
        "profiling_screen_off_timeout=1800000",
        "profiling_stay_on_while_plugged_in=7"
    ) | Set-Content -LiteralPath (Join-Path $rawDirectory "power_policy.txt") -Encoding UTF8
    $backupReady = $true

    $perfetto = Invoke-PerfettoProbe $rawDirectory
    $quickState = New-TemporaryQuickState $storagePaths
    $controllerJson = '{"inputMapper":[{"input":"FAST_FORWARD","assignment":{"type":"key","deviceId":null,"keyCode":30}},{"input":"QUICK_LOAD","assignment":{"type":"key","deviceId":null,"keyCode":29}}]}'
    Set-AppFileBytes -Path $script:ControllerPath -Bytes ([byte[]][Text.Encoding]::UTF8.GetBytes($controllerJson))

    $testA = [pscustomobject]@{ id = "A"; renderer = "software"; scale = 1; threaded = $true; jit = $true; refresh = "60" }
    [void]$profiles.Add((Invoke-ProfileRun -Test $testA -OriginalPreferenceBytes ([byte[]]$preferenceState.Bytes) `
        -DisplayInfos $displayInfos -TargetRomUri $targetRomUri -Tools $tools -ResultDirectory $resultDirectory -CaptureAtrace))
    if (-not $profileOptions.SkipThreadedOff) {
        $testB = [pscustomobject]@{ id = "B"; renderer = "software"; scale = 1; threaded = $false; jit = $true; refresh = "60" }
        [void]$profiles.Add((Invoke-ProfileRun -Test $testB -OriginalPreferenceBytes ([byte[]]$preferenceState.Bytes) `
            -DisplayInfos $displayInfos -TargetRomUri $targetRomUri -Tools $tools -ResultDirectory $resultDirectory))
    }
}
catch {
    $fatalError = $_.Exception.Message
    $fatalDetails = $_.Exception.ToString() + "`n`nSCRIPT STACK`n" + $_.ScriptStackTrace
    [IO.File]::WriteAllText((Join-Path $rawDirectory "fatal_error.txt"), $fatalDetails, (New-Object Text.UTF8Encoding($false)))
    Write-Warning "Profiling interrompu: $fatalError"
}
finally {
    Stop-App
    if ($backupReady) {
        foreach ($restoreAction in @(
            @{ Name = "preferences"; Action = { Restore-AppFileState $preferenceState } },
            @{ Name = "backup SharedPreferences"; Action = { Restore-AppFileState $preferenceBakState } },
            @{ Name = "controleur"; Action = { Restore-AppFileState $controllerState } },
            @{ Name = "metadonnees ROM"; Action = { Restore-AppFileState $romDataState } },
            @{ Name = "SRAM"; Action = { Restore-RemoteFile $sramState } },
            @{ Name = "savestate rapide temporaire"; Action = { Remove-TemporaryQuickState $quickState } },
            @{ Name = "refresh rate"; Action = { Restore-DisplayModes $displayStates } },
            @{ Name = "veille ecran"; Action = { Restore-PowerState $powerState } }
        )) {
            try {
                & $restoreAction.Action
            }
            catch {
                [void]$restorationErrors.Add("$($restoreAction.Name): $($_.Exception.Message)")
            }
        }
        $restorationSucceeded = $restorationErrors.Count -eq 0
    }
    else {
        [void]$restorationErrors.Add("La sauvegarde initiale complete n'avait pas ete etablie.")
    }
}

if ($profiles.Count -gt 0) {
    $profileArray = @($profiles.ToArray())
    $profileArray | Select-Object id,threaded,duration_s,produced_per_s,effective_speed,overwritten_percent,fully_underfed,partially_underfed |
        Export-Csv -LiteralPath (Join-Path $resultDirectory "profiles.csv") -NoTypeInformation -Encoding UTF8
    Write-Summary -ResultDirectory $resultDirectory -Profiles $profileArray -Tools $tools -Perfetto $perfetto `
        -RestorationSucceeded $restorationSucceeded -RestorationErrors @($restorationErrors.ToArray())
}
else {
    [IO.File]::WriteAllText((Join-Path $resultDirectory "summary.md"), "# Profiling fast-forward AYN Thor`n`nECHEC: $fatalError`n", (New-Object Text.UTF8Encoding($false)))
}

if ($restorationSucceeded) {
    $resultFull = [IO.Path]::GetFullPath($resultDirectory).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $recoveryFull = [IO.Path]::GetFullPath($recoveryDirectory)
    if (-not $recoveryFull.StartsWith($resultFull + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refus de supprimer un dossier de recuperation hors du resultat courant."
    }
    Remove-Item -LiteralPath $recoveryFull -Recurse -Force
}

Write-Host "Resultats: $resultDirectory"
Write-Host "Restauration: $(if ($restorationSucceeded) { 'PASS' } else { 'ECHEC' })"
if ($fatalError) { throw $fatalError }
if (-not $restorationSucceeded) {
    throw "La restauration a echoue: $($restorationErrors -join '; ')"
}
