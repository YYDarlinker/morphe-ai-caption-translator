$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$env:JAVA_HOME='E:\morphe-ai-caption-translator-next\build\isolated-toolchains\jdk-21.0.12.1+1'
$env:ANDROID_HOME='C:\Users\14776\AppData\Local\Android\Sdk'
$out=Join-Path $repo 'build/n32-composition-final'
if(Test-Path -LiteralPath $out){throw 'Final output already exists; do not overwrite a delivery'}
$records=Join-Path $repo '.verification/n32/delivery-records'
New-Item -ItemType Directory -Path $records -Force | Out-Null
$inputs=@(
 (Join-Path $repo 'com.google.android.youtube_21.16.256-1561068412_minAPI28(arm64-v8a,armeabi-v7a,x86,x86_64)(nodpi)_apkmirror.com.apk'),
 (Join-Path $repo 'patches-1.45.0.mpp'),
 (Join-Path $repo 'build/local-test/patches-1.3.5-本地测试包-n32.mpp')
)
function Get-InputRows { param([string[]]$Paths)
 foreach($path in $Paths){@{path=$path;bytes=(Get-Item -LiteralPath $path).Length;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash}}
}
$before=@(Get-InputRows $inputs)
$log=Join-Path $records 'composition-final.log'
$arguments=@(':patches:verifyComposition','--offline','--console=plain','-Dorg.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g',
 "-Pcomposition.input=$($inputs[0])","-Pcomposition.official=$($inputs[1])","-Pcomposition.addon=$($inputs[2])","-Pcomposition.output=$out",
 '-Pcomposition.selection=AI caption translator|Remember caption selection','-Pcomposition.compile=true','-Pcomposition.dex-only=true')
& "$repo/gradlew.bat" @arguments *> $log
if($LASTEXITCODE -ne 0){Get-Content -LiteralPath $log -Tail 35;throw 'Composition failed'}
$after=@(Get-InputRows $inputs)
for($index=0;$index -lt $before.Count;$index++){if($before[$index].sha256 -ne $after[$index].sha256){throw "Composition input changed: $($before[$index].path)"}}
@{inputs_before=$before;inputs_after=$after;output=$out;selection=@('AI caption translator','Remember caption selection');
 log=@{path=$log;bytes=(Get-Item -LiteralPath $log).Length;sha256=(Get-FileHash -LiteralPath $log -Algorithm SHA256).Hash};compile_resources=$true;serialized_dex=$true} |
 ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $records 'composition-final-inputs.json') -Encoding utf8
Get-Content -LiteralPath $log | Select-String '^PASS |COMPOSITION_PASS|STRUCTURE|BUILD SUCCESSFUL|N32_PUBLIC_ROOTS_PASS'
