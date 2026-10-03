# N32 Codex执行卡：原N31基线的设置导航修复与两根重新交付

日期：2026-10-03（Asia/Shanghai）。由新Codex对话完整执行、验证、独立建包、本地提交；完成即停。用户已要求本规划对话先回退，当前产品已经恢复原N31，后续旧二根/设置crash修复不作为活动基线。不交DeepSeek、不恢复N27。

## 1. 施工身份与已知风险

- 仓库仅E:\Projects\morphe-caption-v2；原产品基线dc304cbe3ae995e7e0edad2754b160c959cafce3 / anchor/n31-dc304cb。当前HEAD是新的回退提交或其docs-only后继，**不要把HEAD数值不同当阻断**：除规划docs外当前跟踪源码/测试/metadata/build与dc304cb相等即可开工，记真实HEAD。本卡及恢复状态获准保留随卡提交。
- 指定原包build/local-test/patches-1.3.5-本地测试包-n31.mpp=1,165,680/SHA256 AF084C20C32636EBA051B2891BDAFC5419BD54A98DBB28974CD441BE18AF913C；原包不覆盖、不复用其文件名建新包。原N31三件套是只读对照，不作为待用户安装的修复包。
- 阅读N31-ROLLBACK-AND-NAVIGATION-REVIEW、PROJECT-STATE最新回退章及原N31交付/原N31卡。开工比对两份状态，无差异跳过覆盖；差异读内容处理。
- 旧HEAD150c91f在backup/n31-later-150c91f与.verification/n31-rollback-review快照；66a2584、1bc94ae、相关失败证据和包全保留。不得reset回旧修复、直接cherry-pick整提交以冒充新根因分析。
- 已知原N31返回hook有ART类型问题，恢复只是准确基线，不是声称无风险。该问题、本次点击错页和两根交付在**同卡**闭环，已授权最小设置hook/UI-only修改，不再为已定边界反复询问。
- 官方1.45.0（SHA DBA660DF61D95131A22242CABE9C44B4B04861B7BA6EBEFFE91C4B3647D55B93）、已有原版YouTube21.16.256/minSdk28、Patcher1.14.1/JDK21/SDK保持。所有文件只放本地，**不得adb向用户手机push/安装/启动/清数据/卸载**；也不签名/推送Git/发布。现有本地只读模拟器可用于ART，不使用物理设备；无下载/新依赖/远程翻译API。

## 2. A：先证明具体导航原因，不能先猜再改

用户现象：点AI子屏内“自动翻译语言”进入官方“通用”设置。

已经证明：原N31/二根/后续crash修复三APK的settings XML全树相同，语言项仍CaptionLanguagesPreference(key=deepseek_caption_languages, order1)。这几个运行时UI类与资源patch也一样；该类onClick仅showLanguages。因此不能把“删除了第三根”视为跳general的直接原因。

检查和对照顺序：

1. 保留前述三候选只读源/DEX/资源与直接root-removal diff；分清66a2584根数清理和1bc94ae设置hook修改。若原包因ART错误无法加载，先保存其Verifier失败证明，使用最小类型修正的独立对照供导航试验，不能写“原包点击都正常”。
2. 以同官方设置树和真实PreferenceAdapter从**视频页AI入口→AI子屏→点语言项**执行，不能直接调用showLanguages或Preference.onClick当主验证。包含官方Fragment.onPreferenceTreeClick与列表原listener的行为；仅模拟这个类的名字或return值不是实际宿主验证。
3. 记录关键时点：当前Fragment/PreferenceScreen key、Dialog root、ListView adapter类型/header数量、firstVisiblePosition、点击position、adapter实际item的key/类/对象身份、原listener委托对象、语言Preference.onClick调用次数、打开Dialog类型与title、是否创建general-screen。不要记录API key等数据。
4. 记录重绑前后对象/key/class/order/root和item→View配对；覆盖首次打开、从通用页返回再开、滑动复用、快速连续点、语言zh→ja→en变化、有/无视频。检查bind/notify/语言写入/官方排序发生在点击和adapter更新的哪个阶段。
5. 做必要最小对照：保留根数不变仅换设置hook时序，根数3→2保持同安全hook。可以隔离暂时禁用click中的root rebind以验证假设，记录为实验，最终不能靠禁掉所有本地化或强行导航兜底。确定是标签/adapter对象错配还是原listener/Preference路由被改。
6. **before必须真实出现错误路由或解释确切失败分支**，保存关键身份/堆栈/实际View/源代码位置。若现有产物不能复现且没有用户当前包，报告这一事实和已完成排除项；不得写“已定位General错误并修复”、不得只按假设批量改点击Listener。继续能独立完成的已知类型审计与两根校验，但在真实导航验收缺口存在时不能宣称最终完成。

