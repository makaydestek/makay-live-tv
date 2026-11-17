param(
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [Parameter(Mandatory = $true)][string]$ExpectedVersion
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $ApkPath)) { throw "APK bulunamadi: $ApkPath" }
$roots = @()
if ($env:ANDROID_HOME) { $roots += $env:ANDROID_HOME }
if ($env:ANDROID_SDK_ROOT) { $roots += $env:ANDROID_SDK_ROOT }
if ($env:LOCALAPPDATA) { $roots += (Join-Path $env:LOCALAPPDATA 'Android\Sdk') }
$aapt = $null
foreach ($root in $roots | Select-Object -Unique) {
    $buildTools = Join-Path $root 'build-tools'
    if (-not (Test-Path -LiteralPath $buildTools)) { continue }
    $found = Get-ChildItem -LiteralPath $buildTools -Filter aapt.exe -Recurse -File -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if ($found) { $aapt = $found.FullName; break }
}
if (-not $aapt) { throw 'Android SDK build-tools/aapt.exe bulunamadi; APK surumu dogrulanamiyor.' }
$badging = (& $aapt dump badging $ApkPath 2>$null | Out-String)
if ($LASTEXITCODE -ne 0) { throw 'aapt APK bilgisini okuyamadi.' }
$versionMatch = [regex]::Match($badging, "versionName='([^']+)'")
$codeMatch = [regex]::Match($badging, "versionCode='([^']+)'")
if (-not $versionMatch.Success) { throw 'APK versionName alani okunamadi.' }
$actual = $versionMatch.Groups[1].Value
if ($actual -ne $ExpectedVersion) { throw "APK surumu uyusmuyor: APK=$actual beklenen=$ExpectedVersion" }
if (-not $codeMatch.Success) { throw 'APK versionCode alani okunamadi.' }
Write-Output "APK dogrulandi: versionName=$actual versionCode=$($codeMatch.Groups[1].Value)"
