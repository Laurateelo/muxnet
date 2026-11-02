# CI bootstrap for a disposable Windows runner. No successful result is assumed.
[CmdletBinding()]
param([switch]$DeviceTests, [ValidateSet('All','Build','Device')][string]$Phase = 'All')
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
if ($Phase -ne 'Device') {
$responses = 1..100 | ForEach-Object { 'y' }
$responses | & $sdkManager --licenses 2>&1 | Tee-Object (Join-Path $logs 'sdk-licenses.txt')
if ($LASTEXITCODE -ne 0) { throw 'SDK license command failed.' }
& $sdkManager --install @packages 2>&1 | Tee-Object (Join-Path $logs 'sdk-install.txt')
if ($LASTEXITCODE -ne 0) { throw 'SDK component installation failed.' }
}

# Preserve independent Windows build evidence even if emulator setup fails.
if ($Phase -ne 'Device') {
    & (Join-Path $PSScriptRoot 'verify-windows.ps1') -JavaHome $env:JAVA_HOME -SdkPath $sdk
    if ($LASTEXITCODE -ne 0) { throw 'Windows build verification failed.' }
}

if ($DeviceTests -and $Phase -ne 'Build') {
    $env:ANDROID_AVD_HOME = Join-Path $env:RUNNER_TEMP 'shoppingcart-avd'
    New-Item -ItemType Directory -Force $env:ANDROID_AVD_HOME | Out-Null
    if ($env:GITHUB_ENV) { ('ANDROID_AVD_HOME=' + $env:ANDROID_AVD_HOME) | Add-Content $env:GITHUB_ENV }
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
    # Use a pinned official emulator release after the newer Windows TCG engine crashed.
    $emulatorArchive = Join-Path $env:RUNNER_TEMP 'emulator-windows_x64-13610412.zip'
    Invoke-WebRequest 'https://edgedl.me.gvt1.com/edgedl/android/repository/emulator-windows_x64-13610412.zip' -OutFile $emulatorArchive
    $actualHash = (Get-FileHash $emulatorArchive -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne '0865071aecf3d90a5b967cb6cb0cd48bea0a58a26ef4953b32037671c129b7e9') { throw 'Official emulator archive checksum mismatch.' }
    $actualHash | Set-Content (Join-Path $logs 'emulator-archive-sha256.txt')
    $packageFile = Join-Path $sdk 'emulator/package.xml'
    [xml]$package = Get-Content $packageFile -Raw
    foreach ($part in @(@('major','35'),@('minor','6'),@('micro','11'))) {
        $node = $package.SelectSingleNode("//*[local-name()='localPackage']/*[local-name()='revision']/*[local-name()='$($part[0])']")
        if ($null -eq $node) { throw 'Emulator package metadata is incomplete.' }
        $node.InnerText = $part[1]
    }
    Remove-Item (Join-Path $sdk 'emulator') -Recurse -Force
    Expand-Archive $emulatorArchive -DestinationPath $sdk -Force
    $package.Save($packageFile)
    Remove-Item $emulatorArchive
    Get-Content (Join-Path $sdk 'emulator/source.properties') | Set-Content (Join-Path $logs 'emulator-version.txt')
    $emulator = Join-Path $sdk 'emulator/emulator.exe'
    $env:ANDROID_I_WANT_MY_TCG = 'yes'
    & $emulator -accel-check 2>&1 | Tee-Object (Join-Path $logs 'acceleration.txt')
    $acceleration = if ($LASTEXITCODE -eq 0) { 'auto' } else { 'off' }
    $adb = Join-Path $sdk 'platform-tools/adb.exe'
    & $adb start-server
    if ($LASTEXITCODE -ne 0) { throw 'ADB server could not start.' }
    $emu = Start-Process -FilePath $emulator -ArgumentList @(
        '-avd','LabCartWindows','-port','5554','-accel',$acceleration,
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
