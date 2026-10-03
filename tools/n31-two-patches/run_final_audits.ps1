$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$env:JAVA_HOME='E:\morphe-ai-caption-translator-next\build\isolated-toolchains\jdk-21.0.12.1+1'
$env:ANDROID_HOME='C:\Users\14776\AppData\Local\Android\Sdk'
$records=Join-Path $repo '.verification/n31-two-patches'
$artifacts=@{
 mpp='build/local-test/patches-1.3.5-本地测试包-n31-two-patches.mpp'
 mpe='build/local-test/extension-1.3.5-本地测试包-n31-two-patches.mpe'
 apk='build/n31-two-patches-composition-final/YouTube-21.16.256-本地测试包-n31-two-patches-unsigned.apk'
}
foreach($label in @('mpp','mpe','apk')){
 $args=@('-I',"$repo/tools/n28a/audit.init.gradle",':patches:auditN28AFinal','--offline','--console=plain',"-Pn28aAudit.input=$(Join-Path $repo $artifacts[$label])","-Pn28aAudit.report=$records/$label-branch-audit.txt",'-Pn28aAudit.require-ai=false',"-Pn28aAudit.label=n31-two-patches-$label")
 & "$repo/gradlew.bat" @args *> "$records/$label-branch-audit.log"
 if($LASTEXITCODE -ne 0){Get-Content "$records/$label-branch-audit.log" -Tail 14;throw "Two-patch final $label audit failed"}
 Get-Content "$records/$label-branch-audit.txt" | Select-String 'SUMMARY|AUDIT_PASS'
}
& "$repo/gradlew.bat" ':patches:auditComposition' '--offline' '--console=plain' "-Pcomposition.apk=$(Join-Path $repo $artifacts.apk)" *> "$records/composition-dex-audit.log"
if($LASTEXITCODE -ne 0){throw 'Two-patch APK feature/hook audit failed'}
$env:PYTHONUTF8='1'
& "$env:USERPROFILE/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe" "$repo/tools/n31-two-patches/verify_bundle.py" *> "$records/verify-bundle.log"
if($LASTEXITCODE -ne 0){Get-Content "$records/verify-bundle.log" -Tail 15;throw 'Two-patch bundle validation failed'}
& "$env:JAVA_HOME/bin/java.exe" -cp "$repo/build" N8Verify (Join-Path $repo $artifacts.mpp) (Join-Path $repo $artifacts.mpe) *> "$records/n8-verify.log"
if($LASTEXITCODE -ne 0){throw 'Two-patch N8Verify failed'}
& "$env:ANDROID_HOME/build-tools/36.0.0/aapt.exe" dump badging (Join-Path $repo 'build/n31-two-patches-composition-final/patched-unsigned.apk') > "$records/aapt-badging.log"
if($LASTEXITCODE -ne 0){throw 'aapt failed'}
Write-Output 'TWO_PATCH_FINAL_AUDITS_PASS'