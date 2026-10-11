[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$taskRepository=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskDirectory=Join-Path $taskRepository 'work/toolchain/robolectric'
New-Item -ItemType Directory -Force -Path $taskDirectory | Out-Null
foreach($taskVersion in @('9-robolectric-4913185-2-i7','13-robolectric-9030017-i7','15-robolectric-12650502-i7')){
    $taskName='android-all-instrumented-'+$taskVersion+'.jar'
    $taskTarget=Join-Path $taskDirectory $taskName
    if(Test-Path -LiteralPath $taskTarget){continue}
    $taskCached=Join-Path $env:USERPROFILE ('.m2/repository/org/robolectric/android-all-instrumented/'+$taskVersion+'/'+$taskName)
    if(-not(Test-Path -LiteralPath $taskCached)){throw "Missing offline Robolectric artifact: $taskName. Provision it in work/toolchain/robolectric or the Maven cache before running -Offline."}
    Copy-Item -LiteralPath $taskCached -Destination $taskTarget
}
Write-Output 'Offline Android 28/33/35 test dependencies are local; runtime downloading is disabled.'
