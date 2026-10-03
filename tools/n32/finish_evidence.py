#!/usr/bin/env python3
"""N32 final evidence gate. Missing evidence is PENDING (exit 2), contradictory evidence is FAIL (exit 1), and only a complete evidence set exits 0."""
from __future__ import annotations
import argparse,datetime as dt,hashlib,json,os,re,subprocess,sys,zipfile
from pathlib import Path
from typing import Any
ROOT=Path(__file__).resolve().parents[2];V=ROOT/'.verification/n32';O=V/'delivery-records';REPORT=ROOT/'docs/N32-LOCAL-TEST-BUILD.md'
ARTIFACTS={'mpp':ROOT/'build/local-test/patches-1.3.5-本地测试包-n32.mpp','mpe':ROOT/'build/local-test/extension-1.3.5-本地测试包-n32.mpe','apk':ROOT/'build/n32-composition-final/YouTube-21.16.256-本地测试包-n32-unsigned.apk'}
INDEPENDENT_MPE=ROOT/'extensions/extension/build/morphe/extensions/extension.mpe';SERIALIZED_DEX=ROOT/'build/n32-composition-final/serialized-dex.zip';DEVICE=V.parent/'n32-device-review';HASH_CACHE={}
def rel(path):
 p=Path(path)
 try:return str(p.resolve().relative_to(ROOT.resolve())).replace('/','\\')
 except ValueError:return str(p)
def resolve_path(raw):
 t=str(raw).strip().replace('\\',os.sep);p=Path(t);return p if p.is_absolute() else ROOT/p
def sha(path):
 p=Path(path).resolve();s=p.stat();key=(str(p),s.st_mtime_ns,s.st_size)
 if key not in HASH_CACHE:
  h=hashlib.sha256()
  with p.open('rb') as f:
   for block in iter(lambda:f.read(1024*1024),b''):h.update(block)
  HASH_CACHE[key]=h.hexdigest().upper()
 return HASH_CACHE[key]
def ident(path):return {'path':rel(path),'bytes':Path(path).stat().st_size,'sha256':sha(Path(path))}
def read_json(path):return json.loads(Path(path).read_text(encoding='utf-8-sig'))
def text(path):return Path(path).read_text(encoding='utf-8-sig',errors='replace')
def norm_files(files):return {str(Path(str(k).replace('\\','/'))).replace('/','\\').lower():str(v).lower() for k,v in files.items()}
def zip_dex(path):
 with zipfile.ZipFile(path) as z:
  if z.testzip() is not None:raise ValueError('ZIP CRC failure')
  names=z.namelist()
  if len(names)!=len(set(names)):raise ValueError('duplicate ZIP entries')
  return {n:hashlib.sha256(z.read(n)).hexdigest().upper() for n in names if re.fullmatch(r'classes\d*\.dex',n)}
def result_path(names):
 for name in names:
  p=V/name/'result.json'
  if p.is_file():return p
 return None
class Gate:
 def __init__(self):self.rows=[]
 def add(self,name,status,detail,paths=(),**extra):
  r={'name':name,'status':status,'detail':detail,'evidence':[rel(p) for p in paths]};r.update(extra);self.rows.append(r)
 def pass_(self,name,detail,paths=(),**extra):self.add(name,'PASS',detail,paths,**extra)
 def pending(self,name,detail,paths=(),**extra):self.add(name,'PENDING',detail,paths,**extra)
 def fail(self,name,detail,paths=(),**extra):self.add(name,'FAIL',detail,paths,**extra)
 @property
 def status(self):
  if any(r['status']=='FAIL' for r in self.rows):return 'FAIL'
  if any(r['status']=='PENDING' for r in self.rows):return 'PENDING'
  return 'PASS'
def test_result(g,name,candidates,expected):
 p=result_path(candidates)
 if p is None:g.pending(name,f'缺少结果文件；候选={candidates}');return None,None
 try:
  r=read_json(p);t=r.get('totals',{});actual=(int(r.get('exit',-999)),int(t.get('tests',-1)),int(t.get('failures',-1)),int(t.get('errors',-1)),int(t.get('skipped',-1)));detail=f'exit/tests/failures/errors/skipped={actual}; expected={expected}'
  (g.pass_ if actual==expected else g.fail)(name,detail,[p],result=r);return p,r
 except Exception as e:g.fail(name,f'结果解析失败：{e}',[p]);return p,None
