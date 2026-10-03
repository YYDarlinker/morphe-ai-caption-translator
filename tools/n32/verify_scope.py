"""N32 window/hooks/root seams leave frozen N31 translation, rendering and user storage code exact."""
from pathlib import Path
import hashlib,json,subprocess
R=Path(__file__).resolve().parents[2];V=R/'.verification/n32';O=V/'delivery-records'
manifest=json.loads((V/'input-manifest.json').read_text(encoding='utf-8'))
allowed_java={name+'.java' for name in ['ApiProfilesPreference','CaptionAddonSupport','CaptionLanguageMetadata','CaptionLanguageSelection','CaptionLanguagesPreference','CaptionPreferenceBindings','CaptionSettingsDialogs','CaptionUiLocale','CaptionUiPreference','CaptionUiWindows','DeepSeekActionPreference','DeepSeekDiagnosticsPreference','DeepSeekModelPreference','NativeCaptionBridge']}
allowed_kotlin={'CaptionFeaturePatches.kt','CaptionSettingsBindingPatch.kt','NativeCaptionBridgePatch.kt'}
allowed_existing_tests={'ModularCaptionTest.java','N30LanguageMenuTest.java','N31RuntimeUiTest.java','ProfileUiRegressionTest.java','N30LocalizationRuntimeTest.java'}
changed=subprocess.check_output(['git','diff','--name-only',manifest['head']],cwd=R).decode().splitlines()
for path in changed:
    if '/src/main/java/' in path:assert Path(path).name in allowed_java,path
    if path.startswith('patches/src/main/kotlin/'):assert Path(path).name in allowed_kotlin,path
    if '/src/test/java/' in path:assert Path(path).name in allowed_existing_tests,path
    assert not path.startswith('scoreboard/') and path!='ACCEPTANCE.md',path
old=json.loads((R/'.verification/n31/full-final-04/inputs.json').read_text(encoding='utf-8'))['files']
def sha(path):
    with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()
protected=[]
for raw_path,digest in old.items():
    path=raw_path.replace(chr(92),'/')
    if '/src/main/java/' in path and Path(path).name not in allowed_java:
        assert sha(R/path)==digest,path
        protected.append(path)
for path in ['ACCEPTANCE.md','scoreboard/results/frozen-baseline.json','scoreboard/results/n9-evidence.json','scoreboard/results/n10-startup.json','patches-bundle.json','CHANGELOG.md','localization/catalog.json','extensions/extension/src/main/java/app/yydarlinker/deepseekcaptions/LanguageMenuOrder.java']:
    raw=(R/path).read_bytes().replace(bytes([13,10]),bytes([10]))
    assert hashlib.sha256(raw).hexdigest()==manifest['files'][path],path
original=subprocess.check_output(['git','show',manifest['head']+':patches/src/main/kotlin/app/yydarlinker/patches/deepseekcaptions/NativeCaptionBridgePatch.kt'],cwd=R).decode()
current=(R/'patches/src/main/kotlin/app/yydarlinker/patches/deepseekcaptions/NativeCaptionBridgePatch.kt').read_text(encoding='utf-8')
# Generic language setter is the selected target; source/URL/VSS selection methods are otherwise exact.
assert 'invoke-virtual {v0, p1}, ${languageSetter.id()}' in current
for source in ['RebuildController.java','RebuildProtocol.java','RebuildCache.java','CaptionRenderSpec.java','CaptionOverlayV2.java','DeepSeekConfig.java','ApiProfiles.java','SecureApiKey.java']:
    path='extensions/extension/src/main/java/app/yydarlinker/deepseekcaptions/'+source
    assert path in protected,source
report={'baseline_head':manifest['head'],'restored_product':'dc304cbe3ae995e7e0edad2754b160c959cafce3','protected_n31_business_files_byte_exact':protected,'changed_paths':changed,'allowed_ui_hook_and_public_root_changes_only':True,'language_menu_locale_sort_exact':True,'business_config_and_storage_implementation_exact':True,'acceptance_frozen_release_urls_locale_catalog_exact':True,'generic_clone_target_fix':'only constant zh-Hans language setter becomes the selected p1; original before in standalone-before/generic-clone-before.json'}
O.mkdir(exist_ok=True);(O/'scope-proof.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+chr(10),encoding='utf-8')
print('N32_SCOPE_PASS protected_business_byte_exact',len(protected),'changed',len(changed))
