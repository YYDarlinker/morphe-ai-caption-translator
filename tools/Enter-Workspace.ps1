[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$taskRepository=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskJdkCandidates=@($env:JAVA_HOME,(Join-Path $taskRepository 'work\toolchain\jdk-21'),'E:\morphe-ai-caption-translator-next\build\isolated-toolchains\jdk-21.0.12.1+1')
$taskJdk=$null
foreach($candidate in $taskJdkCandidates){
    if($candidate -and (Test-Path -LiteralPath (Join-Path $candidate 'bin\javac.exe'))){
        # Windows PowerShell 5.1 turns redirected native stderr into a terminating error under Stop.
        $version=& {$ErrorActionPreference='Continue';& (Join-Path $candidate 'bin\java.exe') -version 2>&1 | Out-String}
        if($version -match 'version "21\.'){$taskJdk=$candidate;break}
    }
}
if(-not $taskJdk){throw 'Install JDK 21 and set JAVA_HOME to its directory.'}
$taskSdk=if($env:ANDROID_HOME){$env:ANDROID_HOME}else{Join-Path $env:LOCALAPPDATA 'Android\Sdk'}
if(-not(Test-Path -LiteralPath (Join-Path $taskSdk 'platforms\android-35\android.jar'))){throw 'Install Android SDK platform 35 and set ANDROID_HOME.'}
$taskPython=if($env:MORPHE_TASK_PYTHON){$env:MORPHE_TASK_PYTHON}else{Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'}
if(-not(Test-Path -LiteralPath $taskPython)){$taskPython=(Get-Command python -ErrorAction Stop).Source}
$taskTemp=Join-Path $taskRepository 'work\temp'
New-Item -ItemType Directory -Path $taskTemp -Force | Out-Null
$env:JAVA_HOME=$taskJdk
$env:ANDROID_HOME=$taskSdk
$env:ANDROID_SDK_ROOT=$taskSdk
$env:MORPHE_TASK_PYTHON=$taskPython
$env:PYTHONUTF8='1'
$env:PYTHONIOENCODING='utf-8'
$env:TEMP=$taskTemp
$env:TMP=$taskTemp
Set-Location -LiteralPath $taskRepository
[pscustomobject]@{Repository=$taskRepository;Java=$taskJdk;AndroidSdk=$taskSdk;Python=$taskPython}