def java_inputs(g,label,result_dir,result):
 if result_dir is None or result is None:g.pending(f'java-inputs:{label}','结果未闭合，不能宣称Java输入已验证');return None,None
 p=result_dir/'inputs.json'
 if not p.is_file():g.pending(f'java-inputs:{label}','缺少Java输入文件SHA清单',[p]);return None,None
 try:
  m=read_json(p);files=norm_files(m.get('files',{}));missing=[];wrong=[]
  for raw,expected in files.items():
   q=resolve_path(raw)
   if not q.is_file():missing.append(raw)
   elif sha(q).lower()!=expected.lower():wrong.append(raw)
  input_sha=str(result.get('input_sha',''))
  if not input_sha or input_sha!=str(m.get('input_sha','')):wrong.append('<input_sha>')
  detail=f'files={len(files)} input_sha={input_sha} missing={len(missing)} mismatched={len(wrong)}'
  (g.fail if (not files or missing or wrong) else g.pass_)(f'java-inputs:{label}',detail,[p],file_count=len(files),input_sha=input_sha)
  return files,input_sha
 except Exception as e:g.fail(f'java-inputs:{label}',f'输入SHA清单解析失败：{e}',[p]);return None,None
def check_before(g):
 expected={'typed':(DEVICE/'device-input.json',199529446,'FB7B28B425A3DCE8884EF9BBFEB6EF8D93FC7B782B9E2E24EDC472D9A9116C38'),'bad_token':(DEVICE/'navigation-current-input.json',199529446,'BFF42C488842FE793BCEA5028208D23FE47F831B867480676FAA99AB86E2E111')};out={}
 for key,(p,size,digest) in expected.items():
  if not p.is_file():g.pending(f'before-apk:{key}','缺少before APK身份JSON',[p]);continue
  try:
   row=read_json(p);got=(row.get('bytes'),str(row.get('sha256','')).upper())
   if got!=(size,digest):g.fail(f'before-apk:{key}',f'bytes/sha={got}; expected={(size,digest)}',[p])
   else:g.pass_(f'before-apk:{key}',f'bytes={size}; sha256={digest}',[p],identity=row);out[key]=row
  except Exception as e:g.fail(f'before-apk:{key}',f'身份JSON解析失败：{e}',[p])
 crash=DEVICE/'phone-crash-key-lines.txt'
 (g.pass_ if crash.is_file() and 'VerifyError' in text(crash) else g.pending)('before-crash:typed','SDK37原N31 before含ART VerifyError',[crash])
 bad=DEVICE/'withdrawn-navigation-root-cause.json'
 if not bad.is_file():g.pending('before-crash:window-owner','缺少BadToken before根因JSON',[bad])
 else:
  try:
   row=read_json(bad);ok='BadTokenException' in str(row.get('crash','')) and row.get('language_handler_reached') is True
   (g.pass_ if ok else g.fail)('before-crash:window-owner','真实语言行点击进入showLanguages后token=null',[bad])
  except Exception as e:g.fail('before-crash:window-owner',f'BadToken JSON解析失败：{e}',[bad])
 return out
def check_chinese(g):
 rows=[]
 for name in ('legacy-activate.json','legacy-region-golden.json'):
  before=V.parent/'n31/n30-chinese-before'/name;after=V/'special-final'/name
  if not before.is_file() or not after.is_file():g.pending(f'chinese-golden:{name}','中文before/after raw文件缺失',[before,after]);continue
  equal=before.read_bytes()==after.read_bytes();groups=None
  try:
   parsed=json.loads(after.read_text(encoding='utf-8-sig'));groups=len(parsed) if isinstance(parsed,list) else 1
  except Exception:pass
  rows.append({'file':name,'before':ident(before),'after':ident(after),'raw_equal':equal,'groups':groups})
  if not equal:g.fail(f'chinese-golden:{name}','raw bytes不相等',[before,after])
  elif name=='legacy-region-golden.json' and groups!=18:g.fail(f'chinese-golden:{name}',f'raw相等但groups={groups}，期望18',[before,after])
  else:g.pass_(f'chinese-golden:{name}',f'raw exact；groups={groups}',[before,after])
 return {'rows':rows}
