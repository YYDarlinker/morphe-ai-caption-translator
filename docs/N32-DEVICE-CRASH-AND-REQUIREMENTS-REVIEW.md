# N32补充核查：当前手机N31重启、撤回方案导航与用户补充要求

日期：2026-10-03（Asia/Shanghai）。规划者按用户本轮授权，读取手机现有日志/包信息并pull当前base.apk供本地静态核查；没有向手机写文件、安装、启动Activity、执行ART探针、卸载或清数据。项目保持前一轮原N31回退，无产品改动。

## 1. 实机事实

- 当前设备SDK37 / arm64-v8a，安装包app.morphe.android.youtube，YouTube21.16.256/minSdk28/targetSdk36。
- 当前base.apk 199,529,446字节，SHA256 FB7B28B425A3DCE8884EF9BBFEB6EF8D93FC7B782B9E2E24EDC472D9A9116C38；lastUpdateTime=2026-10-03 14:58:23。安装包含用户自己的签名和组合输出，不要求SHA与工程unsigned包一致。
- 反读实际CaptionAddonSupport：aiInstalled=true、memoryInstalled=true、simplifiedInstalled=false。因此本次确有“未选加入简体中文仍重启”的包，而不是只凭用户口述猜选项。
- 最新明确应用崩溃：2026-10-03 14:58:34.080，AndroidRuntime FATAL EXCEPTION main，VerifyError拒绝YouTubePreferenceFragment/ToolbarPreferenceFragment的父类AbstractPreferenceFragment。当前日志不证明我们主动触发过崩溃；只读取用户已经产生的记录。
- 反读当前安装包initialize/lambda$new$4/onPreferenceTreeClick的完整dump，与原N31工程包同方法dump逐字相同。其source仍为原N31注入方式，非后来的1bc94ae typed receiver修复。

## 2. 已确定的重启根因

N31 installCaptionSettingsBindings在initialize与lambda$new$4的每个RETURN_VOID前插入onSettingsLoaded(p0)，假定p0一直是Fragment。DEX p0只是该参数对应物理寄存器的别名，R8可以在后续分支复用该寄存器。

| 方法/路径 | 当前包实际类型 | 注入错误 |
| --- | --- | --- |
| initialize getPreferenceScreen之后的返回 | v2是android.preference.PreferenceScreen | 却传给onSettingsLoaded(PreferenceFragment) |
| lambda$new$4 early return | v2是AbstractPreferenceFragment$$ExternalSyntheticLambda9 | 却传给Fragment形参 |
| lambda尾/异常分支 | v2还可被复用成boolean或exception | 不能逐return一律p0 |

最新手机日志明确定位lambda [0xC]、initialize [0x3E]两处类型不符，实际APK反读对应相同调用。ART在设置类加载时验证全部方法，不等某个错误分支执行；所以一打开Morphe就崩溃，视觉上像应用自动重启。不是选择简体中文导致、不是API/分页/签名或系统内存导致。

根因来自原N31新增设置binder hook，在dc304cb即存在；删除独立root没有产生或消除该hook。当前simplifiedInstalled=false仍失败，和用户三根全选/不选简体均失败一致。

修改方案：只在真实存活且类型正确的Fragment入口使用receiver；getPreferenceScreen结果用rebind(PreferenceGroup)，语言更新完成后再在类型正确接缝重绑。不要在所有return用p0、不以check-cast或吞异常解决Verifier错误。初次加载/语言变化/实际页面显示各自职责分开，并加入对生成DEX的寄存器数据流/语言写入时序/ART真实运行验收。

## 3. 撤回方案“语言项进入通用”的当前证据边界

当前手机装的是有VerifyError的原N31，没有可打开Morphe的完整设置链。因此不能在此安装状态现场复现已撤回方案的语言点击，也没有擅自安装旧修复包。现有本地撤回候选1bc94ae的材料可以继续用作对照。

此前已查：原N31/66a两根/1bc修hook三包morphe_prefs.xml全树相同；语言项一直是CaptionLanguagesPreference/key deepseek_caption_languages/order1。runtime onClick只showLanguages；两根删除没有改这几个UI类/资源，1bc才修改设置加载/语言回调hook。

