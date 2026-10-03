param([switch]$RepeatInSameProcess)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$env:JAVA_HOME='E:\morphe-ai-caption-translator-next\build\isolated-toolchains\jdk-21.0.12.1+1'
$env:ANDROID_HOME='C:\Users\14776\AppData\Local\Android\Sdk'
$records=Join-Path $repo '.verification/n32/delivery-records'
New-Item -ItemType Directory -Path $records -Force | Out-Null
# The resource-compiled final AI+Remember artifact is the third combination.
# Compose only the two remaining nonempty selections here; all use serialized actual DEX.
$final=Join-Path $repo 'build/n32-composition-final'
if(!(Test-Path -LiteralPath (Join-Path $final 'serialized-dex.zip'))){throw 'Run the final resource composition first; its AI+Remember result is combination 3/3'}
$inputs=@(
 (Join-Path $repo 'com.google.android.youtube_21.16.256-1561068412_minAPI28(arm64-v8a,armeabi-v7a,x86,x86_64)(nodpi)_apkmirror.com.apk'),
 (Join-Path $repo 'patches-1.45.0.mpp'),
 (Join-Path $repo 'build/local-test/patches-1.3.5-本地测试包-n32.mpp')
)
function Get-InputRows { param([string[]]$Paths)
 foreach($path in $Paths){@{path=$path;bytes=(Get-Item -LiteralPath $path).Length;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash}}
}
$before=@(Get-InputRows $inputs)
$cases=@(@{label='ai';selection='AI caption translator'},@{label='memory';selection='Remember caption selection'})
if($RepeatInSameProcess){$cases=@(@{label='same-process';selection='AI caption translator|Remember caption selection'})}
$snapshots=@()
foreach($case in $cases){
 $output=Join-Path $repo ".verification/n32/combinations-final/$($case.label)"
 if(Test-Path -LiteralPath $output){throw "Combination output already exists: $output"}
 $log=Join-Path $records "combination-$($case.label).log"
 $arguments=@(':patches:verifyComposition','--offline','--console=plain','-Dorg.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g',
 "-Pcomposition.input=$($inputs[0])","-Pcomposition.official=$($inputs[1])","-Pcomposition.addon=$($inputs[2])",
 "-Pcomposition.output=$output","-Pcomposition.selection=$($case.selection)",'-Pcomposition.compile=false','-Pcomposition.dex-only=true')
 if($RepeatInSameProcess){$arguments+='-Pcomposition.repeat=Remember caption selection;AI caption translator'}
 & "$repo/gradlew.bat" @arguments *> $log
 if($LASTEXITCODE -ne 0){Get-Content -LiteralPath $log -Tail 25;throw "Combination failed: $($case.label)"}
 $batch=@(@{label=$case.label;directory=$output;composition_log=$log})
 if($RepeatInSameProcess){foreach($index in 1..2){$batch+=@{label="same-process-$index";directory=(Join-Path $output "repeat-$index");composition_log=$log}}}
 foreach($snapshot in $batch){
  $auditLog=Join-Path $records "combination-$($snapshot.label)-audit.log"
  & "$repo/gradlew.bat" ':patches:auditComposition' '--offline' '--console=plain' "-Pcomposition.apk=$($snapshot.directory)/serialized-dex.zip" *> $auditLog
  if($LASTEXITCODE -ne 0){Get-Content -LiteralPath $auditLog -Tail 25;throw "Serialized combination audit failed: $($snapshot.label)"}
  $snapshot.audit_log=$auditLog
  $snapshots+=$snapshot
  Write-Output "N32_SERIALIZED_COMBINATION_PASS $($snapshot.label)"
 }
}
$after=@(Get-InputRows $inputs)
for($index=0;$index -lt $before.Count;$index++){if($before[$index].sha256 -ne $after[$index].sha256){throw "Combination input changed: $($before[$index].path)"}}
$label=if($RepeatInSameProcess){'same-process'}else{'combinations'}
$logs=@($cases | ForEach-Object {
 $path=Join-Path $records "combination-$($_.label).log"
 @{path=$path;bytes=(Get-Item -LiteralPath $path).Length;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash}
})
@{inputs_before=$before;inputs_after=$after;snapshots=$snapshots;logs=$logs;resource_compilation_count=0;same_process=[bool]$RepeatInSameProcess} |
 ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $records "$label-inputs.json") -Encoding utf8
$python=Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
& $python (Join-Path $repo 'tools/n32/write_combinations.py') "--$label"
if($LASTEXITCODE -ne 0){throw 'Combination artifact binding failed'}
