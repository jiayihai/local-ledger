param([string[]]$Tasks = @('wrapper', ':core:test', ':app:assembleDebug'))
$ErrorActionPreference = 'Stop'
$ledgerRoot = Split-Path $PSScriptRoot -Parent
function Get-LedgerJunction([string]$Name, [string]$Target) {
    $ledgerLink = Join-Path $env:TEMP $Name
    if (Test-Path -LiteralPath $ledgerLink) {
        $ledgerExisting = Get-Item -LiteralPath $ledgerLink -Force
        if ($ledgerExisting.LinkType -eq 'Junction' -and $ledgerExisting.Target[0] -eq $Target) { return $ledgerLink }
        # Preserve an unexpected existing directory; use a fresh alias instead.
        $ledgerLink = Join-Path $env:TEMP ($Name + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
    }
    New-Item -ItemType Junction -Path $ledgerLink -Target $Target | Out-Null
    return $ledgerLink
}
$ledgerTools = Get-LedgerJunction 'local-ledger-devtools' (Join-Path $ledgerRoot '.tools')
$ledgerProject = Get-LedgerJunction 'local-ledger-android' (Join-Path $ledgerRoot 'android')
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
