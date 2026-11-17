Add-Type -AssemblyName System.Drawing

$root = "D:\MakayLiveTV\app\src\main\res"

function New-RoundRectPath([float]$x, [float]$y, [float]$w, [float]$h, [float]$r) {
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $r * 2
    $p.AddArc($x, $y, $d, $d, 180, 90)
    $p.AddArc($x + $w - $d, $y, $d, $d, 270, 90)
    $p.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90)
    $p.AddArc($x, $y + $h - $d, $d, $d, 90, 90)
    $p.CloseFigure()
    return $p
}

function New-IconBitmap([int]$size, [bool]$round) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::Transparent)

    $bg = [System.Drawing.ColorTranslator]::FromHtml("#0B1220")
    $accent = [System.Drawing.ColorTranslator]::FromHtml("#3F8CFF")
    $play = [System.Drawing.ColorTranslator]::FromHtml("#2EB050")

    if ($round) {
        $brush = New-Object System.Drawing.SolidBrush($bg)
        $g.FillEllipse($brush, 0, 0, ($size - 1), ($size - 1))
        $brush.Dispose()
    } else {
        $path = New-RoundRectPath 0 0 ($size - 1) ($size - 1) ($size * 0.18)
        $brush = New-Object System.Drawing.SolidBrush($bg)
        $g.FillPath($brush, $path)
        $brush.Dispose()
        $path.Dispose()
    }

    $s = $size / 108.0
    $fx = { param($v) [float]($v * $s) }

    # TV govdesi
    $tvPath = New-RoundRectPath (& $fx 24) (& $fx 34) (& $fx 60) (& $fx 40) (& $fx 6)
    $b = New-Object System.Drawing.SolidBrush($accent)
    $g.FillPath($b, $tvPath)
    $b.Dispose(); $tvPath.Dispose()

    # Ic ekran
    $scPath = New-RoundRectPath (& $fx 29) (& $fx 38) (& $fx 50) (& $fx 30) (& $fx 3)
    $b = New-Object System.Drawing.SolidBrush($bg)
    $g.FillPath($b, $scPath)
    $b.Dispose(); $scPath.Dispose()

    # Anten
    $pen = New-Object System.Drawing.Pen($accent, [float](3 * $s))
    $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $g.DrawLine($pen, (& $fx 48), (& $fx 26), (& $fx 54), (& $fx 34))
    $g.DrawLine($pen, (& $fx 60), (& $fx 26), (& $fx 54), (& $fx 34))
    $pen.Dispose()

    # Play ucgeni
    $pts = @(
        (New-Object System.Drawing.PointF((& $fx 48), (& $fx 45))),
        (New-Object System.Drawing.PointF((& $fx 48), (& $fx 63))),
        (New-Object System.Drawing.PointF((& $fx 64), (& $fx 54)))
    )
    $b = New-Object System.Drawing.SolidBrush($play)
    $g.FillPolygon($b, $pts)
    $b.Dispose()

    # Ayak
    $standPath = New-RoundRectPath (& $fx 42) (& $fx 77) (& $fx 24) (& $fx 5) (& $fx 2)
    $b = New-Object System.Drawing.SolidBrush($accent)
    $g.FillPath($b, $standPath)
    $b.Dispose(); $standPath.Dispose()

    $g.Dispose()
    return $bmp
}

