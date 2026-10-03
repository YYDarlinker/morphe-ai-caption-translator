# N32 Codex执行卡：原N31基线的设置导航修复与两根重新交付

> **2026-10-03最新补充定案**：已读取当前SDK37手机crash及实际安装APK，确证原N31 initialize/lambda返回hook的寄存器类型错误；当前未选独立简体根仍同样失败。先保证ART安全才有可执行的设置导航链，再调查已撤回方案的错页，最后同一最终包仅AI＋Remember。用户允许手机日志/包读取，但不允许向手机传入、安装、启动测试或改数据。详见N32-DEVICE-CRASH-AND-REQUIREMENTS-REVIEW.md，旧N32规划before已保存。

日期：2026-10-03（Asia/Shanghai）。由新Codex对话完整执行、验证、独立建包、本地提交；完成即停。用户已要求本规划对话先回退，当前产品已经恢复原N31，后续旧二根/设置crash修复不作为活动基线。不交DeepSeek、不恢复N27。

## 1. 施工身份与已知风险

- 仓库仅E:\Projects\morphe-caption-v2；原产品基线dc304cbe3ae995e7e0edad2754b160c959cafce3 / anchor/n31-dc304cb。当前HEAD是新的回退提交或其docs-only后继，**不要把HEAD数值不同当阻断**：除规划docs外当前跟踪源码/测试/metadata/build与dc304cb相等即可开工，记真实HEAD。本卡及恢复状态获准保留随卡提交。
- 实际恢复提交 `282d155fd08e126a36c4eec8b6ef8be85eff1827` / `anchor/n31-restored-282d155`；docs-only身份后继允许。该恢复点除docs外与dc304cb完全相同，不能因恢复commit名称不同再请求确认。
- 指定原包build/local-test/patches-1.3.5-本地测试包-n31.mpp=1,165,680/SHA256 AF084C20C32636EBA051B2891BDAFC5419BD54A98DBB28974CD441BE18AF913C；原包不覆盖、不复用其文件名建新包。原N31三件套是只读对照，不作为待用户安装的修复包。
- 阅读N31-ROLLBACK-AND-NAVIGATION-REVIEW、PROJECT-STATE最新回退章及原N31交付/原N31卡。开工比对两份状态，无差异跳过覆盖；差异读内容处理。
- 旧HEAD150c91f在backup/n31-later-150c91f与.verification/n31-rollback-review快照；66a2584、1bc94ae、相关失败证据和包全保留。不得reset回旧修复、直接cherry-pick整提交以冒充新根因分析。
- 已知原N31返回hook有ART类型问题，恢复只是准确基线，不是声称无风险。该问题、本次点击错页和两根交付在**同卡**闭环，已授权最小设置hook/UI-only修改，不再为已定边界反复询问。
- 官方1.45.0（SHA DBA660DF61D95131A22242CABE9C44B4B04861B7BA6EBEFFE91C4B3647D55B93）、已有原版YouTube21.16.256/minSdk28、Patcher1.14.1/JDK21/SDK保持。所有文件只放本地，**不得adb向用户手机push/安装/启动/清数据/卸载**；也不签名/推送Git/发布。现有本地只读模拟器可用于ART，不使用物理设备；无下载/新依赖/远程翻译API。

### 1.1 当前实机输入与先后顺序（本轮已确定）

只读证据.verification/n32-device-review：手机SDK37/arm64、包app.morphe.android.youtube/21.16.256；当前base.apk199,529,446/SHA256 FB7B28B425A3DCE8884EF9BBFEB6EF8D93FC7B782B9E2E24EDC472D9A9116C38，安装时间2026-10-03 14:58:23。当前实际flags AI=true、Remember=true、Simplified=false。

最新crash 14:58:34.080明确VerifyError：lambda$new$4 [0xC] v2是synthetic lambda而被传Fragment；initialize [0x3E] v2是PreferenceScreen而被传Fragment。三个相关方法的反读与原N31工程APK逐字相同。直接使用这些本机已读取证据作为before，不再要求用户重新崩溃或安装旧包。

**执行顺序固定为：先B1已知类型安全→再A/B2真实设置导航对照→C两根整理→最终全量与同一正式MPP/实际UI/ART交付。** 模块名保留沿革，不能先要求有VerifyError的原N31正常开菜单。当前手机状态无法重现已撤回方案的错页，不能伪称最新crash同时证明了导航根因。

### 1.2 N31对话用户补充要求（原文已核对）

- 第二补丁的14语言功能已有于AI，故删独立Add Simplified public root；最终只AI caption translator＋Remember，所有语言在AI子屏选、AI运行OFF仍保留已选菜单能力。不能把独立强制zh-Hans来源重新塞回以满足旧测试。
- 保持N31说明、14语言有效Morphe locale与动态控件绑定、默认展示/业务偏好分层、多选纯语言名无状态、位置/主题风格。语言切换功能不可因删hook而失效，不改系统语言/官方全局Locale解决。
- 诊断原始数据中中文“原始证据/自定义/provider错误”按原样保留；只有自有技术标题/字段/stage/reason英文，UI交互壳本地化。不能翻译或删除数据去迎合残留扫描。
- 文件本地交付，用户自己传手机/合成/签名安装。本轮允许read logcat/dumpsys/getprop/pm/pull APK，**不允许push任何探针或MPP、app_process测试、安装/卸载/清数据/自动打开YouTube页面**。主动ART/导航测试使用已有本地read-only模拟器/测试宿主；物理手机只读证据已完整，不另操作。
- 缩短周期：只做上述两个bug与两根，不新增性能/语义模型/翻译API实验；必要专项后一次最终全量/交付。加速不能省真实导航测试，也不重复没有变化的历史大包构建。


