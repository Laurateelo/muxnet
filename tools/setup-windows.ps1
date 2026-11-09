[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if ($env:OS -ne 'Windows_NT') { throw 'This workflow requires Windows.' }
$repo = Split-Path -Parent $PSScriptRoot
$base = 'D:\lab3-ci'
$evidence = Join-Path $base 'evidence'
$cache = 'C:\lab2-downloads'
$project = 'D:\workspace\lab3-demo'
New-Item -ItemType Directory -Force $base,$evidence,$cache,$project | Out-Null
Start-Transcript -Path (Join-Path $evidence 'setup-transcript.txt')
function Download([string]$Url,[string]$Path) {
  if (Test-Path $Path) { Write-Host "Using cached $(Split-Path -Leaf $Path)"; return }
  Write-Host "Downloading $Url"
  Invoke-WebRequest $Url -OutFile ($Path+'.partial') -MaximumRetryCount 3 -RetryIntervalSec 5
  Move-Item ($Path+'.partial') $Path
}
Copy-Item (Join-Path $repo 'pom.xml') $project
Copy-Item (Join-Path $repo 'src') $project -Recurse -Force
Copy-Item (Join-Path $repo 'tests') $project -Recurse -Force
# Keep the shipped collection intact. Resolve the local base URL in the copy
# imported into Offline Space, where no shared environment is needed.
$collection = Get-Content "$project/tests/apifox_import.postman_collection.json" -Raw -Encoding utf8
$collection.Replace('{{baseUrl}}','http://localhost:8080') | Set-Content "$project/lab3-native-requests.json" -Encoding utf8
Get-CimInstance Win32_OperatingSystem | Select-Object -Property @('Caption', 'Version', 'BuildNumber', 'OSArchitecture') | ConvertTo-Json | Set-Content (Join-Path $evidence 'windows-version.json')
& java -version 2>&1 | Out-File (Join-Path $evidence 'java-version.txt')
& mvn -version 2>&1 | Out-File (Join-Path $evidence 'maven-version.txt')

# Official redistributable and ZIP installations, in this disposable Windows VM only.
$vc = Join-Path $cache 'vc_redist.x64.exe'
Download 'https://aka.ms/vs/17/release/vc_redist.x64.exe' $vc
$vcProcess = Start-Process $vc -ArgumentList '/install /quiet /norestart' -Wait -PassThru
if ($vcProcess.ExitCode -notin 0,1638,3010) { throw "VC++ runtime installation exit $($vcProcess.ExitCode)" }
"VC++ redistributable exit: $($vcProcess.ExitCode)" | Set-Content (Join-Path $evidence 'vc-runtime.txt')

$mysqlZip = Join-Path $cache 'mysql-8.4.11-winx64.zip'
Download 'https://cdn.mysql.com/archives/mysql-8.4/mysql-8.4.11-winx64.zip' $mysqlZip
Expand-Archive $mysqlZip -DestinationPath $base -Force
Move-Item (Join-Path $base 'mysql-8.4.11-winx64') 'D:\mysql'
& 'D:\mysql\bin\mysqld.exe' --version | Set-Content (Join-Path $evidence 'mysql-version.txt')
Get-Service -Name '*mysql*' -ErrorAction SilentlyContinue | Where-Object Status -eq Running | Stop-Service
& 'D:\mysql\bin\mysqld.exe' '--no-defaults' '--initialize-insecure' '--console' '--basedir=D:\mysql' '--datadir=D:\mysql\data' 2>&1 | Tee-Object (Join-Path $evidence 'mysql-initialize.txt')
if ($LASTEXITCODE -ne 0) { throw 'MySQL initialization failed.' }
$mysqlCmd = '@echo off'+"`r`n"+'title Lab3 MySQL 8.4.11'+"`r`n"+'chcp 65001'+"`r`n"+'cd /d D:\mysql\bin'+"`r`n"+'mysqld --no-defaults --console --basedir=D:\mysql --datadir=D:\mysql\data --port=3306 --bind-address=127.0.0.1 --mysqlx-bind-address=127.0.0.1'+"`r`n"
[IO.File]::WriteAllText((Join-Path $base 'start-mysql.cmd'),$mysqlCmd,[Text.Encoding]::ASCII)
Start-Process cmd.exe -ArgumentList '/k D:\lab3-ci\start-mysql.cmd' -WindowStyle Normal
foreach ($n in 1..60) {
  & 'D:\mysql\bin\mysqladmin.exe' '--protocol=tcp' '-h127.0.0.1' '-u' 'root' 'ping' 2>$null
  if ($LASTEXITCODE -eq 0) { break }
  Start-Sleep -Seconds 1
}
if ($LASTEXITCODE -ne 0) { throw 'MySQL did not become ready.' }
& 'D:\mysql\bin\mysql.exe' '--default-character-set=utf8mb4' '-uroot' '-e' 'SELECT VERSION();'

# Build the supplied Maven project. Dependency copies are also used by the IDE module.
$settings = Join-Path $base 'maven-settings.xml'
'<settings><mirrors><mirror><id>central</id><mirrorOf>*</mirrorOf><url>https://repo.maven.apache.org/maven2</url></mirror></mirrors></settings>' | Set-Content $settings
Push-Location $project
& mvn -B -s $settings '-Dmaven.repo.local=D:\lab3-ci\m2' '-DskipTests' 'package' 'dependency:copy-dependencies' '-DoutputDirectory=target/dependency' 2>&1 | Tee-Object (Join-Path $evidence 'maven-build.txt')
if ($LASTEXITCODE -ne 0) { throw 'Maven build failed.' }
Copy-Item 'target/lab3-demo-1.0.jar' $evidence
Pop-Location

# Use separate schemas for the 29-case replay and the @Valid comparison. The
# lab3 schema is still fresh when the IDE starts the desktop demonstration.
$env:PYTHONIOENCODING = 'utf-8'
& python "$PSScriptRoot/verify-windows.py" $project $evidence
if ($LASTEXITCODE -ne 0) { throw 'Windows HTTP or database verification failed.' }

$ideaZip = Join-Path $cache 'ideaIC-2022.3.3.win.zip'
Download 'https://download.jetbrains.com/idea/ideaIC-2022.3.3.win.zip' $ideaZip
$idea = Join-Path $base 'idea'
Expand-Archive $ideaZip -DestinationPath $idea -Force
if (-not (Test-Path (Join-Path $idea 'bin/idea64.exe'))) {
  $found = Get-ChildItem $idea -Recurse -Filter idea64.exe | Select-Object -First 1
  if (-not $found) { throw 'IDEA executable missing.' }
  $idea = Split-Path -Parent (Split-Path -Parent $found.FullName)
}
$config = Join-Path $base 'idea-config'
$system = Join-Path $base 'idea-system'
$plugins = Join-Path $base 'idea-plugins'
$classes = Join-Path $base 'driver-classes'
New-Item -ItemType Directory -Force "$config/options","$plugins/lab3/lib","$classes/META-INF","$project/.idea" | Out-Null
$jars = @(Get-ChildItem "$idea/lib","$idea/plugins/java" -Recurse -File -Filter '*.jar')
$classPath = ($jars.FullName | ForEach-Object { $_.Replace('\','/') }) -join ';'
$argFile = Join-Path $base 'javac.args'
$argsText = @('--release','17','-proc:none','-encoding','UTF-8','-classpath',('"'+$classPath+'"'),'-d',('"'+$classes.Replace('\','/')+'"'),('"'+(Join-Path $PSScriptRoot 'idea-driver/LabDriver.java').Replace('\','/')+'"')) -join "`n"
[IO.File]::WriteAllText($argFile,$argsText,(New-Object Text.UTF8Encoding($false)))
& "$env:JAVA_HOME/bin/javac.exe" ('@'+$argFile) 2>&1 | Tee-Object (Join-Path $evidence 'idea-driver-build.txt')
if ($LASTEXITCODE -ne 0) { throw 'IDE driver compilation failed.' }
Copy-Item "$PSScriptRoot/idea-driver/plugin.xml" "$classes/META-INF/plugin.xml"
& "$env:JAVA_HOME/bin/jar.exe" --create --file "$plugins/lab3/lib/lab3-driver.jar" -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'IDE driver packaging failed.' }
$jdk = $env:JAVA_HOME.Replace('\','/')
$sdkRoots = (Get-ChildItem "$env:JAVA_HOME/jmods" -Filter '*.jmod' | ForEach-Object { '<root url="jrt://'+$jdk+'!/'+$_.BaseName+'" type="simple"/>' }) -join ''
$sdkXml = '<application><component name="ProjectJdkTable"><jdk version="2"><name value="Lab JDK 17"/><type value="JavaSDK"/><version value="java version &quot;17&quot;"/><homePath value="'+$jdk+'"/><roots><annotationsPath><root type="composite"/></annotationsPath><classPath><root type="composite">'+$sdkRoots+'</root></classPath><javadocPath><root type="composite"/></javadocPath><sourcePath><root type="composite"/></sourcePath></roots></jdk></component></application>'
$sdkXml | Set-Content "$config/options/jdk.table.xml"
'<project version="4"><component name="ProjectRootManager" version="2" languageLevel="JDK_17" project-jdk-name="Lab JDK 17" project-jdk-type="JavaSDK"><output url="file://$PROJECT_DIR$/target/classes"/></component></project>' | Set-Content "$project/.idea/misc.xml"
'<project version="4"><component name="ProjectModuleManager"><modules><module fileurl="file://$PROJECT_DIR$/lab3-demo.iml" filepath="$PROJECT_DIR$/lab3-demo.iml"/></modules></component></project>' | Set-Content "$project/.idea/modules.xml"
$dependencies = Get-ChildItem "$project/target/dependency" -Filter '*.jar' | ForEach-Object { '<orderEntry type="module-library"><library><CLASSES><root url="jar://$MODULE_DIR$/target/dependency/'+$_.Name+'!/"/></CLASSES><JAVADOC/><SOURCES/></library></orderEntry>' }
$moduleXml = '<module type="JAVA_MODULE" version="4"><component name="NewModuleRootManager" inherit-compiler-output="false"><output url="file://$MODULE_DIR$/target/classes"/><output-test url="file://$MODULE_DIR$/target/test-classes"/><exclude-output/><content url="file://$MODULE_DIR$"><sourceFolder url="file://$MODULE_DIR$/src/main/java" isTestSource="false"/><sourceFolder url="file://$MODULE_DIR$/src/main/resources" type="java-resource"/><excludeFolder url="file://$MODULE_DIR$/target"/></content><orderEntry type="inheritedJdk"/><orderEntry type="sourceFolder" forTests="false"/>'+($dependencies -join '')+'</component></module>'
$moduleXml | Set-Content "$project/lab3-demo.iml"
$props = Join-Path $base 'lab-idea.properties'
@("idea.config.path=$($config.Replace('\','/'))","idea.system.path=$($system.Replace('\','/'))","idea.plugins.path=$($plugins.Replace('\','/'))",'idea.initially.ask.config=false','ide.show.tips.on.startup.default.value=false') | Set-Content $props
$env:IDEA_PROPERTIES = $props
Start-Process "$idea/bin/idea64.exe" -ArgumentList ('"'+$project+'"') -PassThru | Select-Object Id | ConvertTo-Json | Set-Content (Join-Path $evidence 'idea-launch.json')

$apifoxZip = Join-Path $cache 'Apifox-windows-latest.zip'
Download 'https://file-assets.apifox.com/download/Apifox-windows-latest.zip' $apifoxZip
$install = Join-Path $base 'apifox-install'
Expand-Archive $apifoxZip -DestinationPath $install -Force
$installer = Get-ChildItem $install -Filter '*.exe' -Recurse | Select-Object -First 1
if (-not $installer) { throw 'Apifox installer missing.' }
$apifox = 'D:\lab3-ci\apifox'
$installProcess = Start-Process $installer.FullName -ArgumentList "/S /D=$apifox" -PassThru -Wait
if ($installProcess.ExitCode -ne 0) { throw "Apifox install exit $($installProcess.ExitCode)" }
$apifoxExe = Get-ChildItem $apifox -Filter Apifox.exe -Recurse | Select-Object -First 1
if (-not $apifoxExe) { throw 'Apifox executable not found.' }
$apifoxExe.VersionInfo | Select-Object -Property @('ProductVersion', 'FileVersion') | ConvertTo-Json | Set-Content (Join-Path $evidence 'apifox-version.json')
Start-Process $apifoxExe.FullName -ArgumentList '--force-renderer-accessibility' -PassThru | Select-Object Id | ConvertTo-Json | Set-Content (Join-Path $evidence 'apifox-launch.json')

& python -m pip install --disable-pip-version-check pyautogui pywinauto pillow requests
if ($LASTEXITCODE -ne 0) { throw 'Desktop driver dependencies failed.' }
Copy-Item "$PSScriptRoot/upload-artifact.cjs" "$base/upload-artifact.cjs"
& npm install --prefix $base --no-audit --no-fund '@actions/artifact@2'
if ($LASTEXITCODE -ne 0) { throw 'Artifact client installation failed.' }
Get-FileHash $vc,$mysqlZip,$ideaZip,$apifoxZip -Algorithm SHA256 | ConvertTo-Json | Set-Content (Join-Path $evidence 'official-download-hashes.json')
Stop-Transcript
