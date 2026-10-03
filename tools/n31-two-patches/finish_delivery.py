from pathlib import Path
import hashlib,json,re,subprocess,zipfile,struct
R=Path(__file__).resolve().parents[2];V=R/'.verification/n31-two-patches'
def sha(p):
    with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def log(name):return (V/name).read_text(encoding='utf-8-sig',errors='replace')
start=read(V/'input-manifest.json');full=read(V/'full/result.json');assert full['exit']==0 and full['totals']=={'tests':682,'failures':0,'errors':0,'skipped':0}
for p,h in read(V/'full/inputs.json')['files'].items():assert sha(R/p)==h,p
for row in start['history']:assert sha(R/row['path'])==row['sha256'] and (R/row['path']).stat().st_size==row['bytes'],row['path']
allowed_main={'CaptionAddonSupport.java','CaptionLanguageMetadata.java','CaptionLanguageSelection.java','NativeCaptionBridge.java'}
for p,h in start['files'].items():
    if '/src/main/java/' in p and Path(p).name not in allowed_main:assert sha(R/p)==h,p
for p in ['ACCEPTANCE.md','patches-bundle.json','localization/catalog.json']:
    assert sha(R/p)==start['files'][p],p
for p,h in start['files'].items():
    if p.startswith(('scoreboard/','patches/src/main/resources/captionlocales/')):assert sha(R/p)==h,p
for name in ['legacy-region-golden.json','legacy-activate.json']:
    assert read(V/'full'/name)==read(R/'.verification/n31/full-final-04'/name),name
assert 'Ran 12 tests' in log('metadata-tests.log') and 'OK' in log('metadata-tests.log')
for case in ['ai','memory']:assert 'STRUCTURE_PASS' in log(f'combination-{case}.log') and 'DEX_AUDIT_PASS' in log(f'combination-{case}-audit.log')
assert 'COMPOSITION_PASS' in log('composition-final.log') and 'N31_UI_ABI_AND_LIFECYCLE_PASS' in log('composition-dex-audit.log')
for label in ['mpp','mpe','apk']:assert 'invalid_branches=0 dex_problems=0 binding_failures=0' in log(label+'-branch-audit.txt')
assert "sdkVersion:'28'" in log('aapt-badging.log')
assert 'root_dex=true extension=true crc=true' in log('n8-verify.log')
public=read(V/'public-roots-check.json');assert len(public['public_names'])==2
paths=[R/'build/local-test/patches-1.3.5-本地测试包-n31-two-patches.mpp',R/'build/local-test/extension-1.3.5-本地测试包-n31-two-patches.mpe',R/'build/n31-two-patches-composition-final/YouTube-21.16.256-本地测试包-n31-two-patches-unsigned.apk']
artifacts=[{'path':str(p),'bytes':p.stat().st_size,'sha256':sha(p)} for p in paths]
with zipfile.ZipFile(paths[2]) as z:
    assert z.testzip() is None;assert not any(re.match(r'META-INF/.*\.(RSA|DSA|EC|SF)$',name,re.I) for name in z.namelist());dexes=[name for name in z.namelist() if re.fullmatch(r'classes\d*\.dex',name)]
with paths[2].open('rb') as f:
    f.seek(-65557,2);tail=f.read();i=tail.rfind(b'PK\x05\x06');central=struct.unpack_from('<I',tail,i+16)[0];f.seek(central-16);assert f.read(16)!=b'APK Sig Block 42'
