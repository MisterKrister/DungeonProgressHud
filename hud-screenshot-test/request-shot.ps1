param(
    [string]$InstanceMinecraftDir = "C:\Users\krister\AppData\Roaming\PrismLauncher\instances\game testisg\minecraft",
    [string]$Reason = "manual",
    [int]$TimeoutSeconds = 20
)

$ErrorActionPreference = "Stop"
$minecraftDir = Resolve-Path -LiteralPath $InstanceMinecraftDir
$screenshotsDir = Join-Path $minecraftDir "screenshots"
$triggerFile = Join-Path $minecraftDir "hud-screenshot-trigger.txt"

if (-not (Test-Path -LiteralPath $screenshotsDir)) {
    New-Item -ItemType Directory -Path $screenshotsDir | Out-Null
}

$before = Get-ChildItem -LiteralPath $screenshotsDir -Filter "dph-ui-test-*.png" -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

Set-Content -LiteralPath $triggerFile -Value ("{0} {1:o}" -f $Reason, [DateTimeOffset]::Now) -Encoding UTF8

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
do {
    Start-Sleep -Milliseconds 250
    $latest = Get-ChildItem -LiteralPath $screenshotsDir -Filter "dph-ui-test-*.png" -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if ($latest -and (-not $before -or $latest.FullName -ne $before.FullName -or $latest.LastWriteTime -gt $before.LastWriteTime)) {
        $latest.FullName
        exit 0
    }
} while ((Get-Date) -lt $deadline)

throw "Timed out waiting for new DPH screenshot. Is the game testing instance running with HudScreenshotTest.jar loaded?"
