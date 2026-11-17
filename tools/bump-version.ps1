# VERSION.txt tek surum kaynagidir. Her yayin oncesi patch artirilir.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$file = Join-Path $root 'VERSION.txt'
if (-not (Test-Path -LiteralPath $file)) { throw 'VERSION.txt bulunamadi.' }
$old = (Get-Content -LiteralPath $file -Raw).Trim()
if ($old -notmatch '^(\d+)\.(\d+)\.(\d+)$') {
    throw "Gecersiz surum '$old'. Beklenen bicim: major.minor.patch"
}
$major = [int]$Matches[1]
$minor = [int]$Matches[2]
$patch = [int]$Matches[3] + 1
if ($patch -gt 99) { $patch = 0; $minor++ }
if ($minor -gt 99) { $minor = 0; $major++ }
if ($major -gt 2100) { throw 'Surum kodu Android sinirini asiyor.' }
$new = "$major.$minor.$patch"
[IO.File]::WriteAllText($file, $new, [Text.Encoding]::ASCII)
Write-Output "$old|$new"
