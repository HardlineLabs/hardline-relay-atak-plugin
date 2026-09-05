[CmdletBinding()]
param()
. "$PSScriptRoot/environment.ps1"
$env:ATAK_SDK_ROOT = Join-Path $devRoot 'references/ATAK-CIV-5.6.0.23-SDK/ATAK-CIV-5.6.0.23-SDK'
$pins = @{
    'android_keystore' = '5a5ec442c96d117c7fa393d8402af4056a650c1a35efa419223cdac5fae30a15'
    'main.jar' = 'a90cb9ddcef5dfffaef1d51e0fe19d1a9a01679d00baa2fba32cdb08f0f76980'
    'atak-gradle-takdev.jar' = '1bf783084a183b11455c9e72d96e162912b94a7ec9812e22a3eca7af0b239276'
    'atak.apk' = 'fcf168bade539faad7accd3d078245466b23c4ff3fde290fa5032b68e0cede90'
}
foreach ($name in $pins.Keys) {
    $path = Join-Path $env:ATAK_SDK_ROOT $name
    if (-not (Test-Path -LiteralPath $path) -or
        (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $pins[$name]) {
        throw "Missing or changed ATAK SDK asset: $name. Obtain the pinned SDK manually; see atak-plugin/README.md."
    }
}
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    & ./gradlew.bat -PwithAtak=true :atak-plugin:assembleCivDebug :atak-plugin:lintCivDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'ATAK SDK plugin build failed' }
} finally { Pop-Location }
