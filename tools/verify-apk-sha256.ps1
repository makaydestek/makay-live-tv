param(
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [Parameter(Mandatory = $true)][string]$ShaPath
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $ApkPath)) { throw "APK bulunamadi: $ApkPath" }
if (-not (Test-Path -LiteralPath $ShaPath)) { throw "SHA-256 dosyasi bulunamadi: $ShaPath" }
$algorithm = [System.Security.Cryptography.SHA256]::Create()
$stream = [System.IO.File]::OpenRead($ApkPath)
try {
    $expected = ([BitConverter]::ToString($algorithm.ComputeHash($stream))).Replace('-', '').ToLowerInvariant()
} finally {
    $stream.Dispose()
    $algorithm.Dispose()
}
$line = (Get-Content -LiteralPath $ShaPath -Raw).Trim()
if ($line -notmatch ('^' + $expected + '\s+')) { throw 'SHA-256 teslim dosyasi APK ile eslesmiyor.' }
Write-Output "SHA-256 dogrulandi: $expected"
