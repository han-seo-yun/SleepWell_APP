param([Parameter(Mandatory = $true)][string]$LocalProjectDirectory)
$ErrorActionPreference = "Stop"
$source = (Resolve-Path -LiteralPath $LocalProjectDirectory).Path
$repository = Split-Path -Parent $PSScriptRoot
$expected = [ordered]@{
    "app/src/main/assets/models/mobile_hgb_cmi.bin" = "968bccec23c8394cd884af560c962b0370b5341c63abfd96a1b4aec9c00c0cf8"
    "app/src/main/assets/models/mobile_hgb_mesa.bin" = "c1572aef170e1957534eb394fbcc2141c7c829aa6b9f0f410e04a707c676d92c"
    "app/src/androidTest/assets/fixtures/mobile_hgb_cmi_golden.json" = "2bc04e9232fe86b71f99dbc1cc9d3b6031bbed67bef587dcbe9f66d50ff2072f"
    "app/src/androidTest/assets/fixtures/mobile_hgb_mesa_golden.json" = "9629cc8a6d9a4d7b9d1ee49e2006883678fc5681fb36f5d2515ee3ce9d58b82f"
}
foreach ($entry in $expected.GetEnumerator()) {
    $file = Join-Path $source $entry.Key
    if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $entry.Value) {
        throw "Legacy fixture hash mismatch: $($entry.Key)"
    }
}
foreach ($entry in $expected.GetEnumerator()) {
    $target = Join-Path $repository $entry.Key
    New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $source $entry.Key) -Destination $target -Force
}
Write-Host "Private legacy regression assets verified and copied locally; do not commit them."