该门槛不要求用户先安装任何旧包，也不允许用一个人为错误listener的负例冒充用户原问题复现。

## 3. B：设置接缝最小修复，不改原生导航

### B1 已知ART错误必须闭合

原initialize/lambda$new$4返回前直接用p0当Fragment不安全：R8复用后其物理寄存器可能是PreferenceScreen、synthetic lambda、boolean或exception。按真实类型数据流选择：

- 入口p0只在仍为实例receiver时可用；已执行typed getPreferenceScreen的move-result可调用rebind(PreferenceGroup)，不能传给Fragment形参。
- 语言重绑发生在MORPHE_LANGUAGE实际写入之后，使用该阶段真实存活且类型正确的Fragment receiver；不是在旧值阶段强刷，不遍历所有return盲用p0。
- 匹配方法名+完整descriptor/访问位/唯一位置，分支处理覆盖；注入分支用ExternalLabel绑定真实BuilderInstruction，不猜PC、不过度增加寄存器或盲cast以隐藏错误。
- 把必要寄存器/时序审计从旧修复提取并审阅后接回最终组合审计，旧包及错误receiver/旧语言阶段负例须非0拒绝。不是复制旧“ART pass”日志算本轮通过。

### B2 点击与本地化分离

以A的实际根因为准最小修改。默认原则：语言Preference点击只开14项多选Dialog，不导航其他屏；官方general/其他行保持自身导航。

- 不在原生onPreferenceTreeClick或ListView点击分发中重建/替换整棵Preference树或重设listener/adapter，不用标题、语言名或固定行号判断身份。
- 普通点击通常不需要root级重新绑定；初始化/有效语言变更/子屏View创建是更适合的绑定阶段。若需要修改绑定时序，合并且去重为现有UI队列上的状态刷新，读取最新locale、只改本补丁对象的文字；不在list布局/回收时触发递归notify或改变item对应关系。
- 单个View回收时按当前adapter实际Preference身份刷新，不让旧row绑定覆盖新的官方general行。支持header/cached/stale View，旧binder/listener任务关闭Dialog/换root时失效；改title不改变key/order/Intent/fragment/listener。
- 不新加“如果语言项就直接showLanguages”全局拦截来掩盖错误root/adapter，不能禁止触发general页/重新指定Activity导航。真正的Preference对象须仍接收到唯一一次点击。
- 保持N31 UI语言来源、default展示/业务分层、自定义prompt/API/profile/selected_codes原值，模型/诊断Dialog行为原样；无视频/AI关闭也能编辑选择。

允许CaptionSettingsBindingPatch、必要CaptionPreferenceBindings/CaptionUiPreference/CaptionUiViewBindings/CaptionLanguagesPreference和官方settings UI-only接缝最小修改；禁止翻译/网络/缓存/分页/播放器/全局语言新架构。

## 4. C：再删除冗余独立根，两根交付

基线N31的AI已含通用14语言metadata/clone与selected_codes；这一功能不需要再合并一份代码/把语言设置改成PreferenceScreen。

- 从public补丁定义删除Add Simplified Chinese to auto-translate；MPP公开集合精确为AI caption translator、Remember caption selection。
- 删除CaptionFeatures.simplified、standalone强制zh-Hans来源与simplifiedInstalled标记/其控制分支；简体中文和另外13语种只按AI设置保存集合提供。默认空集合，不迁移/清空selected_codes，AI运行开关关闭不抹掉菜单能力。
- Remember独立：未安装AI时原生记忆仍正常、API0，语言注入不意外启用；安装AI但关AI开关时已选语言仍加入原生自动翻译菜单、翻译API0。两种“installed”与“runtime enabled”不能混淆。
- 沿原SharedPreferences/Keystore/目标source权限/native draw及初始化唯一性，不能删除共享能力。每次真实patcher新session重置features；同一进程多组合不泄漏前次选项。
- 同步README、生成patches-list、本地metadata/发行校验从3根到2根；用正式MPP加载并generatePatchesList，不能手改JSON伪装。旧published URL/日期/assets不改，不发布。
- 对本模块允许旧测试“Standalone Simplified一定存在”的断言定点更新且保留before；新增错误第三根/漏简中/重复zh-Hans、错误集合迁移负例。不要继续用7组合等旧分母声称二根验收，应3/3非空组合。

