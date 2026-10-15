<#
.SYNOPSIS
  Capture the program-tile flip animation frame by frame from a real device, then measure it.

.DESCRIPTION
  One command gives you "timeline + angle + perspective + size" evidence for one flip:

      powershell -File tools/capture_flip.ps1 -Device XKEERWW8YTRWJZ9X -Mode open -Slow 8

  What it does, in order:
    1. reads the animation constants straight out of the source (FLIP_MS, HANDOFF,
       HANDOFF_GROW, PERSPECTIVE_DISTANCE_FACTOR, BAR_FLIP_MS) and records the installed
       build id, device size/density and the current animation scales into <OutDir>/run.json
    2. settings put global animator_duration_scale <Slow>   (verified: Compose animators obey it)
    3. for each requested mode: force-stop -> am start -> tap the program tab -> screenshot the
       list at rest (baseline) -> tap the card (-Mode open) or, after the open flip settles,
       tap the toolbar back button (-Mode close)
    4. captures frames with screenrecord (video, real 60fps timeline when the encoder feels
       like it) or with tools/flip_burst.sh (screencap burst, ~2 fps but never zero frames);
       -Capture auto tries video first and falls back to burst when the video timeline is thin
    5. decodes to <OutDir>/frames/*.png + frames.csv, then runs tools/measure_flip.py
    6. restores animator_duration_scale in a finally block - your phone is never left in slow motion

  Coordinate note: tap points that matter (the back arrow in the graph toolbar) are read from a
  uiautomator dump, not from hardcoded numbers, because the hardcoded 126,448 from the task brief
  lands on empty canvas on this device - the real back button is around 126,220.

.EXAMPLE
  powershell -File tools/capture_flip.ps1 -Mode both -Slow 8
.EXAMPLE
  powershell -File tools/capture_flip.ps1 -Mode open -Slow 1 -Capture burst -KeepScale
.EXAMPLE
  # only undo a forgotten slow-motion setting
  powershell -File tools/capture_flip.ps1 -RestoreOnly
#>
[CmdletBinding()]
param(
    [string]$Device = "",
    [ValidateSet('open', 'close', 'both')][string]$Mode = 'open',
    [double]$Slow = 8,
    [string]$OutDir = "",
    [ValidateSet('auto', 'video', 'burst')][string]$Capture = 'auto',
    [string]$ProgramTab = '720,3060',
    [string]$CardTap = '',
    [string]$BackTap = 'auto',
    [string]$Window = '',
    [int]$VideoLimit = 0,
    [double]$MinVideoFps = 8,
    [int]$TargetFrames = 90,
    [double]$StartupWait = 4.0,
    [double]$TabWait = 1.5,
    [double]$PreRoll = 1.2,
    [double]$TailWait = 1.5,
    [int]$BurstFrames = 0,
    [switch]$KeepScale,
    [switch]$NoMeasure,
    [switch]$RestoreOnly
)

$ErrorActionPreference = 'Continue'
$RepoRoot = Split-Path -Parent $PSScriptRoot
$MeasurePy = Join-Path $RepoRoot 'tools\measure_flip.py'
$FramesPy = Join-Path $RepoRoot 'tools\video_to_frames.py'
$BurstSh = Join-Path $RepoRoot 'tools\flip_burst.sh'
$SourcePs = Join-Path $RepoRoot 'app\src\main\java\com\yjc\click\ui\program\ProgramScreen.kt'
$SourceMs = Join-Path $RepoRoot 'app\src\main\java\com\yjc\click\ui\shell\MainScaffold.kt'
$script:ScaleChanged = $false

function Log([string]$msg) {
    $line = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss.fff'), $msg
    Write-Host $line
    if ($script:LogFile) { Add-Content -Path $script:LogFile -Value $line -Encoding utf8 }
}

function Die([string]$msg) {
    Log "ABORT: $msg"
    throw $msg
}

function Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$AdbArgs)
    $all = @('-s', $script:Device) + $AdbArgs
    $out = & adb @all 2>&1
    if ($LASTEXITCODE -ne 0) { Log ("adb " + ($AdbArgs -join ' ') + " -> exit $LASTEXITCODE") }
    return $out
}

function TapPoint([string]$xy, [string]$what) {
    $p = $xy.Split(',')
    if ($p.Count -ne 2) { Die "bad tap point for $what : $xy" }
    Log "tap $what at $xy"
    Adb @('shell', 'input', 'tap', $p[0].Trim(), $p[1].Trim()) | Out-Null
}

function Get-UiXml {
    $remote = "/sdcard/ui_$($PID).xml"
    Adb @('shell', 'uiautomator', 'dump', $remote) | Out-Null
    $xml = (Adb @('shell', 'cat', $remote)) -join "`n"
    return $xml
}

function Find-NodeCenter([string]$Xml, [string]$Needle) {
    $idx = $Xml.IndexOf($Needle)
    if ($idx -lt 0) { return $null }
    $start = $Xml.LastIndexOf('<node', $idx)
    $end = $Xml.IndexOf('>', $idx)
    if ($start -lt 0 -or $end -lt 0) { return $null }
    $frag = $Xml.Substring($start, $end - $start + 1)
    $m = [regex]::Match($frag, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $m.Success) { return $null }
    $x1 = [int]$m.Groups[1].Value; $y1 = [int]$m.Groups[2].Value
    $x2 = [int]$m.Groups[3].Value; $y2 = [int]$m.Groups[4].Value
    return [pscustomobject]@{
        x    = [int](($x1 + $x2) / 2)
        y    = [int](($y1 + $y2) / 2)
        rect = "$x1,$y1,$($x2 - $x1),$($y2 - $y1)"
    }
}

function Get-SourceConst([string]$path, [string]$name) {
    if (-not (Test-Path $path)) { return $null }
    $m = Select-String -Path $path -Pattern "const val $name\s*=\s*([0-9.]+)f?" | Select-Object -First 1
    if (-not $m) { return $null }
    $v = $m.Matches[0].Groups[1].Value
    return [pscustomobject]@{ value = [double]$v; line = $m.LineNumber; text = $m.Line.Trim() }
}

function Screenshot([string]$localPath, [string]$tag) {
    $remote = "/sdcard/cap_$PID.png"
    Adb @('shell', 'screencap', '-p', $remote) | Out-Null
    Adb @('pull', $remote, $localPath) | Out-Null
    if (-not (Test-Path $localPath)) { Die "screencap $tag failed (no $localPath)" }
    Log "screencap $tag -> $(Split-Path -Leaf $localPath)"
}

function Start-Background([string[]]$AdbArgs, [string]$stdout, [string]$stderr) {
    $all = @('-s', $script:Device) + $AdbArgs
    return Start-Process -FilePath 'adb' -ArgumentList $all -PassThru -NoNewWindow `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr
}

# --------------------------------------------------------------------------- preflight
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { Die "adb not on PATH" }
if (-not (Test-Path $MeasurePy)) { Die "missing $MeasurePy" }
if (-not (Test-Path $FramesPy)) { Die "missing $FramesPy" }

$devices = (& adb devices) 2>&1 | Select-String -Pattern '\tdevice$'
if (-not $Device) {
    if (-not $devices) { Die "no device in 'adb devices'" }
    $Device = ($devices[0] -split '\t')[0].Trim()
}
if (-not (($devices | Out-String) -match [regex]::Escape($Device))) { Die "device $Device not attached" }
$script:Device = $Device

if ($RestoreOnly) {
    Adb @('shell', 'settings', 'put', 'global', 'animator_duration_scale', '1') | Out-Null
    Log "animator_duration_scale -> 1"
    exit 0
}

if (-not $OutDir) {
    $OutDir = Join-Path $RepoRoot ("tools\captures\{0}_{1}" -f (Get-Date -Format 'yyyyMMdd_HHmmss'), $Mode)
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$OutDir = (Resolve-Path $OutDir).Path
$script:LogFile = Join-Path $OutDir 'run.log'
Set-Content -Path $script:LogFile -Value ("capture_flip.ps1 {0}" -f (Get-Date -Format s)) -Encoding utf8
Log "repo      : $RepoRoot"
Log "device    : $Device"
Log "outdir    : $OutDir"
Log "mode      : $Mode   slow=${Slow}x   capture=$Capture"

$sizeRaw = (Adb @('shell', 'wm', 'size') | Out-String)
$densityRaw = (Adb @('shell', 'wm', 'density') | Out-String)
$appRaw = (Adb @('shell', 'dumpsys', 'package', 'com.yjc.click') | Out-String)
$installed = [regex]::Match($appRaw, 'lastUpdateTime=([0-9\- :]+)').Groups[1].Value.Trim()
$versionName = [regex]::Match($appRaw, 'versionName=(\S+)').Groups[1].Value.Trim()
$flip = Get-SourceConst $SourcePs 'FLIP_MS'
$handoff = Get-SourceConst $SourcePs 'HANDOFF'
$handoffGrow = Get-SourceConst $SourcePs 'HANDOFF_GROW'
$persp = Get-SourceConst $SourcePs 'PERSPECTIVE_DISTANCE_FACTOR'
$barMs = Get-SourceConst $SourceMs 'BAR_FLIP_MS'
$flipMs = if ($flip) { $flip.value } else { 600.0 }
$expectedMs = $flipMs * $Slow
$scaleBefore = ((Adb @('shell', 'settings', 'get', 'global', 'animator_duration_scale')) | Out-String).Trim()

Log "device    : $($sizeRaw.Trim() -replace '\s+', ' ')  $($densityRaw.Trim() -replace '\s+', ' ')"
Log "app       : versionName=$versionName lastUpdateTime=$installed"
Log ("source    : FLIP_MS={0} (line {1})  BAR_FLIP_MS={2}  HANDOFF={3}  HANDOFF_GROW={4}  F={5}" -f `
        $flipMs, $(if ($flip) { $flip.line } else { '?' }), $(if ($barMs) { $barMs.value } else { '?' }), `
        $(if ($handoff) { $handoff.value } else { '?' }), $(if ($handoffGrow) { $handoffGrow.value } else { '?' }), `
        $(if ($persp) { $persp.value } else { '?' }))
Log "expected flip duration on device: FLIP_MS x slow = $expectedMs ms"
if ($flip -and $flip.text -match 'TEMP') { Log "WARNING: the source line still says TEMP - $($flip.text)" }
if ($barMs -and [math]::Abs($barMs.value - $flipMs) -gt 0.001) {
    Log "WARNING: BAR_FLIP_MS=$($barMs.value) != FLIP_MS=$flipMs (the bars and the tile will not stay in sync)"
}
if ($expectedMs -gt 20000) {
    Log "WARNING: $expectedMs ms per flip is long; measure_flip.py will still work, but consider -Slow 1"
}
if ($installed) {
    $inst = [datetime]::ParseExact($installed, 'yyyy-MM-dd HH:mm:ss', $null)
    $src = (Get-Item $SourcePs).LastWriteTime
    if ($src -gt $inst.AddSeconds(5)) {
        Log "WARNING: $($SourcePs | Split-Path -Leaf) is NEWER than the installed build - the APK on the phone may not contain the current constants"
    }
}

$script:DeviceShort = ($sizeRaw -replace '[^0-9x]', '')
$windowRect = $Window
if (-not $windowRect) {
    if ($sizeRaw -match '(\d+)x(\d+)') { $windowRect = "0,0,$($Matches[1]),$($Matches[2])" }
    Log "window    : $windowRect (device size; override with -Window for a tighter content-area box)"
}

# --------------------------------------------------------------------------- helpers for one round
function Invoke-FlipRound {
    param([string]$FlipMode, [string]$CapMode, [string]$RoundDir)

    New-Item -ItemType Directory -Force -Path $RoundDir | Out-Null
    $prefix = "flip_${FlipMode}"
    $json = [ordered]@{
        mode = $FlipMode; capture = $CapMode; slow = $Slow; flip_ms = $flipMs
        expected_ms = $expectedMs; device = $script:Device; installed = $installed
        versionName = $versionName; window_rect = $windowRect; started = (Get-Date -Format s)
    }

    Log "[$FlipMode/$CapMode] force-stop + start"
    Adb @('shell', 'am', 'force-stop', 'com.yjc.click') | Out-Null
    Adb @('shell', 'am', 'start', '-W', '-n', 'com.yjc.click/.MainActivity') | Out-Null
    Start-Sleep -Seconds $StartupWait
    TapPoint $ProgramTab 'program tab'
    Start-Sleep -Seconds $TabWait

    $basePng = Join-Path $RoundDir 'baseline.png'
    Screenshot $basePng 'baseline (list at rest)'
    $detTxt = (& python $MeasurePy --detect-card $basePng 2>&1 | Out-String)
    Set-Content -Path (Join-Path $RoundDir 'detect_card.json') -Value $detTxt -Encoding utf8
    $cardRect = $null; $cardColor = $null; $seed = $null
    if ($detTxt -match '"rect":\s*\[\s*(\d+),\s*(\d+),\s*(\d+),\s*(\d+)\s*\]') {
        $cardRect = "$($Matches[1]),$($Matches[2]),$($Matches[3]),$($Matches[4])"
    }
    if ($detTxt -match '"card_color":\s*\[\s*(\d+),\s*(\d+),\s*(\d+)\s*\]') {
        $cardColor = "$($Matches[1]),$($Matches[2]),$($Matches[3])"
    }
    if (-not $cardRect) {
        Log (Get-Content (Join-Path $RoundDir 'detect_card.json') -Raw)
        Die "no program card found on the list page. The tool needs at least one program card; create one in the app (or pass -CardTap x,y if you know where a card is)."
    }
    $cr = $cardRect.Split(',')
    $seed = "{0},{1}" -f ([int]$cr[0] + [int]$cr[2] / 2), ([int]$cr[1] + [int]$cr[3] / 2)
    Log "card      : rect=$cardRect colour=$cardColor seed=$seed"
    $json.card_rect = $cardRect; $json.card_color = $cardColor

    $tapTarget = 'card'
    if ($FlipMode -eq 'open') {
        if (-not $CardTap) { $CardTap = $seed }
        $tapXY = $CardTap
    }
    else {
        # get into the graph first, then close it: the measured flip is the CLOSE one
        if (-not $CardTap) { $CardTap = $seed }
        TapPoint $CardTap 'card (to open the graph first)'
        $openSettle = [math]::Max(1.0, $expectedMs / 1000.0 + 1.0)
        Log "waiting $openSettle s for the open flip to settle"
        Start-Sleep -Seconds $openSettle
        $graphPng = Join-Path $RoundDir 'graph.png'
        Screenshot $graphPng 'graph page (before closing)'
        $xml = Get-UiXml
        Set-Content -Path (Join-Path $RoundDir 'ui_graph.xml') -Value $xml -Encoding utf8
        if ($BackTap -eq 'auto') {
            $node = Find-NodeCenter $xml '关闭'
            if (-not $node) { $node = Find-NodeCenter $xml 'content-desc="关闭"' }
            if ($node) {
                $BackTap = "$($node.x),$($node.y)"
                Log "back button from uiautomator dump: $BackTap (node rect $($node.rect))"
            }
            else {
                $BackTap = '126,220'
                Log "WARNING: '关闭' not found in the dump, falling back to $BackTap"
            }
        }
        if (-not ($xml -match '关闭')) { Log "WARNING: the graph toolbar back button is not in the dump - is the graph really open?" }
        $tapXY = $BackTap
        $tapTarget = 'back arrow (close flip)'
    }

    # ---- capture
    $videoFile = $null; $burstDir = $null
    $framesDir = Join-Path $RoundDir 'frames'
    $framesCsv = Join-Path $RoundDir 'frames.csv'
    if ($CapMode -eq 'video') {
        $secs = if ($VideoLimit -gt 0) { $VideoLimit } else {
            [math]::Ceiling($PreRoll + $expectedMs / 1000.0 + $TailWait) + 2
        }
        $remote = "/sdcard/${prefix}.mp4"
        Adb @('shell', 'rm', '-f', $remote) | Out-Null
        Log "[$FlipMode/$CapMode] screenrecord ${secs}s -> $remote"
        $so = Join-Path $RoundDir 'screenrecord.out'
        $se = Join-Path $RoundDir 'screenrecord.err'
        $proc = Start-Background @('shell', 'screenrecord', '--time-limit', "$secs", $remote) $so $se
        try {
            Start-Sleep -Seconds $PreRoll
            $tapTime = Get-Date
            TapPoint $tapXY $tapTarget
            $wait = $expectedMs / 1000.0 + $TailWait
            Log "waiting $wait s for the flip to finish"
            Start-Sleep -Seconds $wait
            if (-not $proc.HasExited) { Log "screenrecord still running; waiting for it to hit --time-limit" }
            $proc.WaitForExit(($secs + 10) * 1000) | Out-Null
        }
        finally {
            if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
        }
        $videoFile = Join-Path $RoundDir "$prefix.mp4"
        Adb @('pull', $remote, $videoFile) | Out-Null
        if (-not (Test-Path $videoFile)) { Log "WARNING: video pull failed" }
        else { Log "video     : $videoFile ($((Get-Item $videoFile).Length) bytes), tap at t≈$([int]($PreRoll*1000)) ms" }
        Log "[$FlipMode/$CapMode] decoding video to frames"
        & python $FramesPy --video $videoFile --outdir $RoundDir 2>&1 | Tee-Object -FilePath (Join-Path $RoundDir 'frames_stdout.txt') | ForEach-Object { Log "  $_" }
    }
    else {
        $remote = "/data/local/tmp/flip_burst.sh"
        $tmpScript = Join-Path $env:TEMP "flip_burst_$PID.sh"
        (Get-Content $BurstSh -Raw) -replace "`r`n", "`n" | Set-Content -Path $tmpScript -NoNewline -Encoding ascii
        Adb @('push', $tmpScript, $remote) | Out-Null
        $remoteDir = "/sdcard/${prefix}_burst"
        Adb @('shell', 'rm', '-rf', $remoteDir) | Out-Null
        Adb @('shell', 'mkdir', '-p', $remoteDir) | Out-Null
        if ($BurstFrames -le 0) {
            $BurstFrames = [int][math]::Ceiling(($PreRoll + $expectedMs / 1000.0 + $TailWait + 2) / 0.5)
        }
        Log "[$FlipMode/$CapMode] screencap burst, up to $BurstFrames frames (~2 fps)"
        $so = Join-Path $RoundDir 'burst.out'
        $se = Join-Path $RoundDir 'burst.err'
        $proc = Start-Background @('shell', 'sh', $remote, $remoteDir, "$BurstFrames", $prefix, '0') $so $se
        try {
            Start-Sleep -Seconds $PreRoll
            TapPoint $tapXY $tapTarget
            $wait = $expectedMs / 1000.0 + $TailWait
            Log "waiting $wait s for the flip to finish"
            Start-Sleep -Seconds $wait
        }
        finally {
            if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
            Adb @('shell', 'pkill', '-f', 'flip_burst') | Out-Null
        }
        $localBurst = Join-Path $RoundDir 'burst'
        New-Item -ItemType Directory -Force -Path $localBurst | Out-Null
        Adb @('pull', $remoteDir, (Split-Path $localBurst -Parent)) | Out-Null
        $moved = Join-Path (Split-Path $localBurst -Parent) (Split-Path $remoteDir -Leaf)
        if ((Test-Path $moved) -and -not (Test-Path (Join-Path $localBurst 'x'))) {
            $localBurst = $moved
        }
        $indexLocal = Get-ChildItem -Path $RoundDir -Recurse -Filter "${prefix}_index.txt" |
            Select-Object -First 1
        if (-not $indexLocal) { Die "burst index not found under $RoundDir (remote dir $remoteDir)" }
        Log "burst     : $((Get-ChildItem (Split-Path $indexLocal.FullName -Parent) -Filter '*.png').Count) png, index $($indexLocal.Name)"
        Log "[$FlipMode/$CapMode] normalising frames"
        & python $FramesPy --png-dir (Split-Path $indexLocal.FullName -Parent) --index $indexLocal.FullName `
            --outdir $RoundDir 2>&1 | Tee-Object -FilePath (Join-Path $RoundDir 'frames_stdout.txt') | ForEach-Object { Log "  $_" }
    }
    if (-not (Test-Path $framesCsv)) { Die "no frames.csv produced in $RoundDir" }

    $rows = Import-Csv $framesCsv
    $json.frames = $rows.Count
    $fpsInWindow = 0.0
    $tmin = $null; $tmax = $null; $stride = 1
    if ($CapMode -eq 'video') {
        $tapOffset = [int]($PreRoll * 1000)
        $tmin = [math]::Max(0, $tapOffset - 500)
        $tmax = $tapOffset + $expectedMs + 500
        $inWin = @($rows | Where-Object { [double]$_.t_ms -ge $tmin -and [double]$_.t_ms -le $tmax })
        $span = ($tmax - $tmin) / 1000.0
        $fpsInWindow = if ($span -gt 0) { $inWin.Count / $span } else { 0 }
        $stride = [math]::Max(1, [int][math]::Ceiling($inWin.Count / [double]$TargetFrames))
        Log ("timeline  : {0} frames total, {1} inside the flip window ({2:N1} fps, stride {3})" -f `
                $rows.Count, $inWin.Count, $fpsInWindow, $stride)
    }
    $json.fps_in_window = [math]::Round($fpsInWindow, 2)

    if (-not $NoMeasure) {
        $argList = @($MeasurePy, '--dir', $RoundDir, '--baseline', $basePng, '--seed', $seed,
            '--flip-ms', "$flipMs", '--slow', "$Slow")
        if ($windowRect) { $argList += @('--window-rect', $windowRect) }
        if ($cardRect) { $argList += @('--card-rect', $cardRect) }
        if ($cardColor) { $argList += @('--card-color', $cardColor) }
        if ($persp) { $argList += @('--F', "$($persp.value)") }
        if ($handoffGrow) { $argList += @('--handoff-grow', "$($handoffGrow.value)") }
        if ($tmin -ne $null) { $argList += @('--tmin', "$tmin", '--tmax', "$tmax") }
        if ($stride -gt 1) { $argList += @('--stride', "$stride") }
        Log "[$FlipMode/$CapMode] measuring: python $(($argList | Select-Object -Skip 1) -join ' ')"
        & python @argList 2>&1 | Tee-Object -FilePath (Join-Path $RoundDir 'measure_stdout.txt') |
            ForEach-Object { Log "  $_" }
    }

    $json.finished = (Get-Date -Format s)
    ($json | ConvertTo-Json -Depth 4) | Set-Content -Path (Join-Path $RoundDir 'run.json') -Encoding utf8
    return [pscustomobject]@{
        dir = $RoundDir; capture = $CapMode; fps = $fpsInWindow; card = $cardRect
        seed = $seed; frames = $rows.Count
    }
}

# --------------------------------------------------------------------------- run
$results = @()
try {
    if (-not $KeepScale) {
        Log "animator_duration_scale: $scaleBefore -> $Slow"
        Adb @('shell', 'settings', 'put', 'global', 'animator_duration_scale', "$Slow") | Out-Null
        $script:ScaleChanged = $true
    }
    else {
        Log "animator_duration_scale left at $scaleBefore (-KeepScale)"
    }
    $modes = if ($Mode -eq 'both') { @('open', 'close') } else { @($Mode) }
    foreach ($m in $modes) {
        $cap = $Capture
        $dir = Join-Path $OutDir $m
        $res = Invoke-FlipRound -FlipMode $m -CapMode $cap -RoundDir $dir
        $results += $res
        if ($Capture -eq 'auto' -and $cap -eq 'video' -and $res.fps -lt $MinVideoFps) {
            Log "video gave only $([math]::Round($res.fps,1)) fps in the flip window (< $MinVideoFps) -> redoing $m with the screencap burst"
            $dir2 = Join-Path $OutDir "${m}_burst"
            $results += Invoke-FlipRound -FlipMode $m -CapMode 'burst' -RoundDir $dir2
        }
    }
}
catch {
    Log "ERROR: $_"
    $script:Failed = $true
}
finally {
    if ($script:ScaleChanged -and -not $KeepScale) {
        Adb @('shell', 'settings', 'put', 'global', 'animator_duration_scale', '1') | Out-Null
        $now = ((Adb @('shell', 'settings', 'get', 'global', 'animator_duration_scale')) | Out-String).Trim()
        Log "animator_duration_scale restored: $scaleBefore -> 1 (device now says $now)"
    }
    else {
        Log "animator_duration_scale NOT changed by this run (device says $scaleBefore)"
    }
}

Log "--- done ---"
foreach ($r in $results) { Log ("result: mode={0} capture={1} frames={2} fps={3} -> {4}" -f `
            (Split-Path $r.dir -Leaf), $r.capture, $r.frames, [math]::Round($r.fps, 1), $r.dir) }
if (-not $results) { Log "result: nothing captured" }
Log "artifacts: run.log, run.json, baseline.png, frames/, frames.csv, measure.csv, measure_stdout.txt in $OutDir"
if ($script:Failed) { exit 1 }
