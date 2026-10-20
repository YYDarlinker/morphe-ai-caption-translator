"""Check sources, compiled dependencies and packages without network/original-workspace access."""
from collections import Counter
from pathlib import Path
import argparse,hashlib,json,re,zipfile,io
R=Path(__file__).resolve().parents[1]
PREFIX='app/yydarlinker/deepseekcaptions/'
CORE_BANNED=['CaptionApplication','CaptionOverlay','CaptionActions','DeepSeekConfig','SecureApiKey','DeepSeekCaptionHook','CaptionStrings','TokenCostAudit','CaptionPlayerAuthority','CaptionSurface','NativeCaptionBridge','RawCaptionSource','SourceCaptionCache','ProviderModelCatalog']
UI_BANNED=['RebuildController','RebuildApi','RebuildProtocol','RebuildPlanner','TranslationRuntime','TranslationAudit','ProviderRequestPolicy','MeaningTransport','MeaningWire','CaptionCore']
DEAD=['app/yydarlinker/caption/core/CaptionCore','MeaningTransport','MeaningWire','MeaningPlannerWire','PlainMeaningWire','BatchMeaningWire','SourcePreparationWire','N39Application','RebuildSourcePlan','RebuildSemanticTimeline','RebuildSemanticStore','RebuildSupplyPolicy','RelationCandidate','FrozenDev3','CaptionSemanticUnit','CaptionRenderFrame','CaptionPageScheduler','CaptionPresentationCursor','CaptionPresentationState','MeaningPager','SentenceCandidate','TranslationRetryPolicy']
def sha(data):return hashlib.sha256(data).hexdigest()
def constants(path):
    data=path.read_bytes();assert data[:4]==b'\xca\xfe\xba\xbe'
    count=int.from_bytes(data[8:10],'big');at=10;i=1;out=[]
    while i<count:
        tag=data[at];at+=1
        if tag==1:
            size=int.from_bytes(data[at:at+2],'big');at+=2;out.append(data[at:at+size].decode('utf-8','replace'));at+=size
        elif tag in (7,8,16,19,20):at+=2
        elif tag in (9,10,11,12,17,18,3,4):at+=4
        elif tag in (5,6):at+=8;i+=1
        elif tag==15:at+=3
        else:raise ValueError(f'unknown constant tag {tag}')
        i+=1
    return out
# Literals of upstream code that is unused (an uncalled helper, an empty-string default) and was deleted.
REMOVED_DEAD_LITERALS={'"HTTP_CONFIG"','""','"layout_overflow"','"paragraph"','","','" "',r'"\\s+"',r'"(?<![\\p{L}\\p{N}])[+-]?\\d+(?:,\\d{3})*(?:\\.\\d+)?"','"numeric_substitution"'}
def literals(path):
    pattern=r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"'
    # Adjacent literals joined by + are one value, so re-wrapping a long prompt is not a change.
    text=re.sub(r"'(?:\\.|[^'\\])'","''",path.read_text(encoding='utf-8-sig'))  # char literals
    text=re.sub(r'"\s*\+\s*"','',text)
    return [m.group() for m in re.finditer(pattern,text) if m.group().startswith('"')]