def check_frozen(g):
 p=ROOT/'scoreboard/results/frozen-baseline.json'
 if not p.is_file():g.pending('frozen-baseline','缺少冻结基线',[p]);return {}
 try:
  d=read_json(p);totals=d.get('case_totals');inv=d.get('metrics',{}).get('invisible_ms',{});zero={k:inv.get(k,{}).get('total') for k in ('pending_translation','event_review','overflow')}
  (g.pass_ if totals=={'通过':4,'失败':4,'未验证':4} and all(v==0 for v in zero.values()) else g.fail)('frozen-baseline',f'case_totals={totals}; invisible_ms={zero}',[p]);return {'case_totals':totals,'invisible_ms':zero}
 except Exception as e:g.fail('frozen-baseline',f'冻结基线解析失败：{e}',[p]);return {}
def check_acceptance(g):
 manifest=V/'input-manifest.json';acceptance=ROOT/'ACCEPTANCE.md'
 if not manifest.is_file() or not acceptance.is_file():g.pending('acceptance-diff','缺少ACCEPTANCE或input-manifest',[manifest,acceptance]);return
 try:
  files=read_json(manifest).get('files',{});expected=next((v for k,v in files.items() if str(k).replace('\\','/').lower()=='acceptance.md'),None);actual=hashlib.sha256(acceptance.read_bytes()).hexdigest();diff=[]
  for args in (('diff','--name-only','--','ACCEPTANCE.md'),('diff','--cached','--name-only','--','ACCEPTANCE.md')):
   p=subprocess.run(['git',*args],cwd=ROOT,capture_output=True,text=True,check=False);diff += [x for x in p.stdout.splitlines() if x.strip()]
  (g.pass_ if expected is not None and actual.lower()==str(expected).lower() and not diff else g.fail)('acceptance-diff',f'expected={expected}; actual={actual}; git_diff={diff}',[manifest,acceptance])
 except Exception as e:g.fail('acceptance-diff',f'ACCEPTANCE检查失败：{e}',[manifest,acceptance])
def check_type(g):
 paths=[O/n for n in ('settings-regression.log','settings-hook-regression.log','settings-final.log','settings-confirmation-regression.log','composition-dex-audit.log')];existing=[p for p in paths if p.is_file()]
 if not existing:g.pending('settings-type-gate','缺少SettingsHookRegression/最终composition audit',paths);return {}
 joined='\n'.join(text(p) for p in existing);neg=re.search(r'N32_SETTINGS_SERIALIZED_REGRESSION_PASS\s+negatives=(\d+)',joined);seams=re.search(r'typed_settings_seams=(\d+)',joined);good=bool(neg and int(neg.group(1))>=8 and seams and int(seams.group(1))>=5 and 'N32_SETTINGS_COMPOSITION_PASS' in joined and 'N32_UI_ABI_AND_LIFECYCLE_PASS' in joined);detail=f'typed_settings_seams={seams.group(1) if seams else "missing"}; negatives={neg.group(1) if neg else "missing"}'
 (g.pass_ if good else g.pending)('settings-type-gate',detail+'; final composition/lifecycle markers present' if good else detail+'; 等待完整typed seam/serialized negative evidence',existing)
 return {'seams':int(seams.group(1)) if seams else None,'negatives':int(neg.group(1)) if neg else None}
def check_packages(g):
 missing=[p for p in ARTIFACTS.values() if not p.is_file()]
 if missing:g.pending('package-files','MPP/MPE/APK未全部生成',missing);return {}
 out={'artifacts':{k:ident(p) for k,p in ARTIFACTS.items()}}
 try:
  with zipfile.ZipFile(ARTIFACTS['mpp']) as z:
   if z.testzip() is not None:raise ValueError('MPP CRC failure')
   names=z.namelist()
   if len(names)!=len(set(names)):raise ValueError('MPP duplicate entries')
   embedded=z.read('extensions/extension.mpe');root_dex=z.read('classes.dex')
   if not root_dex.startswith(b'dex\n'):raise ValueError('MPP classes.dex不是DEX')
  local=ARTIFACTS['mpe'].read_bytes();independent=INDEPENDENT_MPE.read_bytes()
  (g.pass_ if embedded==local==independent else g.fail)('package-mpe-identity','MPP embedded MPE = local-test MPE = independent build MPE',[ARTIFACTS['mpp'],ARTIFACTS['mpe'],INDEPENDENT_MPE])
  with zipfile.ZipFile(ARTIFACTS['apk']) as z:
   if z.testzip() is not None:raise ValueError('APK CRC failure')
   names=z.namelist();signed=[n for n in names if re.match(r'META-INF/.*\.(RSA|DSA|EC|SF)$',n,re.I)]
   if signed:raise ValueError('unsigned APK has signature files')
   apk_dex={n:hashlib.sha256(z.read(n)).hexdigest().upper() for n in names if re.fullmatch(r'classes\d*\.dex',n)}
  if not SERIALIZED_DEX.is_file():g.pending('package-apk-serialized-dex','缺少serialized-dex.zip，不能绑定APK DEX',[SERIALIZED_DEX])
  elif apk_dex!=zip_dex(SERIALIZED_DEX):g.fail('package-apk-serialized-dex','APK classes*.dex与serialized-dex.zip不相等',[ARTIFACTS['apk'],SERIALIZED_DEX])
  else:g.pass_('package-apk-serialized-dex',f'APK DEX与serialized-dex逐entry相等；dex_units={len(apk_dex)}',[ARTIFACTS['apk'],SERIALIZED_DEX])
  g.pass_('package-zip-crc-unsigned','MPP/APK CRC通过且APK无签名文件',[ARTIFACTS['mpp'],ARTIFACTS['apk']])
 except Exception as e:g.fail('package-structure',f'最终包结构检查失败：{e}',list(ARTIFACTS.values()))
 return out
