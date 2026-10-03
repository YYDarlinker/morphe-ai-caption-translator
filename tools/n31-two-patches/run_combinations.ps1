param()
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$env:JAVA_HOME='E:\morphe-ai-caption-translator-next\build\isolated-toolchains\jdk-21.0.12.1+1'
$env:ANDROID_HOME='C:\Users\14776\AppData\Local\Android\Sdk'
foreach($case in @(@{label='ai';selection='AI caption translator'},@{label='memory';selection='Remember caption selection'})){
 $output=Join-Path $repo ".verification/n31-two-patches/combinations/$($case.label)"
 $log=Join-Path $repo ".verification/n31-two-patches/combination-$($case.label).log"
 $args=@(':patches:verifyComposition','--offline','--console=plain','-Dorg.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g',
 "-Pcomposition.input=$repo/com.google.android.youtube_21.16.256-1561068412_minAPI28(arm64-v8a,armeabi-v7a,x86,x86_64)(nodpi)_apkmirror.com.apk",
 "-Pcomposition.official=$repo/patches-1.45.0.mpp","-Pcomposition.addon=$repo/build/local-test/patches-1.3.5-本地测试包-n31-two-patches.mpp",
 "-Pcomposition.output=$output","-Pcomposition.selection=$($case.selection)",'-Pcomposition.compile=false','-Pcomposition.dex-only=true')
 & "$repo/gradlew.bat" @args *> $log
 if($LASTEXITCODE -ne 0){Get-Content -LiteralPath $log -Tail 16;throw "Two-patch combination failed: $($case.label)"}
 & "$repo/gradlew.bat" ':patches:auditComposition' '--offline' '--console=plain' "-Pcomposition.apk=$output/serialized-dex.zip" *> "$repo/.verification/n31-two-patches/combination-$($case.label)-audit.log"
 if($LASTEXITCODE -ne 0){throw "Two-patch serialized audit failed: $($case.label)"}
 Write-Output "TWO_PATCH_SERIALIZED_PASS $($case.label)"
}