## 2. A：先证明具体导航原因，不能先猜再改

用户现象：点AI子屏内“自动翻译语言”进入官方“通用”设置。

已经证明：原N31/二根/后续crash修复三APK的settings XML全树相同，语言项仍CaptionLanguagesPreference(key=deepseek_caption_languages, order1)。这几个运行时UI类与资源patch也一样；该类onClick仅showLanguages。因此不能把“删除了第三根”视为跳general的直接原因。

检查和对照顺序：

1. 保留前述三候选只读源/DEX/资源与直接root-removal diff；分清66a2584根数清理和1bc94ae设置hook修改。若原包因ART错误无法加载，先保存其Verifier失败证明，使用最小类型修正的独立对照供导航试验，不能写“原包点击都正常”。
2. 以同官方设置树和真实PreferenceAdapter从**视频页AI入口→AI子屏→点语言项**执行，不能直接调用showLanguages或Preference.onClick当主验证。包含官方Fragment.onPreferenceTreeClick与列表原listener的行为；仅模拟这个类的名字或return值不是实际宿主验证。
3. 记录关键时点：当前Fragment/PreferenceScreen key、Dialog root、ListView adapter类型/header数量、firstVisiblePosition、点击position、adapter实际item的key/类/对象身份、原listener委托对象、语言Preference.onClick调用次数、打开Dialog类型与title、是否创建general-screen。不要记录API key等数据。
4. 记录重绑前后对象/key/class/order/root和item→View配对；覆盖首次打开、从通用页返回再开、滑动复用、快速连续点、语言zh→ja→en变化、有/无视频。检查bind/notify/语言写入/官方排序发生在点击和adapter更新的哪个阶段。
5. 做必要最小对照：N30无binder点击路径、已撤回1bc94ae候选、原N31最小类型安全对照；保持同根数仅换设置hook时序，根数3→2保持同安全hook。可以隔离暂时禁用click中的root rebind以验证假设，记录为实验，最终不能靠禁掉所有本地化或强行导航兜底。确定是标签/adapter对象错配还是原listener/Preference路由被改。N30是语言项原点击方式的功能对照，不将其所有业务回退。
6. 重点排查N31新增onPreferenceTreeClick入口的全root重绑与onSettingsView的布局时View身份：它们可能在native点击/adapter通知中改变标题或旧View关联，产生视觉与对象不对应。分别记录clicked key/class、View文本、delegate绑定的PreferenceScreen、当前Dialog所属root；一般语言Preference不可替换为PreferenceScreen或配置General Intent/fragment。只有证据指向这些路径才定因，不能因为看起来可能就宣称用户导航根因已抓到。
7. **before必须真实出现错误路由或解释确切失败分支**，保存关键身份/堆栈/实际View/源代码位置。若现有产物不能复现且没有用户当前包，报告这一事实和已完成排除项；不得写“已定位General错误并修复”、不得只按假设批量改点击Listener。继续能独立完成的已知类型审计与两根校验，但在真实导航验收缺口存在时不能宣称最终完成。

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

- 不在原生onPreferenceTreeClick或ListView点击分发中重建/替换整棵Preference树或重设listener/adapter，不用标题、语言名或固定行号判断身份。该用户事件以点击对象身份分发；仅语言刷新不得改变其delegate、root、order或路由。可以删除/收紧全局click入口的root rebind，前提是初始化/真实语言写入/本补丁onBind仍保证14语言及Dialog刷新；这属于本卡已授权UI-only修复，不需再开冻结范围询问。
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
- 正式组合序列化后ART验证实际4个settings类；先类加载/方法校验，然后执行设置initialize/语言变更/子屏实际点击链。Class.forName成功只能记“类型验证”，不能当“导航点击通过”。可用已有本地read-only emulator完成，记录SDK/ABI/脚本/包SHA和局限，不冒作本机SDK37手机after；禁止物理手机写入或主动测试。
- 一次性精简debug链必须包含最终build/官方baseline、clicked Preference key/class、View文字、list/root/dialog标识与每次onClick/dialog_open序号；不逐帧dump整树、不记录用户API密钥。原始before与after在同一完整动作序列比较，保证即使未来遇类似错页也能诊断，而非仅增加一条“开窗成功”的helper日志。
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

docs/N32-LOCAL-TEST-BUILD.md须明确原类型bug、导航真实原因与证据、对照变量、真实点击链/adapter/key/class/root、解决方法通用性、2根3组合、UI/ART区别、所有测试/SHA/字节、设备未覆盖边界。 当前手机before使用已读取FB7B28B4…APK和14:58:34日志，不重置/安装以重测；所有N31补充要求在报告中逐项映射到证据。若导航没有复现/当前用户包身份未知，必须如实保留该缺口，不能用合成点击或Class.forName冒充修复签字。

本地一个核心commit/anchor/n32-<真实短哈希>、docs-only身份可补记，两份PROJECT-STATE同步、规划文档保留随卡提交。未签名/未传手机/未安装/未清数据/未推送/未发布，零API/下载/依赖。完成即停，用户自行用最终本地MPP合成安装，再点视频→AI→语言多选、保存/返回、通用页和中日英切换短验，不让用户回装旧坏包。