def check_audits(g,package_data):
 out={}
 for label in ('mpp','mpe','apk'):
  p=O/f'{label}-branch-audit.txt'
  if not p.is_file():g.pending(f'dex-branch:{label}','缺少最终包DEX分支审计',[p]);continue
  s=text(p);m=re.search(r'SUMMARY\s+label=[^\s]+\s+dex_units=(\d+)\s+classes=(\d+)\s+methods=(\d+)\s+branch_edges=(\d+)',s);ok=bool(m and 'DEX_BRANCH_AUDIT_PASS' in s and 'invalid_branches=0' in s and 'dex_problems=0' in s and 'binding_failures=0' in s)
  if not ok:g.fail(f'dex-branch:{label}','DEX分支审计缺PASS或存在非零问题',[p])
  else:
   row={'dex_units':int(m.group(1)),'classes':int(m.group(2)),'methods':int(m.group(3)),'branch_edges':int(m.group(4))};out.setdefault('branch_counts',{})[label]=row;g.pass_(f'dex-branch:{label}',str(row),[p])
 p=O/'composition-dex-audit.log'
 if not p.is_file():g.pending('composition-dex-audit','缺少最终APK接口/生命周期审计',[p])
 elif 'N32_UI_ABI_AND_LIFECYCLE_PASS' in text(p) and 'DEX_AUDIT_PASS' in text(p):g.pass_('composition-dex-audit','5 typed settings seams/官方语言链/生命周期DEX审计通过',[p])
 else:g.fail('composition-dex-audit','最终APK审计marker不完整',[p])
 p=O/'official-cc-final.log'
 if not p.is_file():g.pending('official-cc','缺少官方CC最终审计',[p])
 elif 'OFFICIAL_CC_CHAIN_PASS' in text(p):g.pass_('official-cc','官方CC最终链通过',[p])
 else:g.fail('official-cc','官方CC日志缺PASS marker',[p])
 p=O/'n8-verify.log'
 if not p.is_file():g.pending('n8-verify','缺少N8/root DEX/CRC最终日志',[p])
 elif 'root_dex=true extension=true crc=true' in text(p):g.pass_('n8-verify','root DEX/extension/CRC通过',[p])
 else:g.fail('n8-verify','N8日志缺root_dex/extension/CRC marker',[p])
 b,a=O/'bundle-structure.json',O/'apk-structure.json'
 if not b.is_file() or not a.is_file():g.pending('resources-structure','缺少最终MPP/APK字节结构报告',[b,a])
 else:
  try:
   br,ar=read_json(b),read_json(a);good=br.get('extension_identical') is True and br.get('crc') is True and br.get('validation_leak') is False and len(br.get('locales',[]))==14 and ar.get('crc') is True and ar.get('validation_leak') is False
   (g.pass_ if good else g.fail)('resources-structure','CRC/14 locales/资源与validation leak检查通过' if good else 'bundle/apk structure字段不满足最终合同',[b,a])
  except Exception as e:g.fail('resources-structure',f'结构报告解析失败：{e}',[b,a])
 aapt,badging=O/'aapt-preference-proof.json',O/'aapt-badging.txt'
 if not aapt.is_file() or not badging.is_file():g.pending('aapt-minsdk28','缺少绑定最终APK的aapt证明',[aapt,badging])
 else:
  try:
   ar=read_json(aapt);apk_id=package_data.get('artifacts',{}).get('apk',{});good=ar.get('min_sdk')==28 and str(ar.get('actual_apk_sha256','')).upper()==apk_id.get('sha256','') and re.search(r"^sdkVersion:'28'$",text(badging),re.M) and "versionName='21.16.256'" in text(badging)
   (g.pass_ if good else g.fail)('aapt-minsdk28','当前APK fresh aapt：minSdk28，YouTube 21.16.256' if good else 'aapt未绑定当前APK或minSdk/version不符',[aapt,badging])
  except Exception as e:g.fail('aapt-minsdk28',f'aapt证明解析失败：{e}',[aapt,badging])
 p=O/'verify-bundle-provenance.json'
 if not p.is_file():g.pending('verify-bundle','缺少verify_bundle provenance',[p])
 else:
  try:
   pr=read_json(p);mpp_id=package_data.get('artifacts',{}).get('mpp',{});good=pr.get('path_adaptation_only') is True and str(pr.get('actual_artifact_sha256','')).upper()==mpp_id.get('sha256','') and pr.get('actual_artifact_bytes')==mpp_id.get('bytes')
   (g.pass_ if good else g.fail)('verify-bundle','verify_bundle通过且绑定当前N32 MPP' if good else 'verify_bundle provenance与当前MPP不一致',[p])
  except Exception as e:g.fail('verify-bundle',f'verify_bundle provenance解析失败：{e}',[p])
 return out