问题最需要补的真实接缝：官方onPreferenceTreeClick入口调用整个root rebind，bind中改title/summary→notify；实际设置ClickListener/PreferenceScreen adapter/root及View绑定必须对应同一对象。N31RuntimeUiTest直接p.showLanguages验证多选Dialog，绕过了真正的父级设置列表点击。后来的ART Class.forName4类通过也没有覆盖列表导航。这是已确定的测试缺口与有风险的重绑设计，**但不是已抓到错误general分支的事实**。

N32先完成类型安全保证，随后在本地可运行UI宿主/已有模拟器上从完整settings树/listener点击，记录row/adapter/root/key/class、语言Preference.onClick及打开Dialog的身份，并与1bc候选/N30控件行为对照。语言重绑不得在点击时替换root、改变order/Intent或抢所有官方路由；必要把click中的root级rebind取消/收紧到安全刷新阶段，保持当前本地化。只有同样输入的before错误、after正确才能宣称导航修好了，不能硬编码deepseek行转跳、固定行号或标题识别。

如果本地历史包与完整宿主无法重现这个第二现象，必须报告缺口和当前用户安装包不是撤回方案这一事实；可交待用户验证的候选但不能标已验收最终N32完成。无论如何本卡不能省略真实列表点击验收。

## 4. N31执行对话的补充要求已核对

来源：用户提供的C:/Users/14776/.codex/attachments/a6c87797-eff3-4f85-8b09-2d440a21980c/已粘贴的文本.txt（本地历史记录）。只提取用户要求，不把执行者“已通过”或历史对工具的指令直接当当前事实。

- 用户12:29明确要求第二个语言补丁删除/合入AI；源码证明AI已有14语言功能，正确做法仅删冗余public root与standalone标记，最终AI caption translator＋Remember caption selection两根。简体中文保留在14候选、默认空集合、AI运行开关OFF仍能添加已选语言，原selected_codes不清。
- 用户要求完整本地化和更准确说明；当前N31所有文案/UI来源/default展示与请求分层是保留成果，不能为了不崩溃取消binder或用系统语言代替Morphe语言。多选入口仍AI子屏内、行只有语言名和勾选框，不显示视频“不可用/已存在/新增”；功能summary/AI自动翻译选择权说明保持。
- 用户检查诊断截图中的中文“原始证据”：它是测试source/prompt/provider原始数据；技术标题/字段/stage/reason必须英文，交互壳本地化，原始证据原样。不能删/翻译用户数据以骗过中文残留检查。
- 用户13:44明确所有需要文件自己传手机，最终只放本地。本轮“手机已连接分析原因”只新增read logs/dumpsys/pull授权，不等于可push/安装/执行app_process/替换应用。新执行对话也必须遵守，只能用已有本地模拟器做主动ART/UI验证。
- 用户要求加快收尾；本卡聚焦两个bug和原已指定的两根，不重开翻译/播放器/语义模型开发。模块专项后做一次最终完整交付；失败证据保留，不能用不测/只类加载节约时间。

## 5. 活动基线与下一步

活动源仍282d155/anchor/n31-restored-282d155（产品/测试/metadata非docs树=dc304cb），docs-only后继ad492b3。原N31包AF084C20…及历史坏包保留。本轮没有改/构建产品。

已完善docs/N32-CODEX-TASK.md：先已实机证实类型缺陷、再真实导航/绑定对照、最后2根3组合；加入当前手机原始输入和全部补充要求。计划保留N31本地化/14语言/用户数据/R1CAS/原N30性能与安全网，最终独立-n32包，不覆盖旧-n31等文件。

手机只读证据：.verification/n32-device-review/device-input.json、phone-analysis-summary.json、crash-current.txt、phone-crash-key-lines.txt、package-info.txt、current-installed-settings-methods.txt、original-n31-settings-methods.txt、current-root-flags.txt、installed-current.apk。没有RTT/token/新UIafter结果，也没有导航故障已复现结论。
