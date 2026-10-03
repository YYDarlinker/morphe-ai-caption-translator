from pathlib import Path
import json,hashlib
R=Path(__file__).resolve().parents[2];O=R/'.verification/n31/delivery-records'
s=json.loads((O/'final-summary.json').read_text(encoding='utf-8'))
rows='\n'.join(f"| {Path(a['path']).name} | {a['bytes']:,} | `{a['sha256'].upper()}` |" for a in s['artifacts'])
report=f'''# N31 本地测试交付：运行时语言、说明与多选去状态

日期：2026-10-03（Asia/Shanghai）。单执行者；本地工程交付已验证，手机观感和14语种母语语义未验收。未签名、未安装、未卸载、未清用户数据、未推送、未发布；远程翻译API/下载/新增依赖均0。

## 基线与根因

实际开工 HEAD `fa103607582d670766d0b949339a84809afaa277`，产品 `d5ca720ecf0c83349ea232d929ee09b11840c65a` / `anchor/n30-d5ca720`，仅docs差异。两份 PROJECT-STATE 开工SHA一致 `8DB8BA40E0819CDAC250CFCE6915CE3C0929808C4CF9C817E70BAD6A3F5D7154`，无覆盖。用户规划docs、本卡及 `.verification/n31-planning` 草稿保留。源码提交与锚点见身份补记。

N30把 ResourceUtils 方法名当成覆盖语言保证；真实1.45方法可从原Activity读字符串，getStringByLocale及getSystemStringByLocale的方法体没有应用locale参数。XML加载后标题/summary被冻结，自定义编辑器/滑轨/预览/诊断缺统一重绑。受控before保留原Activity中文、官方EnumSetting/AppLanguage接口形态且显式ja，N30确读中文；不是把Activity预先换ja绕过问题。没有读取用户手机的线程/Context或内部选项快照，设备具体分支未采样。

## 实现

- CaptionUiLocale读取官方 MORPHE_LANGUAGE / EnumSetting.get / AppLanguage.getLocale。显式覆盖优先；DEFAULT读实际Context配置。复制Configuration建立本地化Context；弱缓存仅存Context/资源句柄，locale/source/config/theme/resources/density/fontScale/屏幕指标参与身份，主题复制，不缓存翻译后的文字。无Locale.setDefault、宿主Resources.updateConfiguration或官方Utils/ResourceUtils全局状态修改。
- 按稳定Preference key绑定真实23节点；4个keyless类别新增展示key并persistent=false，既有存储key不变。官方1.45仅设置加载/语言回调/页面创建/子屏点击与实际ListView加载接缝；仅处理本补丁对象，不碰每帧播放器。动态业务状态保留，未变标题/summary不重复notify。Inline编辑器系统动作使用本地化Context读取稳定Android资源ID。
- 两处功能说明14语种更新。中文语言说明为“将所选语言添加到 YouTube 的‘自动翻译’语言列表，可同时选择多种语言。”，空集/保存/取消/重绑不消失；AI说明为“启用后，在 YouTube 的‘自动翻译’列表中选语言，使用已配置的 AI 服务实时翻译字幕。”。每行仅当前UI语言名称与标准勾选框，全部视频状态调用删除，14项可保存；历史状态资源不显示。原Native菜单locale/比较/排序逐字不变，仅新增display-locale名称重载。
- 内建默认编辑器按UI资源显示，Snapshot业务prompt固定N30中文基线，非中文沿原effectivePreference，stored_custom原样。程序化重绑抑制TextWatcher；显式用户paste即便等于日语模板也保存为custom；空白才恢复默认。旧历史中文默认升级规则保留，没有新增“等于任意语言模板即删除”。方案名、地址/模型/Key、selected_codes及有效草稿不因UI刷新写入或覆盖。
- 诊断报告正文与导出技术heading/stage/字段/reason/code为英文；交互壳本地化，source/translation/prompt/profile/provider/raw错误保留。旧中文raw记录不自动变英文、不删除或回写。Morphe确认框显式提供取消按钮，绕过其不可靠系统locale读取，保留主题；后台导出/API结果在主线程按当前UI key+raw参数呈现，不因诊断操作调用供应商。

## 实际验证分母

| 检查 | 结果 |
|---|---|
| Java全量 | 680/680；failure/error/skipped0，原669保留并定点修订UI合同 |
| 原专项 / 中文探针 | 68/68 + 2/2；原main11/K12/生命周期/CAS/barrier继续通过 |
| 并发 | 全量200 + 专项200 = 400轮 |
| 原生Morphe形态Dialog专项 | 9/9；与platform fallback分开；同产品/测试输入SHA |
| UI语言 | 原系统/global、Activity、Application都zh-CN；14覆盖×4尺寸字体=56实际树；缺官方类14完整树单列 |
| 基础资源 | 原232 + 模型空值/地址无效2键 = 234×14，主/降级逐key作者值；不以此替代实际View |
| 实际控件 | 编辑器、真实Canvas绘制/测量、5档标签/选中CD/px说明、透明度、展开诊断、profile/改名/新增/删除/清Key、model popup、多选、Android9复制Dialog/Toast；14快照及zh/ja/en/fr/ar完整控件截图 |
| 同树切换 | zh→ja→en→fr→ar→zh，复用/滚动/重开；默认不保存，自定义中文/相似模板保留 |
| 请求稳定 | 5 UI语言×3目标×2 provenance，实际生产prompt/provider JSON/hash/cache scope稳定 |
| Python / 冻结 | 27/27；4通过/4既有失败/4未验证，三个invisible_ms0；ACCEPTANCE/frozen零diff |
| 七组合 | 7/7，从同一最终MPP自身+官方1.45.0+原版21.16.256序列化后审计 |
| 最终DEX | {s['actual_dex_units']} DEX / {s['dex_classes']:,}类 / {s['dex_methods']:,}方法 / {s['dex_branches']:,}分支；invalid/problem/binding0 |
| APK/MPP检查 | CRC、资源/aapt/minSdk28、公开语言API/新UI接缝、verify_bundle、N8Verify；内嵌MPE=独立MPE；debug/release {json.loads((O/'release-debug-class-equality.json').read_text())['classes']}产品class逐字一致 |
| 历史输入 | {s['history']['unchanged']}份捕获历史文件及{s['history']['external_inputs_unchanged']}外部输入SHA/字节不变 |

完整逐角落清单：[N31-UI-LOCALIZATION-INVENTORY.md](N31-UI-LOCALIZATION-INVENTORY.md)。证据 `.verification/n31/full-final-04`、`special-final`、`custom-host-final`、`delivery-records`；可重跑入口 `tools/n31`。主矩阵装配依据正式Patcher生成XML，仅剪掉无关官方类/资源，未重写本补丁节点；最终XML对子树逐字一致。截图诊断中的“原始证据/自定义/原样错误”是N31RuntimeUiTest第41行刻意注入的source/prompt/provider原始数据，不是遗留UI文字；五语言核查见diagnostic-chinese-audit.json，技术heading英文、原始值原样保留。

负例：N30旧解析器/中文Activity错误取值、真实hint缺重绑、语言行状态泄漏，以及最终MPP缺key/重复资源/错locale值由同一作者值/控件/资源谓词拒绝。locale/key/控件/调用路径见Java XML及mutation-sensitivity。截图使用真实控件；测试编辑器仅scroll归零/software layer辅助离屏栅格，没有静态字符串图代替控件。

## 中文golden与旧测试修订边界

发现N30留存18golden实际运行于英语UI，其中文兼容请求尾部是英文默认模板；N31固定中文业务默认后，旧英语UI产物只在request_json/identity/cache_key/warm_cache_key四字段发生D已授权的漂移修正，其余时间/text/pages/font/token/source字段相等。旧证据保留于N30，不改样本或旧产物来翻绿。

从准确N30产品提交建立独立中文Context回放，以**原golden方法、原样本、原断言**执行；与N31中文配置回放的18组及activate证据逐字段相等。before来源、原英语漂移、after及比较在chinese-baseline-provenance、chinese-golden-equality、historical-english-ui-golden-drift JSON；不把旧英语漂移产物称为中文默认基线，也不假称它零diff。

原测试定点修订：Profile默认display/business分离与同View重绑（profile epoch失效仍assertNotSame）；N28B矩阵保留原源/响应，不清cache使之绿，改为后续仅换UI中文路径也cache hit；诊断正文英文与本地化壳分层；build=n31与实际234键分母。原轨道中心/字号常量/生命周期/失败上限/golden样本未放宽。所有失败和两套前候选包保留；首次普通build未带Android根DEX已列为非交付，正式MPP使用buildAndroid并通过完整校验。

## 独立交付

| 文件 | 字节 | SHA256 |
|---|---:|---|
{rows}

位置：build/local-test与build/n31-composition-final。兼容metadata仍YouTube21.16.256/minSdk28，官方1.45.0；产品1.3.5，旧发布URL/日期不改。实际测试输入SHA `{s['input_sha']}`。

下一次仅保持系统语言，在Morphe切中文→日语→英语，看视频页AI summary、AI子屏类别/默认要求/预览/档名/诊断、多选纯语言名与固定说明（无视频可查）；用户自定义要求应原样。提供截图或诊断即可，无需14语种母语全表。本卡完成即停，不自动发布或启动下一卡。
'''
(R/'docs/N31-LOCAL-TEST-BUILD.md').write_text(report,encoding='utf-8')
state=R/'docs/PROJECT-STATE.md';text=state.read_text(encoding='utf-8');start=text.index('> 最后更新：');end=text.index('\n',start)
text=text[:start]+'> 最后更新：2026-10-03。N31统一运行时UI语言/稳定key重绑/默认展示与业务值分层、两处准确功能说明与多选纯语言名已工程交付。680全量、68专项+2中文探针、234×14及56主/14降级实际树、七组合/全部DEX通过；中文18golden以原N30中文配置逐字段相等，旧英语UI请求漂移证据保留。三件套未签名未安装，本地核心提交与anchor见§4al身份补记；手机短复验仍待用户。N30交付/规划docs保留，不发布、不开下一卡。'+text[end:]
text+='\n\n## 4al. N31 工程闭环交付（2026-10-03）\n\n'+f'实际HEAD fa10360、产品d5ca720，仅docs差异；初始两份状态SHA一致。统一官方MORPHE_LANGUAGE快照，DEFAULT随宿主Context；本补丁配置Context、稳定key、4类别展示标识和设置加载/重绑接缝，仅自有UI。两处summary按定案14语种，多选14纯名称/标准勾选无视频状态；原native locale/sort/clone/去重保持。默认展示随UI，业务中文DEFAULT固定、非中文effective既有、自定义raw；语言刷新无存储/revision/request变化，显式同模板paste保持custom。技术正文英文、壳本地化、旧raw不改，所有源/译文/provider/user证据原样。\n\nJava680/680，原专项68+原中文golden探针2、Morphe形态Dialog9；failure/error/skipped0，main/K12/R1/400轮保持。234×14，中文原Activity/Application/system与14显式覆盖×4尺寸字体共56真实23节点树，另14缺官方类完整fallback；zh/ja/en/fr/ar真实控件截图，所有动态状态/错误/5档/对话/Android9复制有14证据。原英语UIgolden曾把英默认带入中文请求：before不改，D分层后仅请求/hash/cache身份修正；准确N30提交以原方法/样本/断言中文配置回放，18组和activate与N31逐字段相等。\n\n7组合/全部{s["actual_dex_units"]}DEX/{s["dex_classes"]}类/{s["dex_methods"]}方法/{s["dex_branches"]}分支、公开API/新UI接缝/CRC/资源/aapt/min28/verify_bundle/N8Verify通过，MPE内嵌独立等值。Python27，冻结4/4/4与invisible_ms0、ACCEPTANCE/业务源码零diff；{s["history"]["unchanged"]}历史文件+{s["history"]["external_inputs_unchanged"]}外部输入SHA字节不变。正式MPP使用Android根DEX；失败普通build与旧候选保留为provisional。三件套build/local-test与build/n31-composition-final，完整SHA/字节见N31-LOCAL-TEST-BUILD，逐角落清单见N31-UI-LOCALIZATION-INVENTORY。远程API/依赖/下载/签名/安装/卸载/清数据/推送/发布均0。\n\n完成即停；用户保持系统语言只切Morphe中→日→英短查，手机/母语边界未验证；不要求用户全表母语审校。\n'
state.write_text(text,encoding='utf-8');outside=Path(r'C:\Users\14776\Documents\kimi\tasks\2026-09-29\00-35-52-ec1208d7\PROJECT-STATE.md');outside.write_bytes(state.read_bytes());assert outside.read_bytes()==state.read_bytes()
print('N31 report/inventory/state synchronized; identity addendum follows verified core commit')
