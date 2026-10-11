[CmdletBinding()]
param([switch]$Offline,[switch]$Live,[int]$MaxRequests=0,[long]$MaxTotalTokens=0,[int]$ElapsedBudgetSeconds=0)
$ErrorActionPreference='Stop'
if($Live -and ($MaxRequests -le 0 -or $MaxTotalTokens -le 0 -or $ElapsedBudgetSeconds -le 0)){throw 'Live requires positive request, token and elapsed budgets.'}
$taskEnvironment=. (Join-Path $PSScriptRoot 'Enter-Workspace.ps1')
$taskRun=Join-Path $taskEnvironment.Repository ('validation\runs\'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $taskRun -Force | Out-Null
$taskMutex=[Threading.Mutex]::new($false,'Local\MorpheCaptionReintegratedVerification')
$taskOwned=$false
try {
    try{$taskOwned=$taskMutex.WaitOne(0)}catch [Threading.AbandonedMutexException]{$taskOwned=$true}
    if(-not $taskOwned){throw 'Another Gradle operation owns the build tree.'}
    if($Offline){& (Join-Path $PSScriptRoot 'Prepare-OfflineTests.ps1')}
    & $taskEnvironment.Python -X utf8 tools/check_localization.py *> (Join-Path $taskRun 'localization.log')
    if($LASTEXITCODE -ne 0){throw "Localization checks failed: $taskRun"}
    $taskArguments=@(':translation-core:testDebugUnitTest',':extensions:extension:testDebugUnitTest',':ui-preview:assembleDebug',':patches:buildAndroid','--no-parallel','--console=plain',('-Pscheduler.output='+$taskRun))
    if($Offline){$taskArguments+='--offline'}
    & .\gradlew.bat @taskArguments *> (Join-Path $taskRun 'gradle.log')
    $taskBuildExit=$LASTEXITCODE
    foreach($taskModule in @('translation-core','extensions\extension')){
        $taskResults=Join-Path $taskEnvironment.Repository ('build\n39\gradle\'+$taskModule+'\test-results\testDebugUnitTest')
        if(Test-Path -LiteralPath $taskResults){Copy-Item -LiteralPath $taskResults -Destination (Join-Path $taskRun ($taskModule.Replace('\','-')+'-tests')) -Recurse}
    }
    if($taskBuildExit -ne 0){Get-Content -LiteralPath (Join-Path $taskRun 'gradle.log') -Tail 28;throw "Build/tests failed: $taskRun"}
    New-Item -ItemType Directory -Path build\delivery -Force | Out-Null
    Copy-Item -LiteralPath build\n39\gradle\extensions\extension\morphe\extensions\extension.mpe -Destination build\delivery\extension.mpe
    & $taskEnvironment.Python -X utf8 tools/verify_integration.py --output (Join-Path $taskRun 'boundaries.json') *> (Join-Path $taskRun 'boundaries.log')
    if($LASTEXITCODE -ne 0){throw "Boundary/package checks failed: $taskRun"}
    if($Live){
        $taskLive=@(':translation-core:testDebugUnitTest','-Ptranslation.live=true','--tests','*PinnedCoreLiveTest','--console=plain',('-Plive.maxRequests='+$MaxRequests),('-Plive.maxTokens='+$MaxTotalTokens),('-Plive.elapsedSeconds='+$ElapsedBudgetSeconds),('-Plive.output='+(Join-Path $taskRun 'live-translation.json')))
        if($Offline){$taskLive+='--offline'}
        & .\gradlew.bat @taskLive *> (Join-Path $taskRun 'live.log')
        if($LASTEXITCODE -ne 0){throw "Bounded Live smoke failed: $taskRun"}
    }
    $taskBoundary=Get-Content -Raw -LiteralPath (Join-Path $taskRun 'boundaries.json') | ConvertFrom-Json
    & $taskEnvironment.Python -X utf8 tools/scan_delivery_credentials.py --path $taskRun --path $taskBoundary.android_patch.path --path build/delivery/extension.mpe --output (Join-Path $taskRun 'credential-scan.json') *> (Join-Path $taskRun 'credential-scan.log')
    if($LASTEXITCODE -ne 0){throw "Credential scan failed: $taskRun"}
    $taskRun | Set-Content -Encoding utf8 -LiteralPath work\last-verify.txt
    Get-Content -LiteralPath (Join-Path $taskRun 'gradle.log') -Tail 8
    Write-Output "Verification evidence: $taskRun"
} finally {if($taskOwned){$taskMutex.ReleaseMutex()};$taskMutex.Dispose()}
