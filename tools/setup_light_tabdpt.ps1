param(
    [Parameter(Mandatory = $true)]
    [string]$BundleDirectory
)

$ErrorActionPreference = "Stop"
$source = (Resolve-Path -LiteralPath $BundleDirectory).Path
$repository = Split-Path -Parent $PSScriptRoot
$destination = Join-Path $repository "app\src\main\assets\tabdpt"

$expected = [ordered]@{
    "manifest.json" = "A9F899025307C75C70F45DBFD6404BD27489287BF211A2CDC698D33B6472B324"
    "cmi_context_f32.bin" = "30B6CB3FBA8260B9ABD4CA37D94ECF4C9DB2AE1011220F6B27DD7101BD4F9444"
    "cmi_preprocessor.json" = "3315610F21DCB1F6D4BF3DC8A7DA94A7D0B48245E11DD0B7A00DA238CCE39BE0"
    "goldens_validation32.json" = "89FDBADBBE524BFEB9176922C747B16A15F1FB8230DD9C12C5820214E0100088"
    "mesa_context_f32.bin" = "58C16DB56298EF87DC0784C987AC467E851AF7A2E62B041A620F6305FC1CCBD2"
    "mesa_preprocessor.json" = "F629C7C8E0B85F1E6AD7B4DD94DAF8D2BA0CC43D40D76F40A3AF5FED29E4403B"
    "tabdpt_ctx1024_b1_fp32_preprocessed_think64.onnx" = "B260D5959556BBAD8ACFAC59D901DC456B640F49BAB378E4D1C5BAAF7ED8565B"
    "tabdpt_ctx1024_b1_fp32_preprocessed_think64.onnx.data" = "8B66F02A116EF6109A7EB8D8179446DB3C0F825AA7BCFDB82FEBDBE384CB1F6F"
    "thinking64_wrapper_equivalence_ctx1024.json" = "C2646644709D01FCB4F530B3EFD1C110C58547118CBDD1D5D7573B0869DC88DE"
}

foreach ($entry in $expected.GetEnumerator()) {
    $path = Join-Path $source $entry.Key
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required TabDPT artifact is missing: $($entry.Key)"
    }
    $actual = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
    if ($actual -ne $entry.Value) {
        throw "SHA-256 mismatch for $($entry.Key). Expected $($entry.Value), got $actual"
    }
}

$manifest = Get-Content -LiteralPath (Join-Path $source "manifest.json") -Raw | ConvertFrom-Json
$registered = @($manifest.files | ForEach-Object { $_.name })
$requiredRegistered = @($expected.Keys | Where-Object { $_ -notin @("manifest.json", "goldens_validation32.json") })
if (@(Compare-Object $registered $requiredRegistered).Count -ne 0) {
    throw "The manifest file list does not match the frozen lightweight TabDPT bundle."
}

New-Item -ItemType Directory -Path $destination -Force | Out-Null
foreach ($name in $expected.Keys) {
    Copy-Item -LiteralPath (Join-Path $source $name) -Destination (Join-Path $destination $name) -Force
}

Write-Host "Verified and installed the lightweight TabDPT bundle in $destination"
Write-Host "This directory is ignored by Git and must not be committed."
