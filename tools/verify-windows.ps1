# Requires Windows PowerShell 5.1 or PowerShell 7 on Windows.
# -DeviceTests resets ONLY this experiment app's cart to test fixtures.
[CmdletBinding()]
param(
    [switch]$DeviceTests,
    [string]$Serial = '',
    [string]$SdkPath = '',
    [string]$JavaHome = '',
    [string]$ResultDirectory = ''
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') {
    throw 'This verification must run on Windows. A non-Windows run is not Windows evidence.'
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$resultDir = if ($ResultDirectory) { $ResultDirectory } else { Join-Path $projectRoot ('artifacts\windows\' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$utf8 = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
New-Item -ItemType Directory -Path $resultDir -Force | Out-Null

function Write-Utf8([string]$Path, [string]$Value) {
    [System.IO.File]::WriteAllText($Path, $Value, $utf8)
}

function Invoke-Checked {
    param([string]$Exe, [string[]]$ArgumentList, [string]$LogName)
    $oldPreference = $ErrorActionPreference
    try {
        # Windows PowerShell represents native stderr as ErrorRecord objects.
        # Record stderr and determine success from the native exit code.
        $ErrorActionPreference = 'Continue'
        $lines = @(& $Exe @ArgumentList 2>&1 | ForEach-Object { $_.ToString() })
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $oldPreference
    }
    $value = $lines -join [Environment]::NewLine
    Write-Utf8 (Join-Path $resultDir $LogName) $value
    Write-Host $value
    if ($exitCode -ne 0) {
        throw "$Exe failed with exit code $exitCode. See $LogName."
    }
    return $value
}

function ConvertTo-PropertyValue([string]$Value) {
    $builder = New-Object System.Text.StringBuilder
    foreach ($character in $Value.ToCharArray()) {
        $number = [int]$character
        if ($number -gt 126 -or $number -lt 32) {
            [void]$builder.Append(('\u{0:x4}' -f $number))
        } elseif ('\:= #!'.Contains([string]$character)) {
            [void]$builder.Append('\').Append($character)
        } else {
            [void]$builder.Append($character)
        }
    }
    return $builder.ToString()
}

$originalLocation = Get-Location
try {
    Set-Location $projectRoot
    if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
    if (-not $JavaHome) {
        $studioJbr = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
        if (Test-Path (Join-Path $studioJbr 'bin\java.exe')) { $JavaHome = $studioJbr }
    }
    if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\java.exe'))) {
        throw 'Install JDK 17 and pass -JavaHome or set JAVA_HOME. See the Windows guide.'
    }
    $javaVersion = Invoke-Checked (Join-Path $JavaHome 'bin\java.exe') @('-version') 'java-version.txt'
    if ($javaVersion -notmatch 'version\s+"17(?:\.|\")') {
        throw 'Use JDK 17 for this recorded project configuration; pass -JavaHome with its folder.'
    }
    $env:JAVA_HOME = $JavaHome
    if (-not $SdkPath) { $SdkPath = $env:ANDROID_HOME }
    if (-not $SdkPath) { $SdkPath = $env:ANDROID_SDK_ROOT }
    if (-not $SdkPath) { $SdkPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $SdkPath = (Resolve-Path $SdkPath).Path
    foreach ($required in @('platforms\android-35\android.jar', 'build-tools\34.0.0\aapt2.exe', 'platform-tools\adb.exe')) {
        if (-not (Test-Path (Join-Path $SdkPath $required))) {
            throw "SDK component missing: $required. Install it in Android Studio SDK Manager."
        }
    }
    $env:ANDROID_HOME = $SdkPath
    $env:ANDROID_SDK_ROOT = $SdkPath
    if (-not (Test-Path 'local.properties')) {
        Write-Utf8 (Join-Path $projectRoot 'local.properties') ('sdk.dir=' + (ConvertTo-PropertyValue $SdkPath) + "`n")
    }
    $osInfo = (Get-CimInstance Win32_OperatingSystem).Caption
    Write-Utf8 (Join-Path $resultDir 'environment.txt') (@(
        ('Executed at: ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz'))
        ('Host: ' + $osInfo)
        ('PowerShell: ' + $PSVersionTable.PSVersion.ToString())
        ('JAVA_HOME: ' + $JavaHome)
        ('Android SDK: ' + $SdkPath)
        ('Project: ' + $projectRoot)
        'Android Studio GUI import/run must also be checked manually; see the Windows guide.'
    ) -join "`r`n")

    $gradleArguments = @('--no-daemon', '--console=plain', 'assembleDebug', 'testDebugUnitTest', 'lintDebug')
    if ($DeviceTests) { $gradleArguments += 'assembleDebugAndroidTest' }
    $null = Invoke-Checked (Join-Path $projectRoot 'gradlew.bat') $gradleArguments 'build-results.txt'
    Copy-Item 'app\build\outputs\apk\debug\app-debug.apk' $resultDir
    Copy-Item 'app\build\test-results\testDebugUnitTest' (Join-Path $resultDir 'unit-tests') -Recurse
    foreach ($lintFile in Get-ChildItem 'app\build\reports\lint-results-debug.*' -File) {
        Copy-Item $lintFile.FullName $resultDir
    }
    $unitReports = @(Get-ChildItem (Join-Path $resultDir 'unit-tests') -Filter 'TEST-*.xml')
    $unitCount = 0
    foreach ($report in $unitReports) {
        [xml]$unit = Get-Content $report.FullName -Raw -Encoding UTF8
        if ([int]$unit.testsuite.failures -ne 0 -or [int]$unit.testsuite.errors -ne 0) {
            throw 'JUnit reports contain failures or errors.'
        }
        $unitCount += [int]$unit.testsuite.tests
    }
    if ($unitCount -ne 8) { throw "Expected 8 JUnit tests; found $unitCount." }
    $summary = "Windows command-line build, 8 JUnit tests and lint: PASS`r`n"

    if ($DeviceTests) {
        Write-Host 'Device tests will reset the cart in cn.edu.hnie.shoppingcart to test fixtures.'
        $adb = Join-Path $SdkPath 'platform-tools\adb.exe'
        $devices = Invoke-Checked $adb @('devices') 'devices.txt'
        $ready = @($devices -split '\r?\n' | Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
        if (-not $Serial) {
            if ($ready.Count -ne 1) { throw 'Connect exactly one authorized device, or specify -Serial.' }
            $Serial = $ready[0]
        }
        if ($ready -notcontains $Serial) { throw "Device $Serial is not ready/authorized." }
        $deviceArgs = @('-s', $Serial)
        $api = Invoke-Checked $adb ($deviceArgs + @('shell', 'getprop', 'ro.build.version.sdk')) 'device-api.txt'
        if ([int]$api.Trim() -lt 26) { throw 'The device must run Android 8.0 / API 26 or above.' }
        $null = Invoke-Checked $adb ($deviceArgs + @('install', '-r', 'app\build\outputs\apk\debug\app-debug.apk')) 'install-app.txt'
        $null = Invoke-Checked $adb ($deviceArgs + @('install', '-r', 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk')) 'install-test.txt'
        $testResult = Invoke-Checked $adb ($deviceArgs + @('shell', 'am', 'instrument', '-w', 'cn.edu.hnie.shoppingcart.test/cn.edu.hnie.shoppingcart.CartInstrumentation')) 'instrumentation-results.txt'
        if ($testResult -notmatch 'RESULT: PASS; checks=21' -or $testResult -match '(?m)^FAIL:') {
            throw 'The instrumentation did not report all 21 checks passing.'
        }
        $null = Invoke-Checked $adb ($deviceArgs + @('pull', '/sdcard/Android/data/cn.edu.hnie.shoppingcart/files/evidence', $resultDir)) 'pull-evidence.txt'
        $null = Invoke-Checked $adb ($deviceArgs + @('shell', 'am', 'force-stop', 'cn.edu.hnie.shoppingcart')) 'force-stop.txt'
        $launch = Invoke-Checked $adb ($deviceArgs + @('shell', 'am', 'start', '-W', '-n', 'cn.edu.hnie.shoppingcart/.MainActivity')) 'restart-launch.txt'
        if ($launch -notmatch 'Status: ok') { throw 'Activity restart did not report Status: ok.' }
        Start-Sleep -Seconds 2
        $preferences = Invoke-Checked $adb ($deviceArgs + @('shell', 'run-as', 'cn.edu.hnie.shoppingcart', 'cat', 'shared_prefs/shopping_cart.xml')) 'shopping_cart.xml'
        [xml]$preferencesXml = $preferences
        $jsonNode = $preferencesXml.SelectSingleNode('/map/string[@name="products_json"]')
        if ($null -eq $jsonNode) { throw 'Persistent products_json was not found.' }
        $products = @($jsonNode.InnerText | ConvertFrom-Json)
        if ($products.Count -ne 1 -or $products[0].priceCents -ne 1320 -or $products[0].quantity -ne 3) {
            throw 'Saved products differ from the expected restart fixture.'
        }
        $remoteScreenshot = '/data/local/tmp/shoppingcart_windows_restart.png'
        $null = Invoke-Checked $adb ($deviceArgs + @('shell', 'screencap', '-p', $remoteScreenshot)) 'restart-screenshot.txt'
        $null = Invoke-Checked $adb ($deviceArgs + @('pull', $remoteScreenshot, (Join-Path $resultDir 'restart.png'))) 'pull-restart.txt'
        $null = Invoke-Checked $adb ($deviceArgs + @('shell', 'rm', $remoteScreenshot)) 'cleanup-screenshot.txt'
        $summary += "Android instrumentation: PASS (21 checks)`r`nForce-stop/restart persistence: PASS`r`n"
    } else {
        $summary += "Device tests and restart persistence: NOT RUN (use -DeviceTests)`r`n"
    }
    $summary += "Android Studio GUI import/run: MANUAL VERIFICATION REQUIRED`r`n"
    Write-Utf8 (Join-Path $resultDir 'summary.txt') $summary
    Write-Host $summary
    Write-Host "Evidence folder: $resultDir"
} catch {
    Write-Utf8 (Join-Path $resultDir 'failure.txt') $_.ToString()
    Write-Error $_ -ErrorAction Continue
    exit 1
} finally {
    Set-Location $originalLocation
}
