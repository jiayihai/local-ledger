param([string[]]$Tasks = @('wrapper', ':core:test', ':app:assembleDebug'))
$ErrorActionPreference = 'Stop'
$ledgerRoot = Split-Path $PSScriptRoot -Parent
$ledgerTools = Join-Path $env:TEMP 'local-ledger-devtools'
$ledgerProject = Join-Path $env:TEMP 'local-ledger-android'
if (-not (Test-Path -LiteralPath $ledgerTools)) { New-Item -ItemType Junction -Path $ledgerTools -Target (Join-Path $ledgerRoot '.tools') | Out-Null }
if (-not (Test-Path -LiteralPath $ledgerProject)) { New-Item -ItemType Junction -Path $ledgerProject -Target (Join-Path $ledgerRoot 'android') | Out-Null }
$env:JAVA_HOME = (Get-ChildItem -LiteralPath "$ledgerTools/jdk" -Directory | Select-Object -First 1).FullName
$env:ANDROID_HOME = "$ledgerTools/android-sdk"
$env:ANDROID_USER_HOME = "$ledgerTools/android-user"
$env:GRADLE_USER_HOME = "$ledgerTools/gradle-cache"
$env:PATH = "$env:JAVA_HOME/bin;$env:ANDROID_HOME/platform-tools;$env:PATH"
$env:DEBUG = $null
$env:JAVA_TOOL_OPTIONS = $null
$ledgerLogDir = Join-Path $ledgerRoot 'outputs/build'
New-Item -ItemType Directory -Force -Path $ledgerLogDir | Out-Null
Push-Location $ledgerProject
try {
    $ErrorActionPreference = 'Continue'
    & "$ledgerTools/gradle-8.11.1/bin/gradle.bat" --console=plain @Tasks *> (Join-Path $ledgerLogDir 'latest.log')
    $ledgerExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    Get-Content -LiteralPath (Join-Path $ledgerLogDir 'latest.log') -Tail 70
    exit $ledgerExit
} finally { Pop-Location }