metrics=re.search(r'SUMMARY label=n31-two-patches-apk dex_units=(\d+) classes=(\d+) methods=(\d+) branch_edges=(\d+)',log('apk-branch-audit.txt'));assert metrics
summary={'execution_head':start['head'],'public_patches':public['public_names'],'java_tests':682,'metadata_tests':12,'new_ai_menu_tests':2,'combinations':3,'dex_units':len(dexes),'dex_classes':int(metrics[2]),'dex_methods':int(metrics[3]),'dex_branches':int(metrics[4]),'localization_unchanged':True,'core_request_paging_scheduling_overlay_unchanged':True,'chinese_golden_exact_to_n31':True,'historical_n31_files_unchanged':len(start['history']),'source_sha':full['input_sha'],'artifacts':artifacts,'sign_install_push_publish_remote_api_download_new_dependencies':0}
(V/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
rows='\n'.join(f"| {Path(a['path']).name} | {a['bytes']:,} | `{a['sha256'].upper()}` |" for a in artifacts)
report=f'''# N31 后续：删除冗余独立语言补丁

日期：2026-10-03（Asia/Shanghai）。本次依据用户明确指令删除第二个补丁，不新增N32任务序列。执行HEAD `{start['head']}`，原N31源码/产物/报告完整保留。

14种自动翻译语言多选与原生菜单通用接缝已经属于AI caption translator；独立Add Simplified Chinese to auto-translate属于冗余入口。本次真正删除其public bytecodePatch定义、CaptionFeatures.simplified、installed标记和独立强制插入zh-Hans路径。简体中文能力保留在AI设置多选中，与其余13种语言一致；默认空集合不被强制追加，用户selected_codes不迁移、不清除。AI安装许可与运行时开关分离：选择AI补丁后，即使AI开关关闭，已选择的菜单语言仍加入原生自动翻译列表。

当前正式MPP自身加载、生成patches-list.json与发行元数据校验均只包含两个根：

1. **AI caption translator**：AI字幕翻译、14种自动翻译语言多选（包含简体中文）及现有本地化设置。
2. **Remember caption selection**：记忆原生字幕选择，仍可独立选用；没有AI时不注入语言或触发翻译API。

README/升级选择说明及本地发行校验已同步。旧版本1.2.x/1.3.4三根记录作为历史保留；本次未发布，旧公开下载URL/日期和patches-bundle.json逐字不变。

## 验证

- Java **682/682**，failure/error/skipped0（原680+两项AI独占菜单/简体中文去重验证）；实际14UI语言/控件矩阵随原全量继续通过。
- 本地发行元数据 **12/12**，含故意加回旧独立补丁必须拒绝的负例。正式MPP含Android根DEX；已从实际MPP生成两个public名称，旧补丁显示名和simplifiedInstalled从DEX消失。
- 2根对应3个非空组合：**AI-only / Remember-only / AI+Remember，3/3**。都由新的MPP自身＋官方1.45.0＋原版YouTube21.16.256实际Patcher装配并序列化审计；AI+Remember形成最终APK。
- 最终 **{len(dexes)}DEX / {int(metrics[2]):,}类 / {int(metrics[3]):,}方法 / {int(metrics[4]):,}分支**；invalid/problem/binding0；N31有效语言/官方UI接缝、原native clone/去重/hook校验继续通过。
- CRC/资源/内嵌MPE=独立MPE、aapt/minSdk28、verify_bundle/N8Verify通过。原中文18golden/activate与N31当前验证逐字段相等，翻译请求/分页/调度/overlay生产源码逐字不变。
- 原N31交付及{len(start['history'])}份本次捕获历史文件SHA/字节不变，ACCEPTANCE/冻结scoreboard/234×14资源不变；旧输入保留。

## 新的独立本地交付

| 文件 | 字节 | SHA256 |
|---|---:|---|
{rows}

证据：`.verification/n31-two-patches`；组合与最终审计复跑入口：`tools/n31-two-patches`。原N31路径没有覆盖；本轮新包后缀`n31-two-patches`。MPP内部两个功能补丁，与MPP/MPE/APK三种交付格式是不同的计数。

未签名、未安装、未卸载、未清用户数据、未推送、未发布；远程API/下载/新增依赖均0。手机与母语语义没有新增验收。用户仍可保持系统语言进行原N31短复验；本次任务完成即停。核心源码提交及锚点以本地身份补记登记。
'''
(R/'docs/N31-PATCH-CONSOLIDATION.md').write_text(report,encoding='utf-8')
state=R/'docs/PROJECT-STATE.md';text=state.read_text(encoding='utf-8');start_line=text.index('> 最后更新：');end=text.index('\n',start_line);text=text[:start_line]+'> 最后更新：2026-10-03。用户明确要求删除第二个冗余补丁；N31后续已将public列表收敛为AI caption translator＋Remember caption selection两根，14语言菜单（含简体中文）由AI独占，AI关闭仍可使用。682全量/12元数据/3组合及全部DEX通过；新三件套后缀n31-two-patches，原N31历史不覆盖。未签名未安装未发布，详见§4am/N31-PATCH-CONSOLIDATION。'+text[end:]
text+=f'''\n\n## 4am. 用户指令：删除冗余独立语言补丁（2026-10-03）\n\n本轮用户明确覆写旧三根冻结：自动翻译14语言功能已在AI caption translator中，直接删除Add Simplified Chinese to auto-translate。已删public root、CaptionFeatures.simplified、simplifiedInstalled及独立强制zh-Hans加入；简体中文和其余13语言全部沿AI设置保存集合提供，AI开关关闭仍有效，默认空集合/用户selected_codes不变。保留Remember独立原生记忆；2根3个非空组合通过。README、生成patches-list及本地发行校验一致，旧公开URL/日期/patches-bundle不变，不发布。\n\n682/682 Java（680+2菜单归属/简体去重）、12/12元数据，原N31语言控件/中文18golden/activate继续相等；翻译调度/分页/cache/overlay源码逐字不变。最终{len(dexes)}DEX/{int(metrics[2])}类/{int(metrics[3])}方法/{int(metrics[4])}分支与公开UI/native hooks、CRC/resources/aapt/min28/verify_bundle/N8Verify通过；MPP自身只有AI和Remember，内嵌MPE独立等值，旧root名与installed标记从DEX删除。新交付build/local-test/*-n31-two-patches及build/n31-two-patches-composition-final；全SHA/字节见N31-PATCH-CONSOLIDATION。{len(start['history'])}份历史捕获SHA/字节不变，旧N31全量交付保留。未签名/安装/清数据/推送/发布，远程API/下载/新依赖0。完成即停；实际核心commit/anchor另补记。\n'''
state.write_text(text,encoding='utf-8');external=Path(r'C:\Users\14776\Documents\kimi\tasks\2026-09-29\00-35-52-ec1208d7\PROJECT-STATE.md');external.write_bytes(state.read_bytes())
print(json.dumps(summary,ensure_ascii=False,indent=2))