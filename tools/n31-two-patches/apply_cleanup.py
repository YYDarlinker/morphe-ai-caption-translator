from pathlib import Path
import json,hashlib,subprocess,shutil
R=Path(__file__).resolve().parents[2];V=R/'.verification/n31-two-patches';V.mkdir(exist_ok=False)
def sha(p):
    with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
files=subprocess.check_output(['git','ls-files','-z'],cwd=R).decode().split('\0')
history=[]
for p in list((R/'build/local-test').glob('*n31.*'))+[R/'build/n31-composition-final/YouTube-21.16.256-本地测试包-n31-unsigned.apk']+list((R/'.verification/n31/delivery-records').glob('*')):
    if p.is_file():history.append({'path':p.relative_to(R).as_posix(),'sha256':sha(p),'bytes':p.stat().st_size})
(V/'input-manifest.json').write_text(json.dumps({'head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=R).decode().strip(),'files':{p:sha(R/p) for p in files if p and (R/p).is_file()},'history':history},indent=2),encoding='utf-8')
shutil.copytree(R/'extensions/extension/src/test',V/'tests-before')

def update(rel,old,new):
    p=R/rel;s=p.read_text(encoding='utf-8');assert old in s,(rel,old);p.write_text(s.replace(old,new),encoding='utf-8')
P='patches/src/main/kotlin/app/yydarlinker/patches/deepseekcaptions/'
p=R/(P+'CaptionFeaturePatches.kt');s=p.read_text(encoding='utf-8');start=s.index('@Suppress("unused")\nval simplifiedCaptionLanguagePatch');end=s.index('@Suppress("unused")\nval rememberCaptionSelectionPatch',start);s=s[:start]+s[end:];s=s.replace('var ai=false; var simplified=false; var memory=false','var ai=false; var memory=false').replace('CaptionFeatures.ai=false;CaptionFeatures.simplified=false;CaptionFeatures.memory=false','CaptionFeatures.ai=false;CaptionFeatures.memory=false').replace('installNativeCaptionBridge(CaptionFeatures.ai,CaptionFeatures.simplified,CaptionFeatures.memory)','installNativeCaptionBridge(CaptionFeatures.ai,CaptionFeatures.memory)');p.write_text(s,encoding='utf-8')
update(P+'NativeCaptionBridgePatch.kt','ai:Boolean, simplified:Boolean, memory:Boolean','ai:Boolean, memory:Boolean')
update(P+'NativeCaptionBridgePatch.kt','"aiInstalled" to ai,"simplifiedInstalled" to simplified,"memoryInstalled" to memory','"aiInstalled" to ai,"memoryInstalled" to memory')
update(P+'NativeCaptionBridgePatch.kt','if(ai || simplified)','if(ai)')
update(P+'NativeCaptionBridgePatch.kt','// optional simplified language menu','// AI-owned auto-translate language menu')
J='extensions/extension/src/main/java/app/yydarlinker/deepseekcaptions/'
update(J+'CaptionAddonSupport.java','    public static boolean simplifiedInstalled(){return false;}\n','')
update(J+'CaptionLanguageSelection.java','        // Backward-compatible standalone root; AI no longer needs that root for this menu seam.\n        if(CaptionAddonSupport.simplifiedInstalled())codes.add("zh-Hans");\n','')
update(J+'CaptionLanguageMetadata.java','if(!CaptionAddonSupport.aiInstalled() && !CaptionAddonSupport.simplifiedInstalled())','if(!CaptionAddonSupport.aiInstalled())')
update(J+'NativeCaptionBridge.java','(!CaptionAddonSupport.aiInstalled() && !CaptionAddonSupport.simplifiedInstalled())','!CaptionAddonSupport.aiInstalled()')
T='patches/src/test/kotlin/validation/'
update(T+'CompositionHarness.kt','"aiInstalled" to ai,"simplifiedInstalled" to names.contains("Add Simplified Chinese to auto-translate"),"memoryInstalled" to names.contains("Remember caption selection")','"aiInstalled" to ai,"memoryInstalled" to names.contains("Remember caption selection")')
update(T+'CompositionHarness.kt','(ai || expected.getValue("simplifiedInstalled"))','ai')
update(T+'CompositionDexAudit.kt','if(flags["aiInstalled"]==1L || flags["simplifiedInstalled"]==1L)','if(flags["aiInstalled"]==1L)')
E='extensions/extension/src/test/java/app/yydarlinker/deepseekcaptions/'
update(E+'ModularCaptionTest.java','ai,memory,simplified','ai,memory')
update(E+'ModularCaptionTest.java','        @Implementation public static boolean simplifiedInstalled(){return simplified;}\n','')
update(E+'ModularCaptionTest.java','ai=false;memory=false;simplified=false;','ai=false;memory=false;')
update(E+'N30LanguageMenuTest.java','static boolean ai,simplified;','static boolean ai;')
update(E+'N30LanguageMenuTest.java','  @Implementation public static boolean simplifiedInstalled(){return simplified;}\n','')
update(E+'N30LanguageMenuTest.java','ai=true;simplified=false;','ai=true;')
update(E+'N30LanguageMenuTest.java','simplified=true;List<?> out=NativeCaptionBridge.augmentTranslations(before);assertEquals(2,out.size());','List<?> out=NativeCaptionBridge.augmentTranslations(before);assertSame(before,out);assertEquals(1,out.size());')
update(E+'N30LanguageMenuTest.java','legacyNativeOnlyAndRememberOnlySelectionsDoNotEnableAiOrChangeUserSet','nativeAndRememberOnlySelectionsDoNotEnableAiOrAddLanguagesOrChangeUserSet')
p=R/'.github/scripts/validate_morphe_metadata.py';s=p.read_text(encoding='utf-8');old='''        if set(names) != {"AI caption translator", "Add Simplified Chinese to auto-translate", "Remember caption selection"}:
            fail("modular release must contain exactly the three declared public patches")''';new='''        expected = {"AI caption translator", "Remember caption selection"}
        if tuple(map(int, version.split("-")[0].split("."))) < (1, 3, 5):
            expected.add("Add Simplified Chinese to auto-translate")
        if set(names) != expected:
            fail("release must contain exactly the declared public patches; language menu belongs to AI")''';assert old in s;s=s.replace(old,new);p.write_text(s,encoding='utf-8')
p=R/'.github/scripts/verify_remote_source.py';s=p.read_text(encoding='utf-8');old='    expected_names.update({"Add Simplified Chinese to auto-translate","Remember caption selection"})';new='''    expected_names.add("Remember caption selection")
    if tuple(map(int,v.split("-")[0].split("."))) < (1,3,5):
        expected_names.add("Add Simplified Chinese to auto-translate")''';assert old in s;p.write_text(s.replace(old,new),encoding='utf-8')
p=R/'README.md';s=p.read_text(encoding='utf-8');s=s.replace('Three independently selectable caption patches: the AI translator, a locale-ordered Simplified Chinese menu entry, and native-compatible caption selection memory. AI includes an in-player engine selector; settings support 14 UI languages.','Two independently selectable caption patches: `AI caption translator` and `Remember caption selection`. AI includes the 14-language Auto-translate menu selector (including Simplified Chinese), an in-player toggle and settings in 14 UI languages.');s=s.replace('- the optional Simplified Chinese patch adds a localized, locale-ordered `zh-Hans` entry;','- the AI settings language selector adds the chosen languages, including `zh-Hans`, to the native Auto-translate list with localized ordering;');s=s.replace('Optionally select `Add Simplified Chinese to auto-translate` and/or `Remember caption selection`. Those two optional patches also work without selecting AI. To retain the former all-in-one feature set, select all three.','Optionally select `Remember caption selection`, which also works without selecting AI. Language insertion is part of `AI caption translator`: in Morphe video settings → AI caption translation → Auto-translate languages, choose one or more of the 14 languages. The saved language list also works while the AI engine is off. No separate Simplified Chinese patch is required.');s=s.replace('another Simplified Chinese remapping/insertion patch with the language root','another overlapping automatic-translation language insertion patch with the AI root');p.write_text(s,encoding='utf-8')
print('Removed standalone public patch and installed flag; AI menu capability unchanged; metadata contract now two roots')