def check_public(g,package_data):
 out={};roots,targeted,metadata=O/'public-roots.json',O/'public-contract-targeted.json',O/'metadata.log'
 if not roots.is_file() or not targeted.is_file() or not metadata.is_file():g.pending('public-roots-metadata','缺少2根/metadata/负例证据',[roots,targeted,metadata])
 else:
  try:
   r,t,mt=read_json(roots),read_json(targeted),text(metadata);expected=['AI caption translator','Remember caption selection'];catalog=t.get('catalog_negative_controls',{});runtime=t.get('runtime_negative_controls',[]);good=r.get('public_roots')==expected and r.get('count')==2 and r.get('actual_root_dex') is True and r.get('embedded_standalone_mpe_equal') is True and r.get('obsolete_flag_absent') is True and all(v=='rejected' for v in catalog.values()) and len(runtime)>=5 and all(x.get('rejected') is True for x in runtime) and 'Morphe metadata OK' in mt
   (g.pass_ if good else g.fail)('public-roots-metadata','AI + Remember恰好2根；metadata/负例通过' if good else '2根/metadata/negative controls合同不完整',[roots,targeted,metadata]);out['public_roots']=expected if good else None
  except Exception as e:g.fail('public-roots-metadata',f'公开根证据解析失败：{e}',[roots,targeted,metadata])
 for name,expected in (('combinations',3),('same-process',3)):
  p=O/f'{name}-contract.json'
  if not p.is_file():g.pending(f'combinations:{name}','缺少serialized组合合同',[p]);continue
  try:
   c=read_json(p);rows=c.get('rows',[]);good=c.get('passed')==expected and c.get('nonempty_combinations')==expected and len(rows)>=expected
   if name=='same-process':good=good and c.get('session_count')==3 and c.get('same_loaded_patch_instances') is True and c.get('retained_feature_state_rejected') is True
   else:
    final=[r for r in rows if r.get('label')=='ai-memory' and r.get('final_apk')];apk_id=package_data.get('artifacts',{}).get('apk',{});good=good and c.get('final_ai_memory_reused') is True and c.get('resource_compilation_count_total')==1 and bool(final) and final[-1]['final_apk'].get('sha256','').upper()==apk_id.get('sha256','') and final[-1]['final_apk'].get('bytes')==apk_id.get('bytes')
   (g.pass_ if good else g.fail)(f'combinations:{name}',f'serialized nonempty combinations={expected}' if good else '组合合同字段或最终APK绑定不完整',[p])
   if good:out[name]=c
  except Exception as e:g.fail(f'combinations:{name}',f'组合合同解析失败：{e}',[p])
 return out
