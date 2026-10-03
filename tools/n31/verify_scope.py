"""N31 permitted UI/default display edits do not change N30 translation or publication contracts."""
from pathlib import Path
import json, hashlib, subprocess, re
R=Path(__file__).resolve().parents[2];O=R/'.verification/n31/delivery-records'
start=json.loads((R/'.verification/n31/input-manifest.json').read_text(encoding='utf-8'))
def sha(p):
    with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
allowed={n+'.java' for n in ['AddonSwitchPreference','ApiProfiles','ApiProfilesPreference','CaptionDiagnostics','CaptionLanguagesPreference','CaptionQuickToggle','CaptionSettingsDialogs','CaptionStrings','CaptionTranslationCatalog','DeepSeekActionPreference','DeepSeekConfig','DeepSeekDiagnosticsPreference','DeepSeekDisplayTextDebugPreference','DeepSeekEnabledPreference','DeepSeekModelPreference','DeepSeekSliderPreference','DeepSeekTextPreference','InlineCaptionEditor','LanguageMenuOrder','SecureApiKey','SubtitleStylePreview']}
changed=[p for p,h in start['files'].items() if sha(R/p).lower()!=h.lower()]
business=[]
for path in changed:
    if '/src/main/java/' in path:
        assert Path(path).name in allowed,path
    if path.startswith('scoreboard/') or path=='ACCEPTANCE.md':raise AssertionError(path)
for path,h in start['files'].items():
    if '/src/main/java/' in path and Path(path).name not in allowed:
        assert sha(R/path).lower()==h.lower(),path;business.append(path)
old=(R/'.verification/n31/source-before/LanguageMenuOrder.java').read_text(encoding='utf-8')
new=(R/'extensions/extension/src/main/java/app/yydarlinker/deepseekcaptions/LanguageMenuOrder.java').read_text(encoding='utf-8')
new=re.sub(r'    /\*\* Display-only overload;.*?\n    static String label\(String code,Locale display\).*?\n','',new,flags=re.S)
assert old.replace('\r\n','\n')==new.replace('\r\n','\n'),'Native menu sort/locale changed'
modified_tests={Path(p).name for p in changed if '/src/test/java/' in p}
assert modified_tests<={'N25DiagnosticsLocalizationTest.java','N28BProductionTest.java','N30ConnectionFailureTest.java','N30LocalizationRuntimeTest.java','ProfileUiRegressionTest.java'},modified_tests
controller='extensions/extension/src/main/java/app/yydarlinker/deepseekcaptions/RebuildController.java'
assert sha(R/controller).lower()==start['files'][controller].lower()
out={'actual_head':start['head'],'protected_business_files_byte_identical':business,'controller_owner_publication_cas_barriers_exact':True,'native_menu_locale_sort_compare_exact':True,'modified_existing_test_files':sorted(modified_tests),'acceptance_scoreboard_unchanged':True,'source_changes':changed}
(O/'scope-proof.json').write_text(json.dumps(out,indent=2),encoding='utf-8');print('N31_SCOPE_PASS protected_business=',len(business))
