param(
    [string]$Path = "$env:APPDATA\PrismLauncher\instances\Skyblock 1.21.11\minecraft\config\DungeonProgressHud\runs.json",
    [int64]$MinProfit = 4000000,
    [int64]$MaxProfit = 7000000
)

if (-not (Test-Path -LiteralPath $Path)) {
    Write-Error "runs.json not found: $Path"
    exit 1
}

$state = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
$samples = @($state.chestProfits)
$hits = @($samples | Where-Object {
    [int64]$_.profit -ge $MinProfit -and [int64]$_.profit -le $MaxProfit
})

Write-Host "File: $Path"
Write-Host "Total chest samples: $($samples.Count)"
Write-Host "Profit range: $MinProfit to $MaxProfit"
Write-Host "Matching chests: $($hits.Count)"
Write-Host ""
Write-Host "By chest:"
if ($hits.Count -eq 0) {
    Write-Host "  none"
} else {
    $hits |
        Group-Object chestName |
        Sort-Object Count -Descending |
        ForEach-Object { Write-Host "  $($_.Name): $($_.Count)" }
}