## 5. 真正的导航/ART验收

- 首次打开视频设置、AI子屏、列表点击语言项出现一个14项multi-choice Dialog（纯语言名/勾选框/正确UI title和固定功能说明）；选/取消/保存/重新打开集合正确、返回仍AI子屏；不曾启动general屏，不留下第二个settings Fragment/root。
- 真实ListView.performItemClick走实际adapter→原listener→Preference，而不是p.showLanguages。再点击general行确进general，不把官方行全部拦截。zh/ja/en/fr/ar、滚动后复用、rapid tap、rebind中点击、有/无视频、AI关/开覆盖；证明onClick恰1次、原listener与key/class/root对应。
- 14本地化/320与420dp/1与1.3/RTL样本保持，真实UI更新不改请求hash/cache identity或用户custom；程序性诊断英文、源文/provider原样。
- 正式组合序列化后ART验证实际4个settings类，且执行设置initialize/语言变更/子屏点击链；Class.forName成功只能记“类型验证”，不能当“导航点击通过”。可用已有本地read-only emulator完成，记录设备SDK/ABI和复现范围，禁止触碰用户手机。
- 错误receiver/错误language时序、故意错误root/adapter导航、重复Dialog事件由同一审计/实际点击验收拒绝；负例数据不混入production。
- 所有实现通用，禁止视频ID、具体时间/token/用户句子、中文标题匹配和固定列表position特判。

## 6. 冻结与验证规模

原N31请求/默认与custom分层/菜单目标权限/650ms断点/cache-only启动/连接attempt/转场render任务合并失效/draw/R1锁-CAS-Publication同key/中文18golden/非中文n29-v3/字号颜色位置不变；N27不恢复，VISIONOS不处理。N31原3根回退只是基线，不把后续2根事实继续声明为当前交付。

- 原Java680加新导航/根归属/寄存器回归，实际failure/error/skipped0，原68专项/main11/K12/中文18golden保持；不强迫复制旧682数字。只局部UI/删除标志时不为凑轮数再无必要重复压力；沿现有规定所需400轮一并报告，若出现N31业务层修改须重新定界。
- Python27、metadata实际测试数、234×14、实际14UI树/默认与用户数据对照；冻结4通过/4既有失败/4未验证，3 invisible_ms0、ACCEPTANCE/frozen零diff。
- 原版21.16.256+官方1.45+**正式MPP自身**三组合：AI-only、Remember-only、AI+Remember。都序列化重读，type/branches/API access/hook/UI导航接缝审计、ART、CRC/resources/aapt/min28/verify_bundle/N8Verify通过。实际DEX/类数/patch数量如实报告，不硬套旧11/680/93。
- 一次最终全量、正式交付；失败原日志与每个实验包保留，不能删失败/放宽时限/改输入翻绿。捕获的历史MPP/MPE/APK和失败证据前后hash不变。

## 7. 独立交付与停止

最终后缀统一-n32：build/local-test/patches-1.3.5-本地测试包-n32.mpp、extension-1.3.5-本地测试包-n32.mpe；build/n32-composition-final/YouTube-21.16.256-本地测试包-n32-unsigned.apk。原-n31、-two-patches、-settings-crash-fixed均留历史，不覆盖。

docs/N32-LOCAL-TEST-BUILD.md须明确原类型bug、导航真实原因与证据、对照变量、真实点击链/adapter/key/class/root、解决方法通用性、2根3组合、UI/ART区别、所有测试/SHA/字节、设备未覆盖边界。若导航没有复现/当前用户包身份未知，必须如实保留该缺口，不能用合成点击或Class.forName冒充修复签字。

本地一个核心commit/anchor/n32-<真实短哈希>、docs-only身份可补记，两份PROJECT-STATE同步、规划文档保留随卡提交。未签名/未传手机/未安装/未清数据/未推送/未发布，零API/下载/依赖。完成即停，用户自行用最终本地MPP合成安装，再点视频→AI→语言多选、保存/返回、通用页和中日英切换短验，不让用户回装旧坏包。
