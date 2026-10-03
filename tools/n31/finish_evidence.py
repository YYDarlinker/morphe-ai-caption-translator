from pathlib import Path
import json,hashlib,re,zipfile,struct
R=Path(__file__).resolve().parents[2];V=R/'.verification/n31';O=V/'delivery-records'
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def sha(p):
    with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def log(name):return (O/name).read_text(encoding='utf-8-sig',errors='replace')
def write(name,data):(O/name).write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
full=read(V/'full-final-04/result.json');special=read(V/'special-final/result.json');custom=read(V/'custom-host-final/result.json')
for result in [full,special,custom]:assert result['exit']==0 and not result['reason'] and all(result['totals'][k]==0 for k in ['failures','errors','skipped'])
assert full['totals']['tests']==680 and special['totals']['tests']==70 and custom['totals']['tests']==9
assert full['input_sha']==special['input_sha']==custom['input_sha']
for p,h in read(V/'full-final-04/inputs.json')['files'].items():assert sha(R/p)==h,p
golden=[]
for name in ['legacy-activate.json','legacy-region-golden.json']:
    before=read(V/'n30-chinese-before'/name);after=read(V/'special-final'/name);assert before==after,name
    golden.append({'file':name,'chinese_n30_baseline_exact':True,'groups':18 if isinstance(after,list) else 1})
write('chinese-golden-equality.json',{'rows':golden,'source_commit':'d5ca720ecf0c83349ea232d929ee09b11840c65a','original_english_ui_drift_preserved':'.verification/n30/full-final-05','note':'Chinese baseline uses original methods/samples/assertions in explicit Chinese host configuration; historical English-UI prompt/cache drift is the D-authorized correction, not silently rewritten.'})
old=read(R/'.verification/n30/full-final-05/legacy-region-golden.json');after=read(V/'full-final-04/legacy-region-golden.json')
delta=[{'group':i,'changed_fields':[k for k in a if a[k]!=b[k]]} for i,(a,b) in enumerate(zip(old,after))]
assert all(set(row['changed_fields'])<= {'request_json','identity','cache_key','warm_cache_key'} for row in delta)
write('historical-english-ui-golden-drift.json',{'groups':delta,'authorized':'N31 D: freeze Chinese program default business value, localize only its editor display'})
score=read(R/'scoreboard/results/frozen-baseline.json');assert sorted(score['case_totals'].values())==[4,4,4]
assert all(score['metrics']['invisible_ms'][k]['total']==0 for k in ['pending_translation','event_review','overflow'])
assert 'Ran 27 tests' in log('python27.log') and 'OK' in log('python27.log')
assert '234 keys in all 14 locales' in log('localization.log')
assert read(O/'scope-proof.json')['controller_owner_publication_cas_barriers_exact']
matrix=read(V/'full-final-04/n31-primary-matrix.json');fallback=read(V/'full-final-04/n31-fallback-matrix.json');assert len(matrix)==56 and len(fallback)==14
assert len(read(V/'full-final-04/n31-dynamic-states.json'))==14
for label in ['mpp','mpe','apk']:assert 'DEX_BRANCH_AUDIT_PASS' in log(label+'-branch-audit.txt') and 'invalid_branches=0 dex_problems=0 binding_failures=0' in log(label+'-branch-audit.txt')
assert 'N31_UI_ABI_AND_LIFECYCLE_PASS' in log('composition-dex-audit.log')
assert 'OFFICIAL_CC_CHAIN_PASS rows=128' in log('official-cc-final.log')
for case in ['ai','simplified','memory','ai-simplified','ai-memory','simplified-memory']:
    assert 'STRUCTURE_PASS' in log('combination-'+case+'.log') and 'DEX_AUDIT_PASS' in log('combination-'+case+'-audit.log'),case
assert 'root_dex=true extension=true crc=true' in log('n8-verify.log')
assert "sdkVersion:'28'" in log('aapt-badging.log')
assert all(c['rejected'] for c in read(O/'mutation-sensitivity.json')['controls'])
artifacts=read(O/'artifacts.json')
for row in artifacts:assert sha(Path(row['path']))==row['sha256'].lower() and Path(row['path']).stat().st_size==row['bytes']
apk=Path(artifacts[2]['path'])
with zipfile.ZipFile(apk) as z:
    assert z.testzip() is None
    assert not any(re.match(r'META-INF/.*\.(RSA|DSA|EC|SF)$',n,re.I) for n in z.namelist())
    dexes=[n for n in z.namelist() if re.fullmatch(r'classes\d*\.dex',n)]
with apk.open('rb') as f:
    f.seek(-65557,2);tail=f.read();at=tail.rfind(b'PK\x05\x06');central=struct.unpack_from('<I',tail,at+16)[0];f.seek(central-16);assert f.read(16)!=b'APK Sig Block 42'
metrics=re.search(r'SUMMARY label=n31-apk dex_units=(\d+) classes=(\d+) methods=(\d+) branch_edges=(\d+)',log('apk-branch-audit.txt'));assert metrics
classes_dir=R/'extensions/extension/build/intermediates/javac'; debug=classes_dir/'debug/compileDebugJavaWithJavac/classes/app/yydarlinker/deepseekcaptions';release=classes_dir/'release/compileReleaseJavaWithJavac/classes/app/yydarlinker/deepseekcaptions'
files=list(release.glob('*.class'));assert files
for p in files:assert p.read_bytes()==(debug/p.name).read_bytes(),p.name
write('release-debug-class-equality.json',{'classes':len(files),'byte_identical':True})
summary={'java_full':680,'original_special':68,'chinese_probe_tests':2,'custom_dialog_tests':9,'controlled_concurrency_rounds':400,'input_sha':full['input_sha'],'localization_keys':234,'locales':14,'primary_trees':56,'fallback_trees':14,'generated_addon_nodes':23,'golden_groups':18,'chinese_golden_exact':True,'frozen':[4,4,4],'invisible_ms':0,'seven_combinations':7,'actual_dex_units':len(dexes),'dex_classes':int(metrics[2]),'dex_methods':int(metrics[3]),'dex_branches':int(metrics[4]),'history':read(O/'history-after.json'),'artifacts':artifacts,'phone_and_mother_tongue':'not verified','remote_api_download_new_dependencies_sign_install_uninstall_clear_data_push_publish':0}
write('final-summary.json',summary);print(json.dumps(summary,ensure_ascii=False,indent=2))