def check_history(g):
 p=O/'history-after.json'
 if not p.is_file():g.pending('history','缺少历史保护报告',[p]);return {}
 try:
  d=read_json(p);good=d.get('original_historical_files')==14000 and d.get('unchanged')==14000 and d.get('external_inputs_unchanged')==8 and d.get('original_n31_and_bad_bundles_and_device_before_preserved') is True
  (g.pass_ if good else g.fail)('history','14000历史文件/8外部输入保持，before/坏包保留' if good else f'history-after={d}',[p]);return d
 except Exception as e:g.fail('history',f'history-after解析失败：{e}',[p]);return {}
def check_locale_rounds(g,full_pass,special_pass):
 out={};p=O/'localization-final.log'
 if not p.is_file():g.pending('localization','缺少234×14本地化日志',[p])
 else:
  m=re.search(r'(\d+) keys in all (\d+) locales',text(p))
  if m and (int(m.group(1)),int(m.group(2)))==(234,14):g.pass_('localization','234 keys in all 14 locales',[p]);out.update(keys=234,locales=14)
  else:g.fail('localization','本地化分母不是234×14',[p])
 source=ROOT/'extensions/extension/src/test/java/app/yydarlinker/deepseekcaptions/SchedulerLifecycleRegressionTest.java'
 if source.is_file() and 'for(int round=0;round<200;round++)' in text(source) and full_pass and special_pass:g.pass_('concurrency-rounds','full 200 + special 200 = 400 controlled rounds',[source]);out['rounds']=400
 else:g.pending('concurrency-rounds','缺少full/special 200轮可核对证据',[source])
 return out
def final_wms_path():
 paths=list((V/'ui-host').glob('after-final*/ui-events.json'));return max(paths,key=lambda p:p.stat().st_mtime_ns) if paths else None
def check_wms(g,package_data):
 out={'physical_sdk37_after':False};before=V/'ui-host/before-typed-safe-attempt05/ui-events.json'
 if not before.is_file():g.pending('wms-before','缺少SDK35 BadToken before事件',[before])
 else:
  try:
   b=read_json(before);events=b.get('events',[]);names=[e.get('event') for e in events];terminal=events[-1];good=b.get('mode')=='before' and 'actual_list_click' in names and terminal.get('event')=='FAIL' and 'BadTokenException' in terminal.get('error','');(g.pass_ if good else g.fail)('wms-before','真实ListView点击→BadToken before已保留' if good else 'before WMS事件不符合真实BadToken合同',[before])
  except Exception as e:g.fail('wms-before',f'before WMS事件解析失败：{e}',[before])
 after=final_wms_path()
 if after is None:g.pending('wms-after','尚未生成final source SDK35 after；不能用provisional/fallback冒充',[V/'ui-host']);return out
 host=after.parent/'host-inputs.json'
 try:
  a=read_json(after);events=a.get('events',[]);names={e.get('event') for e in events};terminal=events[-1] if events else {};bound=False
  if host.is_file():
   h=read_json(host);source=resolve_path(h.get('source',''));apk=ARTIFACTS['apk'];bound=source.resolve()==apk.resolve() and str(h.get('source_sha256','')).upper()==package_data.get('artifacts',{}).get('apk',{}).get('sha256','') and h.get('standalone_fallback') is False
  required={'actual_list_click','language_dialog_visible','dialog_cancel','dialog_save'};good=a.get('sdk')==35 and a.get('mode')=='navigation' and terminal.get('event')=='PASS' and required.issubset(names) and not any(e.get('event')=='FAIL' for e in events) and bound
  (g.pass_ if good else g.fail)('wms-after','final source SDK35真实列表→14项Dialog→Cancel/Save PASS，host绑定当前APK' if good else f'final WMS after不完整或未绑定当前APK；bound={bound}',[after,host])
  if good:out.update({'path':rel(after),'host_inputs':rel(host),'sdk':a.get('sdk'),'events':sorted(names),'sha256':sha(after)})
 except Exception as e:g.fail('wms-after',f'after WMS事件解析失败：{e}',[after,host])
 return out
