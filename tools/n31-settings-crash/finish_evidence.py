from pathlib import Path
import json,hashlib,re,zipfile,struct
R=Path(__file__).resolve().parents[2];V=R/'.verification/n31-settings-crash'
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def sha(p):
    with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def log(name):return (V/name).read_text(encoding='utf-8-sig',errors='replace')
start=read(V/'input-manifest.json');art=read(V/'art-local-emulator-final.json');tests=read(V/'targeted-tests/result.json')
assert art['local_before']['exit_code']==1 and art['local_after']['exit_code']==0
assert 'VerifyError' in log(art['local_before']['log']) and 'ART_SETTINGS_VERIFICATION_PASS' in log(art['local_after']['log'])
assert 'VerifyError' in log(art['actual_phone_historical_before']['log'])
assert tests['exit']==0 and tests['totals']=={'tests':10,'failures':0,'errors':0,'skipped':0}
assert tests['input_sha']==read(R/'.verification/n31-two-patches/full/result.json')['input_sha']
for p,h in read(V/'targeted-tests/inputs.json')['files'].items():assert sha(R/p)==h,p
for row in start['history']:assert sha(R/row['path'])==row['sha256'] and (R/row['path']).stat().st_size==row['bytes'],row['path']
allowed={'patches/build.gradle.kts','patches/src/main/kotlin/app/yydarlinker/patches/deepseekcaptions/CaptionSettingsBindingPatch.kt','patches/src/test/kotlin/validation/CompositionDexAudit.kt'}
changed=[p for p,h in start['tracked'].items() if sha(R/p)!=h]
assert set(changed)<=allowed,changed
assert 'SETTINGS_OLD_REGISTER_REUSE_REJECTED' in log('register-and-timing-negative-final.log') and 'SETTINGS_PREWRITE_CALLBACK_REJECTED' in log('register-and-timing-negative-final.log') and 'SETTINGS_REGISTER_REGRESSION_PASS' in log('register-and-timing-negative-final.log')
assert 'SETTINGS_HOOK_REGISTER_TYPES_PASS typed_tree_result=1 receiver_entry_hooks=2 language_after_write=1 typed_view_return=1' in log('composition-dex-audit-timing-final.log')
for label in ['mpp','mpe','apk']:assert 'invalid_branches=0 dex_problems=0 binding_failures=0' in log(label+'-branch-timing-final.txt')
assert 'TWO_PUBLIC_PATCHES_VALIDATED' in log('verify-bundle-timing-final.log')
assert 'root_dex=true extension=true crc=true' in log('n8-verify-timing-final.log')
assert "sdkVersion:'28'" in log('aapt-badging-timing-final.log')
paths=[R/'build/local-test/patches-1.3.5-本地测试包-n31-settings-crash-fixed.mpp',R/'build/local-test/extension-1.3.5-本地测试包-n31-settings-crash-fixed.mpe',R/'build/n31-settings-crash-fixed-composition-final/YouTube-21.16.256-本地测试包-n31-settings-crash-fixed-unsigned.apk']
assert sha(paths[2])==art['local_after']['apk_sha256'].lower()
assert sha(paths[1])==sha(R/'build/local-test/extension-1.3.5-本地测试包-n31-two-patches.mpe')
with zipfile.ZipFile(paths[2]) as z:assert z.testzip() is None;assert not any(re.match(r'META-INF/.*\.(RSA|DSA|EC|SF)$',n,re.I) for n in z.namelist())
with paths[2].open('rb') as f:
    f.seek(-65557,2);tail=f.read();at=tail.rfind(b'PK\x05\x06');central=struct.unpack_from('<I',tail,at+16)[0];f.seek(central-16);assert f.read(16)!=b'APK Sig Block 42'
metrics=re.search(r'SUMMARY label=n31-settings-crash-fixed-apk dex_units=(\d+) classes=(\d+) methods=(\d+) branch_edges=(\d+)',log('apk-branch-timing-final.txt'));assert metrics
summary={'execution_head':start['head'],'cause':'N31 hook passed overwritten p0 registers as PreferenceFragment at method returns','official_callback_instance_flags':4098,'before_installed_apk_sha256':start['installed_before_sha256'],'phone_original_art_before_exit':1,'local_emulator_art_before_exit':1,'local_emulator_art_after_exit':0,'final_sdk37_phone_art':'not run after local-only instruction','emulator_sdk':35,'emulator_abi':'x86_64','device_art_classes':len(art['probe']['classes']),'targeted_java_tests':10,'unchanged_java_full_baseline':682,'java_source_test_input_sha':tests['input_sha'],'changed_existing_files':changed,'historical_files_unchanged':len(start['history']),'all_existing_java_resources_metadata_unchanged':True,'language_rebind_after_official_write':True,'final_delivery_local_only':True,'dex_units':int(metrics[1]),'dex_classes':int(metrics[2]),'dex_methods':int(metrics[3]),'dex_branches':int(metrics[4]),'artifacts':[{'path':str(p),'bytes':p.stat().st_size,'sha256':sha(p)} for p in paths],'app_install_sign_uninstall_clear_data_remote_api_download_publish':0,'device_ui_tap_fixed_apk':'not installed; user must recompose/sign with own Morphe key'}
(V/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(summary,ensure_ascii=False,indent=2))
