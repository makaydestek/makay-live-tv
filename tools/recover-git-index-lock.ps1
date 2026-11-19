param(
    [Parameter(Mandatory = $true)]
    [string]$LockPath
)

$ErrorActionPreference = 'Stop'

try {
    if (-not (Test-Path -LiteralPath $LockPath -PathType Leaf)) {
        exit 0
    }

    $lock = Get-Item -LiteralPath $LockPath -Force
    if ($lock.Length -ne 0 -or $lock.LastWriteTimeUtc -gt [DateTime]::UtcNow.AddMinutes(-2)) {
        throw 'Git kilidi bos degil veya son iki dakika icinde yazilmis.'
    }
    if (Get-Process -Name 'git', 'git-*' -ErrorAction SilentlyContinue) {
        throw 'Calisan bir Git islemi var.'
    }

    # Verify that another program is not holding the lock open.
    $backup = $lock.FullName + '.stale-' + [Guid]::NewGuid().ToString('N')
    $handle = [IO.File]::Open($lock.FullName, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Delete)
    try {
        if ($handle.Length -ne 0 -or [IO.File]::GetLastWriteTimeUtc($lock.FullName) -gt [DateTime]::UtcNow.AddMinutes(-2)) {
            throw 'Git kilidi degisti.'
        }
        [IO.File]::Move($lock.FullName, $backup)
    } finally {
        $handle.Dispose()
    }

    Write-Host " [OK] Eski Git kilidi yedeklendi: $backup"
} catch {
    Write-Host " [HATA] Git indeks kilidi yedeklenemedi: $($_.Exception.Message)"
    Write-Host '        Git islemlerinin bitmesini bekleyip betigi yeniden calistirin.'
    exit 1
}
