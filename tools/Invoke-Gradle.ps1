[CmdletBinding()]
param([Parameter(ValueFromRemainingArguments=$true)][string[]]$GradleArguments)
$ErrorActionPreference='Stop'
$taskEnvironment=. (Join-Path $PSScriptRoot 'Enter-Workspace.ps1')
$taskMutex=[Threading.Mutex]::new($false,'Local\MorpheCaptionReintegratedVerification')
$taskOwned=$false
try {
    try {$taskOwned=$taskMutex.WaitOne(0)} catch [Threading.AbandonedMutexException] {$taskOwned=$true}
    if(-not $taskOwned){throw 'Another Gradle operation owns this workspace build tree.'}
    & (Join-Path $taskEnvironment.Repository 'gradlew.bat') @GradleArguments
    if($LASTEXITCODE -ne 0){throw "Gradle failed with exit code $LASTEXITCODE"}
} finally {
    if($taskOwned){$taskMutex.ReleaseMutex()}
    $taskMutex.Dispose()
}