$sizes = @{ "mipmap-mdpi" = 48; "mipmap-hdpi" = 72; "mipmap-xhdpi" = 96; "mipmap-xxhdpi" = 144; "mipmap-xxxhdpi" = 192 }
foreach ($key in $sizes.Keys) {
    $dir = Join-Path $root $key
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $px = [int]$sizes[$key]

    $square = New-IconBitmap $px $false
    $square.Save((Join-Path $dir "ic_launcher.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $square.Dispose()

    $circle = New-IconBitmap $px $true
    $circle.Save((Join-Path $dir "ic_launcher_round.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $circle.Dispose()
}

# TV banner: Android TV launcher standardi tam 320x180.
# Cizim 2x tuvalde yapilip yuksek kaliteyle kucultulur (kenar yumusatma korunur).
$cw = 640; $ch = 360
$bmp = New-Object System.Drawing.Bitmap($cw, $ch, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.Clear([System.Drawing.ColorTranslator]::FromHtml("#0B1220"))

$iBrush = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml("#3498DB"))
$g.FillEllipse($iBrush, -120, 210, 420, 300)
$g.FillEllipse($iBrush, 430, 240, 320, 240)
$iBrush.Dispose()

$accent = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml("#3F8CFF"))
$bgB = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml("#0B1220"))
$play = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml("#2EB050"))

$tvPath = New-RoundRectPath 300 104 88 52 10
$g.FillPath($accent, $tvPath); $tvPath.Dispose()
$scPath = New-RoundRectPath 306 112 76 40 6
$g.FillPath($bgB, $scPath); $scPath.Dispose()

$pen = New-Object System.Drawing.Pen([System.Drawing.ColorTranslator]::FromHtml("#3F8CFF"), 6)
$pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
$pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
$g.DrawLine($pen, 328, 88, 344, 104)
$g.DrawLine($pen, 360, 88, 344, 104)
$pen.Dispose()

$pts = @(
    (New-Object System.Drawing.PointF(326, 122)),
    (New-Object System.Drawing.PointF(326, 158)),
    (New-Object System.Drawing.PointF(362, 140))
)
$g.FillPolygon($play, $pts)

$standPath = New-RoundRectPath 326 162 36 8 4
$g.FillPath($accent, $standPath); $standPath.Dispose()

$font = New-Object System.Drawing.Font("Segoe UI", 26, [System.Drawing.FontStyle]::Bold)
$white = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
$fmt = New-Object System.Drawing.StringFormat
$fmt.Alignment = [System.Drawing.StringAlignment]::Center
$g.DrawString("Makay Live TV", $font, $white, (New-Object System.Drawing.RectangleF(0, 210, $cw, 50)), $fmt)
$font.Dispose()

$font2 = New-Object System.Drawing.Font("Segoe UI", 15, [System.Drawing.FontStyle]::Regular)
$grey = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml("#8FA6C8"))
$sep = [string][char]0x00B7
$subtitle = "Dahili Tuner  " + $sep + "  DVB-T / DVB-C / DVB-S"
$g.DrawString($subtitle, $font2, $grey, (New-Object System.Drawing.RectangleF(0, 262, $cw, 30)), $fmt)
$font2.Dispose()

$accent.Dispose(); $bgB.Dispose(); $play.Dispose(); $white.Dispose(); $grey.Dispose(); $fmt.Dispose()
$g.Dispose()

# Android TV banner'i yogunluk bazinda uret (mdpi temel: 320x180).
# Boylece hem lint "IconMissingDensityFolder" uyariyi kalkar hem de
# launcher her TV ekraninda keskin gorsel kullanir.
$bannerDensities = [ordered]@{
    "drawable-mdpi"    = @(320, 180)
    "drawable-hdpi"    = @(480, 270)
    "drawable-xhdpi"   = @(640, 360)
    "drawable-xxhdpi"  = @(960, 540)
}

foreach ($entry in $bannerDensities.GetEnumerator()) {
    $w = [int]$entry.Value[0]
    $h = [int]$entry.Value[1]

    $final = New-Object System.Drawing.Bitmap($w, $h, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $fg = [System.Drawing.Graphics]::FromImage($final)
    $fg.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $fg.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $fg.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $fg.DrawImage($bmp, 0, 0, $w, $h)
    $fg.Dispose()

    $bannerDir = Join-Path $root $entry.Key
    New-Item -ItemType Directory -Force -Path $bannerDir | Out-Null
    $final.Save((Join-Path $bannerDir "tv_banner.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $final.Dispose()
}
$bmp.Dispose()

Write-Output "Ikonlar uretildi."