def render(summary):
 if not REPORT.is_file():return
 begin,end='<!-- N32_DYNAMIC_SUMMARY_BEGIN -->','<!-- N32_DYNAMIC_SUMMARY_END -->';source=REPORT.read_text(encoding='utf-8')
 if begin not in source or end not in source:return
 mark=chr(96);lines=[begin,'',f'**finish_evidence.py 状态：{mark}{summary["status"]}{mark}**（生成时间：{summary["generated_at"]}）。缺少证据只显示 PENDING，不会写成 PASS。','', '| 检查 | 状态 | 结果 | 证据 |','|---|---|---|---|']
 for row in summary['checks']:
  detail=str(row.get('detail','')).replace('|','\|').replace('\n',' ');evidence=', '.join(f'{mark}{p}{mark}' for p in row.get('evidence',[])) or '—';lines.append(f'| {row["name"]} | {mark}{row["status"]}{mark} | {detail} | {evidence} |')
 lines += ['',end];REPORT.write_text(re.sub(re.escape(begin)+r'.*?'+re.escape(end),'\n'.join(lines),source,count=1,flags=re.S),encoding='utf-8')
def main():
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--no-report-write',action='store_true');args=parser.parse_args();g=Gate();before=check_before(g);chinese=check_chinese(g);frozen=check_frozen(g);check_acceptance(g);type_gate=check_type(g)
 full_p,full=test_result(g,'java-full-final',['full-final-03','full-final-02','full-final-04'],(0,695,0,0,0));special_p,special=test_result(g,'java-special-final',['special-final'],(0,70,0,0,0));main_p,main=test_result(g,'java-main-final',['main-final'],(0,11,0,0,0));custom_p,custom=test_result(g,'java-custom-final',['custom-final','custom-final-01','custom-host-final','custom-host-final-01'],(0,9,0,0,0));old_p,old=test_result(g,'java-full-final-01-stored',['full-final-01','full-final'],(1,695,2,0,0))
 manifests={};input_sha={}
 for label,path,result in (('full',full_p,full),('special',special_p,special),('main',main_p,main),('custom',custom_p,custom)):
  manifests[label],input_sha[label]=java_inputs(g,label,path.parent if path else None,result)
 common=[manifests[k] for k in ('full','special','custom')]
 if any(x is None for x in common):g.pending('java-inputs:cross-run','full/special/custom全部完成后才可宣称同一输入')
 elif not (common[0]==common[1]==common[2]) or not (input_sha['full']==input_sha['special']==input_sha['custom']):g.fail('java-inputs:cross-run','full/special/custom输入文件或input_sha不一致')
 else:g.pass_('java-inputs:cross-run',f'full/special/custom同一Java输入；input_sha={input_sha["full"]}')
 tests={'full':{'path':rel(full_p) if full_p else None,'tests':full.get('totals',{}).get('tests') if full else None,'input_sha':input_sha.get('full')},'special':{'path':rel(special_p) if special_p else None,'tests':special.get('totals',{}).get('tests') if special else None,'input_sha':input_sha.get('special')},'main':{'path':rel(main_p) if main_p else None,'tests':main.get('totals',{}).get('tests') if main else None,'input_sha':input_sha.get('main')},'custom':{'path':rel(custom_p) if custom_p else None,'tests':custom.get('totals',{}).get('tests') if custom else None,'input_sha':input_sha.get('custom')},'stored_failed_full':rel(old_p) if old_p else None}
 package_data=check_packages(g);audits=check_audits(g,package_data);public=check_public(g,package_data);history=check_history(g);rounds=check_locale_rounds(g,bool(full and full.get('exit')==0 and full.get('totals',{}).get('failures')==0),bool(special and special.get('exit')==0 and special.get('totals',{}).get('failures')==0));wms=check_wms(g,package_data)
 summary={'schema':'n32-final-evidence-v1','status':g.status,'generated_at':dt.datetime.now(dt.timezone.utc).isoformat(timespec='seconds'),'root':str(ROOT),'checks':g.rows,'before_apks':before,'chinese':chinese,'frozen':frozen,'type_gate':type_gate,'tests':tests,'packages':package_data,'audits':audits,'public_roots_and_combinations':public,'history':history,'localization_and_rounds':rounds,'wms':wms,'limitations':{'physical_sdk37_after':False,'general_before_visual_source':'UNKNOWN / not independently captured before BadToken','native_youtube_video_startup':False,'provider_or_remote_translation':False,'user_install_or_sign_or_phone_write':False}}
 O.mkdir(parents=True,exist_ok=True);out=O/'final-summary.json';out.write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
 if not args.no_report_write:render(summary)
 print(json.dumps({'status':summary['status'],'summary':rel(out),'checks':len(g.rows)},ensure_ascii=False,indent=2));return 0 if summary['status']=='PASS' else (2 if summary['status']=='PENDING' else 1)
if __name__=='__main__':raise SystemExit(main())
