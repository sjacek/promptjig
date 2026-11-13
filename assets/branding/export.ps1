$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$assetRoot = $PSScriptRoot
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $assetRoot '../..'))
$renderRoot = Join-Path $repositoryRoot 'build/branding'
[void][System.IO.Directory]::CreateDirectory($renderRoot)

# A dedicated headless profile keeps the export separate from personal browser state.
$browserCandidates = @(
    'C:\Program Files\Google\Chrome\Application\chrome.exe',
    'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'
)
$browser = $browserCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $browser) { throw 'Install Chrome or Edge to render the SVG icon.' }
$renderedIcon = Join-Path $renderRoot ("promptjig-icon-$([System.Guid]::NewGuid().ToString('N'))-1024.png")
$svgUri = [System.Uri]::new((Join-Path $assetRoot 'promptjig-icon.svg')).AbsoluteUri
$browserArguments = @(
    '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
    '--disable-background-networking', '--disable-extensions', '--hide-scrollbars',
    '--noerrdialogs', '--default-background-color=00000000', '--window-size=1024,1024',
    ('"--user-data-dir={0}"' -f (Join-Path $renderRoot 'export-profile')),
    ('"--screenshot={0}"' -f $renderedIcon), $svgUri
)
$renderer = Start-Process -FilePath $browser -ArgumentList $browserArguments -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $renderRoot 'export-out.log') `
    -RedirectStandardError (Join-Path $renderRoot 'export-error.log')
if (-not $renderer.WaitForExit(20000)) {
    Stop-Process -Id $renderer.Id
    throw 'The isolated SVG renderer did not finish in 20 seconds.'
}
if (-not (Test-Path -LiteralPath $renderedIcon)) { throw 'The SVG renderer did not create a PNG.' }

function Export-Square([System.Drawing.Image]$Source, [int]$Size, [string]$Path) {
    $bitmap = [System.Drawing.Bitmap]::new($Size, $Size)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $attributes = [System.Drawing.Imaging.ImageAttributes]::new()
    try {
        $graphics.Clear([System.Drawing.Color]::Transparent)
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $attributes.SetWrapMode([System.Drawing.Drawing2D.WrapMode]::TileFlipXY)
        $graphics.DrawImage($Source, [System.Drawing.Rectangle]::new(0, 0, $Size, $Size),
            0, 0, $Source.Width, $Source.Height, [System.Drawing.GraphicsUnit]::Pixel, $attributes)
        $bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $attributes.Dispose()
        $graphics.Dispose()
        $bitmap.Dispose()
    }
}

$avatar = [System.Drawing.Image]::FromFile((Join-Path $assetRoot 'masters/promptjig-avatar.png'))
$icon = [System.Drawing.Image]::FromFile($renderedIcon)
try {
    if ($avatar.Width -ne $avatar.Height) { throw 'The avatar master must be square.' }
    foreach ($size in @(512, 256, 192)) {
        $name = if ($size -eq 512) { 'promptjig-avatar.png' } else { "promptjig-avatar-$size.png" }
        Export-Square $avatar $size (Join-Path $assetRoot $name)
    }
    foreach ($size in @(512, 192, 64, 32)) {
        $name = if ($size -eq 512) { 'promptjig-icon.png' } else { "promptjig-icon-$size.png" }
        Export-Square $icon $size (Join-Path $assetRoot $name)
    }

    $sheet = [System.Drawing.Bitmap]::new(1000, 650)
    $graphics = [System.Drawing.Graphics]::FromImage($sheet)
    $titleFont = [System.Drawing.Font]::new('Segoe UI', 25, [System.Drawing.FontStyle]::Bold)
    $labelFont = [System.Drawing.Font]::new('Segoe UI', 16, [System.Drawing.FontStyle]::Bold)
    $detailFont = [System.Drawing.Font]::new('Segoe UI', 11)
    $ink = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml('#063D26'))
    try {
        $graphics.Clear([System.Drawing.ColorTranslator]::FromHtml('#F2F7F2'))
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
        $graphics.DrawString('PromptJig / Grinnotech Platform', $titleFont, $ink, 55, 22)
        $graphics.DrawImage($avatar, [System.Drawing.Rectangle]::new(55, 95, 400, 400))
        $graphics.DrawImage($icon, [System.Drawing.Rectangle]::new(545, 95, 400, 400))
        $graphics.DrawString('Avatar', $labelFont, $ink, 55, 510)
        $graphics.DrawString('Icon / SVG + transparent PNG', $labelFont, $ink, 545, 510)
        $graphics.DrawImage($avatar, [System.Drawing.Rectangle]::new(55, 560, 48, 48))
        $graphics.DrawImage($avatar, [System.Drawing.Rectangle]::new(140, 568, 32, 32))
        $graphics.DrawString('48 px       32 px', $detailFont, $ink, 55, 612)
        $graphics.DrawImage($icon, [System.Drawing.Rectangle]::new(545, 552, 64, 64))
        $graphics.DrawImage($icon, [System.Drawing.Rectangle]::new(650, 560, 48, 48))
        $graphics.DrawImage($icon, [System.Drawing.Rectangle]::new(740, 568, 32, 32))
        $graphics.DrawString('64 px          48 px         32 px', $detailFont, $ink, 545, 617)
        $sheet.Save((Join-Path $assetRoot 'preview.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $ink.Dispose()
        $detailFont.Dispose()
        $labelFont.Dispose()
        $titleFont.Dispose()
        $graphics.Dispose()
        $sheet.Dispose()
    }
} finally {
    $icon.Dispose()
    $avatar.Dispose()
}

Compress-Archive -LiteralPath $assetRoot -DestinationPath (Join-Path $renderRoot 'promptjig-branding.zip') -Force
Write-Output 'Exported avatar and icon PNGs, preview and branding ZIP.'
