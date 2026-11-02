# CI bootstrap for a disposable Windows runner. No successful result is assumed.
[CmdletBinding()]
param([switch]$DeviceTests)
$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'Windows is required.' }
$project = Split-Path -Parent $PSScriptRoot
$logs = Join-Path $project 'artifacts/windows/bootstrap'
New-Item -ItemType Directory -Force $logs | Out-Null
if (-not $env:ANDROID_HOME) { throw 'ANDROID_HOME was not supplied by the Windows runner.' }
if (-not $env:JAVA_HOME) { throw 'JAVA_HOME must point to JDK 17.' }
$sdk = $env:ANDROID_HOME
$sdkManager = @(Get-ChildItem (Join-Path $sdk 'cmdline-tools') -Filter sdkmanager.bat -Recurse | Sort-Object FullName -Descending)[0].FullName
$avdManager = Join-Path (Split-Path $sdkManager -Parent) 'avdmanager.bat'
$packages = @('platforms;android-35','build-tools;34.0.0','platform-tools')
$imagePackage = 'system-images;android-28;google_apis;x86'
if ($DeviceTests) { $packages += @('emulator',$imagePackage) }
$responses = 1..100 | ForEach-Object { 'y' }
$responses | & $sdkManager --licenses 2>&1 | Tee-Object (Join-Path $logs 'sdk-licenses.txt')
if ($LASTEXITCODE -ne 0) { throw 'SDK license command failed.' }
& $sdkManager --install @packages 2>&1 | Tee-Object (Join-Path $logs 'sdk-install.txt')
if ($LASTEXITCODE -ne 0) { throw 'SDK component installation failed.' }

# Preserve independent Windows build evidence even if emulator setup fails.
& (Join-Path $PSScriptRoot 'verify-windows.ps1') -JavaHome $env:JAVA_HOME -SdkPath $sdk
if ($LASTEXITCODE -ne 0) { throw 'Windows build verification failed.' }

if ($DeviceTests) {
    $env:ANDROID_AVD_HOME = Join-Path $env:RUNNER_TEMP 'shoppingcart-avd'
    New-Item -ItemType Directory -Force $env:ANDROID_AVD_HOME | Out-Null
    'no' | & $avdManager create avd --force --name LabCartWindows --package $imagePackage 2>&1 | Tee-Object (Join-Path $logs 'avd-create.txt')
    if ($LASTEXITCODE -ne 0) { throw 'AVD creation failed.' }
    $config = Join-Path $env:ANDROID_AVD_HOME 'LabCartWindows.avd/config.ini'
    $settings = @(
        'hw.lcd.width=720'
        'hw.lcd.height=1280'
        'hw.lcd.density=320'
        'hw.keyboard=yes'
        'hw.gpu.enabled=yes'
        'hw.gpu.mode=swiftshader_indirect'
        'disk.dataPartition.size=1G'
    )
    $configLines = @(Get-Content $config)
    foreach ($setting in $settings) {
        $key = $setting.Split('=')[0]
        $configLines = @($configLines | Where-Object { -not $_.StartsWith($key + '=') })
        $configLines += $setting
    }
    $configLines | Set-Content $config -Encoding ascii
    $emulator = Join-Path $sdk 'emulator/emulator.exe'
    $adb = Join-Path $sdk 'platform-tools/adb.exe'
    & $adb start-server
    if ($LASTEXITCODE -ne 0) { throw 'ADB server could not start.' }
    $emu = Start-Process -FilePath $emulator -ArgumentList @(
        '-avd','LabCartWindows','-port','5554','-accel','off',
        '-gpu','swiftshader_indirect','-no-window','-no-audio','-no-snapshot',
        '-feature','-Vulkan','-no-metrics','-no-boot-anim','-memory','2048','-cores','2',
        '-camera-back','none','-camera-front','none'
    ) -PassThru -RedirectStandardOutput (Join-Path $logs 'emulator-stdout.txt') -RedirectStandardError (Join-Path $logs 'emulator-stderr.txt')
    $emu.Id | Set-Content (Join-Path $logs 'emulator-pid.txt')
    $deadline = (Get-Date).AddMinutes(25)
    $booted = $false
    do {
        if ($emu.HasExited) { throw "Emulator stopped before boot; exit=$($emu.ExitCode)." }
        $boot = & $adb -s emulator-5554 shell getprop sys.boot_completed 2>$null
        if ($LASTEXITCODE -eq 0 -and "$boot".Trim() -eq '1') { $booted = $true; break }
        Start-Sleep -Seconds 5
    } while ((Get-Date) -lt $deadline)
    if (-not $booted) { throw 'Android did not finish booting within 25 minutes.' }
    & $adb -s emulator-5554 shell input keyevent 82
    foreach ($setting in @('window_animation_scale','transition_animation_scale','animator_duration_scale')) {
        & $adb -s emulator-5554 shell settings put global $setting 0
    }
    & (Join-Path $PSScriptRoot 'verify-windows.ps1') -JavaHome $env:JAVA_HOME -SdkPath $sdk -DeviceTests -Serial emulator-5554
}
if ($LASTEXITCODE -ne 0) { throw 'Windows verification failed; inspect the saved evidence.' }
