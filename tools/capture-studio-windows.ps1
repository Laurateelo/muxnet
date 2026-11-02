# Captures real Windows desktop pixels. A screenshot alone does not prove IDE sync/run success.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'Windows is required.' }
$project = Split-Path -Parent $PSScriptRoot
$logs = Join-Path $project 'artifacts/windows/android-studio'
New-Item -ItemType Directory -Force $logs | Out-Null
$studioRoot = Join-Path $env:RUNNER_TEMP 'shoppingcart-studio'
$studioExe = Join-Path $studioRoot 'android-studio/bin/studio64.exe'
if (-not (Test-Path $studioExe)) {
    $archive = Join-Path $env:RUNNER_TEMP 'android-studio-2024.2.1.12-windows.zip'
    $url = 'https://dl.google.com/dl/android/studio/ide-zips/2024.2.1.12/android-studio-2024.2.1.12-windows.zip'
    Invoke-WebRequest $url -OutFile $archive
    Get-FileHash $archive -Algorithm SHA256 | Format-List | Out-String | Set-Content (Join-Path $logs 'download-sha256.txt')
    Expand-Archive $archive -DestinationPath $studioRoot -Force
    Remove-Item $archive
}
$props = Join-Path $studioRoot 'lab-studio.properties'
$config = (Join-Path $studioRoot 'config').Replace('\','/')
$system = (Join-Path $studioRoot 'system').Replace('\','/')
@(
    "idea.config.path=$config"
    "idea.system.path=$system"
    'disable.android.first.run=true'
    'idea.initially.ask.config=false'
    'ide.show.tips.on.startup=false'
) | Set-Content $props -Encoding ascii
$env:STUDIO_PROPERTIES = $props
$studio = Start-Process -FilePath $studioExe -ArgumentList ('"' + $project + '"') -PassThru
$studio.Id | Set-Content (Join-Path $logs 'studio-pid.txt')
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
function Capture-Desktop([string]$Name) {
    $bounds = [System.Windows.Forms.SystemInformation]::VirtualScreen
    if ($bounds.Width -lt 1 -or $bounds.Height -lt 1) { throw 'No graphical desktop is available.' }
    $bitmap = New-Object System.Drawing.Bitmap($bounds.Width,$bounds.Height)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.CopyFromScreen($bounds.Left,$bounds.Top,0,0,$bounds.Size)
        $bitmap.Save((Join-Path $logs $Name),[System.Drawing.Imaging.ImageFormat]::Png)
    } finally { $graphics.Dispose(); $bitmap.Dispose() }
}
foreach ($index in 1..4) {
    Start-Sleep -Seconds 45
    Capture-Desktop ("studio-$index.png")
    Get-Process studio64 -ErrorAction SilentlyContinue | Select-Object Id,MainWindowTitle,MainWindowHandle | Format-List | Out-String | Add-Content (Join-Path $logs 'windows.txt')
}
$ideaLog = Join-Path $system 'log/idea.log'
if (Test-Path $ideaLog) { Copy-Item $ideaLog (Join-Path $logs 'idea.log') }
@(
    'Android Studio process launched; actual desktop captures were saved.'
    'Review screenshots and idea.log before claiming successful project import or Gradle sync.'
    'IDE Run-button execution is NOT automatically verified by this script.'
    ('Recorded at: ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz'))
) | Set-Content (Join-Path $logs 'review-required.txt') -Encoding utf8