def inspect(source_only=False):
    errors=[];facts={}
    for module,banned in [('translation-core',CORE_BANNED),('caption-shared',CORE_BANNED+UI_BANNED),('ui',UI_BANNED),('presentation',UI_BANNED)]:
        for path in (R/module/'src/main/java').rglob('*.java'):
            source=re.sub(r'//[^\n]*|/\*[\s\S]*?\*/','',path.read_text(encoding='utf-8-sig'))
            for name in banned:
                if re.search(r'\b'+name+r'\b',source):errors.append(f'{path.relative_to(R)} depends on {name}')
            if module=='translation-core' and re.search(r'\bimport\s+android\.(app|view|widget)\.',source):errors.append(f'{path.relative_to(R)} imports a UI component')
    provenance=json.loads((R/'docs/core-provenance.json').read_text(encoding='utf-8'))
    for row in provenance['files']:
        if sha((R/row['snapshot']).read_bytes())!=row['upstream_sha256']:errors.append('changed pinned snapshot: '+row['snapshot'])
        if sha((R/row['destination']).read_bytes())!=row['sha256']:errors.append('unrecorded core adaptation: '+row['destination'])
    for row in provenance.get('new_files',[]):
        if sha((R/row['path']).read_bytes())!=row['sha256']:errors.append('unrecorded core adapter: '+row['path'])
    entry=json.loads((R/'docs/ui-preservation.json').read_text(encoding='utf-8'))
    protected=[]
    for row in entry['files']:
        if '/captionlocales/' in row['path'] or row['path'].startswith('localization/') or row['path'].startswith('ui/src/') and not row['path'].endswith(('CaptionOverlayV2.java','CaptionActions.java')):
            if (R/row['path']).exists():protected.append(row['path'])
    facts['latest_ui_localization_exact_files']=len(protected)
    for row in provenance.get('protected_abi',[]):
        text=(R/row['path']).read_text(encoding='utf-8-sig')
        signatures=sorted(re.sub(r'\s+',' ',m.group(1)).strip() for m in re.finditer(r'(public\s+static\s+[\w<>?,\[\]. ]+\s+\w+\s*\([^)]*\))\s*\{',text))
        if signatures!=row['signatures']:errors.append('public Hook/native ABI changed: '+row['path'])
    for name in ['LICENSE','NOTICE']:
        if not (R/'translation-core/upstream'/name).is_file():errors.append('upstream license absent: '+name)
    for name in ['RebuildApi','RebuildProtocol']:
        original=literals(R/'translation-core/upstream'/(name+'.java.txt'))
        current=literals(R/'translation-core/src/main/java/app/yydarlinker/deepseekcaptions'/(name+'.java'))
        if name=='RebuildProtocol':current.remove('"1.4.0"')
        # Only literals of code removed as dead may disappear; none may be added or altered.
        gone=Counter(original)-Counter(current);added=Counter(current)-Counter(original)
        if added or any(k not in REMOVED_DEAD_LITERALS for k in gone):errors.append('upstream prompt/protocol literals changed: '+name)
    facts['pinned_core']={k:provenance[k] for k in ['repository','branch','commit']}
    facts['prompt_and_protocol_literals_unchanged']=not any('literals changed' in e for e in errors)
    settings=(R/'settings.gradle.kts').read_text(encoding='utf-8-sig')
    if ':caption-core' in settings or 'archive/' in settings:errors.append('retired implementation included in Gradle settings')
    preview=(R/'ui-preview/build.gradle.kts').read_text(encoding='utf-8-sig')
    if 'project(":translation-core")' in preview:errors.append('UI preview depends on translation core')
    if not source_only:
        classes=list((R/'build/n39/gradle/translation-core/intermediates/javac/debug/compileDebugJavaWithJavac/classes').rglob('*.class'))
        if not classes:errors.append('core compiled classes missing')
        for path in classes:
            for value in constants(path):
                if any(PREFIX+n in value or PREFIX.replace('/','.')+n in value for n in CORE_BANNED):errors.append(f'compiled core depends on app/UI: {path.name}: {value}')
        facts['compiled_core_class_count']=len(classes)
        mpps=list((R/'build/n39/gradle/patches/libs').glob('*.mpp'))
        if not mpps:errors.append('Android MPP missing; run :patches:buildAndroid')
        else:
            mpp=max(mpps,key=lambda p:p.stat().st_mtime)
            with zipfile.ZipFile(mpp) as z:
                names=z.namelist();embedded=[n for n in names if n.endswith('.mpe')]
                if 'classes.dex' not in names:errors.append('Manager root classes.dex missing from MPP')
                if len(embedded)!=1:errors.append('expected exactly one embedded MPE')
                else:
                    mpe=z.read(embedded[0]);independent=[p for p in (R/'build').rglob('*.mpe') if p.is_file() and sha(p.read_bytes())==sha(mpe)]
                    if not independent:errors.append('embedded MPE differs from standalone MPE')
                    if mpe.startswith(b'dex\n'):dex=mpe
                    elif zipfile.is_zipfile(io.BytesIO(mpe)):
                        with zipfile.ZipFile(io.BytesIO(mpe)) as ext:dex=b''.join(ext.read(n) for n in ext.namelist() if n.endswith('.dex'))
                    else:dex=b''
                    if not dex:errors.append('extension DEX missing')
                    for dead in DEAD:
                        if dead.encode() in dex:errors.append('retired core still packaged: '+dead)
                    for live in ['RebuildController','RebuildApi','RebuildProtocol','CaptionApplication']:
                        if (PREFIX+live).encode() not in dex:errors.append('production class absent: '+live)
                    for fixture in ['CoreSessionIntegrationTest','PinnedCoreLiveTest','N39UiPreviewActivity','org/robolectric']:
                        if fixture.encode() in dex:errors.append('test/preview implementation packaged in production: '+fixture)
                    facts['android_patch']={'path':mpp.relative_to(R).as_posix(),'sha256':sha(mpp.read_bytes()),'size':mpp.stat().st_size,'manager_dex':True,'embedded_mpe_sha256':sha(mpe),'standalone_mpe':[p.relative_to(R).as_posix() for p in independent],'embedded_mpe_equal_to_standalone':bool(independent)}
                locales=[n for n in names if n.endswith('caption_addon_strings.xml')]
                facts['packaged_locale_files']=len(locales)
                if len(locales)!=14:errors.append(f'expected 14 packaged locale files, got {len(locales)}')
        apks=list((R/'build/n39/gradle/ui-preview/outputs/apk/debug').glob('*.apk'))
        if not apks:errors.append('UI preview APK missing')
        else:
            with zipfile.ZipFile(apks[0]) as z:
                dex=b''.join(z.read(n) for n in z.namelist() if n.endswith('.dex'))
                for name in UI_BANNED:
                    if (PREFIX+name).encode() in dex:errors.append('UI preview contains a translator: '+name)
                if b'android.permission.INTERNET' in z.read('AndroidManifest.xml').replace(b'\x00',b''):errors.append('UI preview requests INTERNET')
            facts['independent_ui_apk']={'path':apks[0].relative_to(R).as_posix(),'sha256':sha(apks[0].read_bytes()),'translation_core_present':False}
    facts['errors']=errors;facts['scope']='Actual sources, independently compiled core constants and package content; not an OEM or semantic-quality certification.'
    return facts
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--source-only',action='store_true');p.add_argument('--output',type=Path,default=R/'validation/boundaries.json');a=p.parse_args()
    result=inspect(a.source_only);a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False,indent=2));raise SystemExit(bool(result['errors']))
