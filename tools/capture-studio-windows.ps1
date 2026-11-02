# Launches a real Android Studio installation and records its actual execution events and pixels.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if ($env:OS -ne 'Windows_NT') { throw 'Windows is required.' }
$project = Split-Path -Parent $PSScriptRoot
$logs = Join-Path $project 'artifacts/windows/android-studio'
New-Item -ItemType Directory -Force $logs | Out-Null
$studioRoot = Join-Path $env:RUNNER_TEMP 'shoppingcart-studio'
$studioHome = Join-Path $studioRoot 'android-studio'
$studioExe = Join-Path $studioHome 'bin/studio64.exe'
if (-not (Test-Path $studioExe)) {
    $archive = Join-Path $env:RUNNER_TEMP 'android-studio-2024.2.1.12-windows.zip'
    Invoke-WebRequest 'https://dl.google.com/dl/android/studio/ide-zips/2024.2.1.12/android-studio-2024.2.1.12-windows.zip' -OutFile $archive
    Get-FileHash $archive -Algorithm SHA256 | Format-List | Out-String | Set-Content (Join-Path $logs 'download-sha256.txt')
    Expand-Archive $archive -DestinationPath $studioRoot -Force
    Remove-Item $archive
}
# Compile the small CI-only driver against the exact IDE installation being tested.
$build = Join-Path $studioRoot 'driver-build'
$classes = Join-Path $build 'classes'
$plugins = Join-Path $studioRoot 'plugins'
$pluginLib = Join-Path $plugins 'lab-verification/lib'
New-Item -ItemType Directory -Force $classes, $pluginLib, (Join-Path $classes 'META-INF') | Out-Null
$jars = @(Get-ChildItem (Join-Path $studioHome 'lib'), (Join-Path $studioHome 'plugins') -Recurse -Filter '*.jar' -File)
$classPath = ($jars.FullName | ForEach-Object { $_.Replace('\','/') }) -join ';'
$javac = Join-Path $studioHome 'jbr/bin/javac.exe'
$jar = Join-Path $studioHome 'jbr/bin/jar.exe'
$javap = Join-Path $studioHome 'jbr/bin/javap.exe'
$argumentsFile = Join-Path $build 'javac.args'
@(
    '--release', '17', '-encoding', 'UTF-8', '-classpath', ('"' + $classPath + '"'),
    '-d', ('"' + $classes.Replace('\','/') + '"'),
    ('"' + (Join-Path $PSScriptRoot 'studio-verification/LabStudioAutomation.java').Replace('\','/') + '"'),
    ('"' + (Join-Path $PSScriptRoot 'studio-verification/LabConsentAgent.java').Replace('\','/') + '"')
) | Set-Content $argumentsFile -Encoding utf8
& $javac ('@' + $argumentsFile) 2>&1 | Tee-Object (Join-Path $logs 'driver-compile.txt')
if ($LASTEXITCODE -ne 0) { throw 'IDE verification helper compilation failed.' }
# Keep the exact API signatures with the evidence for diagnosing IDE version differences.
@('-classpath', ('"' + $classPath + '"'), 'com.android.tools.idea.gradle.project.sync.GradleSyncState') |
    Set-Content (Join-Path $build 'javap.args') -Encoding utf8
& $javap ('@' + (Join-Path $build 'javap.args')) 2>&1 | Set-Content (Join-Path $logs 'sync-api.txt')
Copy-Item (Join-Path $PSScriptRoot 'studio-verification/plugin.xml') (Join-Path $classes 'META-INF/plugin.xml')
& $jar --create --file (Join-Path $pluginLib 'lab-verification.jar') -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'IDE verification plugin packaging failed.' }
$manifest = Join-Path $build 'agent-manifest.txt'
"Premain-Class: lab.verification.LabConsentAgent`r`n`r`n" | Set-Content $manifest -Encoding ascii
$agent = Join-Path $build 'lab-consent-agent.jar'
& $jar --create --file $agent --manifest $manifest -C $classes 'lab/verification/LabConsentAgent.class'
if ($LASTEXITCODE -ne 0) { throw 'Consent helper packaging failed.' }

$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
$deviceState = (& $adb -s emulator-5554 get-state 2>$null | Out-String).Trim()
$deviceReady = $LASTEXITCODE -eq 0 -and $deviceState -eq 'device'
$deviceState | Set-Content (Join-Path $logs 'device-before-ide.txt')
if ($deviceReady) {
    & $adb -s emulator-5554 shell am force-stop cn.edu.hnie.shoppingcart
    if ($LASTEXITCODE -ne 0) { throw 'Cannot stop the app before the IDE launch check.' }
    & $adb -s emulator-5554 logcat -c
    $beforePid = (& $adb -s emulator-5554 shell pidof cn.edu.hnie.shoppingcart 2>$null | Out-String).Trim()
    $beforePid | Set-Content (Join-Path $logs 'app-pid-before-ide.txt')
    if ($beforePid) { throw 'App must be stopped before verifying an IDE-initiated launch.' }
}
$props = Join-Path $studioRoot 'lab-studio.properties'
$config = (Join-Path $studioRoot 'config').Replace('\','/')
$system = (Join-Path $studioRoot 'system').Replace('\','/')
@(
    "idea.config.path=$config", "idea.system.path=$system",
    ('idea.plugins.path=' + $plugins.Replace('\','/')),
    'disable.android.first.run=true', 'idea.initially.ask.config=false',
    'idea.trust.all.projects=true', 'jb.consents.confirmation.enabled=false',
    'ide.show.tips.on.startup.default.value=false'
) | Set-Content $props -Encoding ascii
$vmOptions = Join-Path $studioRoot 'lab-studio.vmoptions'
Copy-Item (Join-Path $studioHome 'bin/studio64.exe.vmoptions') $vmOptions
@(
    ('-javaagent:' + $agent.Replace('\','/')),
    ('-Dlab.evidence.path=' + $logs.Replace('\','/')),
    ('-Dlab.run.enabled=' + $deviceReady.ToString().ToLowerInvariant())
) | Add-Content $vmOptions -Encoding ascii
$env:STUDIO_PROPERTIES = $props
$env:STUDIO_VM_OPTIONS = $vmOptions
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
$passed = $false
try {
    foreach ($index in 1..120) {
        Start-Sleep -Seconds 5
        if ($index % 9 -eq 0) { Capture-Desktop ("studio-$index.png") }
        if ($studio.HasExited) { throw "Android Studio exited early: $($studio.ExitCode)." }
        $eventsFile = Join-Path $logs 'ide-events.txt'
        if (-not (Test-Path $eventsFile)) { continue }
        $events = Get-Content $eventsFile -Raw
        if ($events -match 'PROCESS_NOT_STARTED') { throw 'Android Studio reported that app execution did not start.' }
        if ($events -match 'SYNC_READY' -and -not $deviceReady) { throw 'IDE sync completed but no Android device is available for Run.' }
        if ($events -notmatch 'PROCESS_STARTED') { continue }
        $afterPid = (& $adb -s emulator-5554 shell pidof cn.edu.hnie.shoppingcart 2>$null | Out-String).Trim()
        if ($LASTEXITCODE -eq 0 -and $afterPid -match '^\d+( \d+)*$') {
            $afterPid | Set-Content (Join-Path $logs 'app-pid-after-ide.txt')
            Start-Sleep -Seconds 8
            Capture-Desktop 'studio-after-run.png'
            & $adb -s emulator-5554 shell screencap -p /data/local/tmp/lab_after_ide.png
            if ($LASTEXITCODE -ne 0) { throw 'Device screenshot failed.' }
            & $adb -s emulator-5554 pull /data/local/tmp/lab_after_ide.png (Join-Path $logs 'device-after-ide-run.png')
            if ($LASTEXITCODE -ne 0) { throw 'Device screenshot download failed.' }
            & $adb -s emulator-5554 logcat -d -s CartLifecycle:I ActivityManager:I '*:S' |
                Set-Content (Join-Path $logs 'logcat-after-ide-run.txt')
            $passed = $true
            break
        }
    }
    if (-not $passed) { throw 'IDE sync and actual app execution were not confirmed before timeout.' }
    @(
        'Windows Android Studio 2024.2.1.12: real project sync and IDE run configuration execution confirmed.'
        'App process absent before IDE launch and present after IDE PROCESS_STARTED event.'
        'The CI-only plugin invokes Android Studio execution APIs; it is not packaged into the app.'
        'Review original screenshots, ide-events.txt, idea.log and device logs together.'
        ('Recorded at: ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz'))
    ) | Set-Content (Join-Path $logs 'summary.txt') -Encoding utf8
} finally {
    Capture-Desktop 'studio-final.png'
    Get-Process studio64 -ErrorAction SilentlyContinue | Select-Object Id,MainWindowTitle,MainWindowHandle |
        Format-List | Out-String | Set-Content (Join-Path $logs 'windows.txt')
    $ideaLog = Join-Path $system 'log/idea.log'
    if (Test-Path $ideaLog) { Copy-Item $ideaLog (Join-Path $logs 'idea.log') }
}
