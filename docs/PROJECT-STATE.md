# PROJECT-STATE — Morphe AI Caption Translator 质量迭代项目状态档案

> 最后更新：2026-10-01（N27r源码见§4o；当前E盘HEAD与锚点以`git log`核对）。用户装机后点图标立即闪退，规划者读取手机日志及最终APK确认非法DEX跳转：bfec构造器PC0x10的if-eqz跳到0x0d（官方invoke内部），ART抛VerifyError。**N27r已执行完毕**：生产注入改为`ExternalLabel`绑定真实`return-void`，`signed_offset`由−3变+11、target 0x1b；新增仓库内最终DEX分支审计并接入既有组合入口，旧N27坏包被直接拒绝。N27仍为「已交付、真机启动失败、未验收」，**启动修复待用户装机复验**。语言阶段暂不推进。用户取消换对话，本聊天继续研究/规划/审阅，DeepSeek只执行；压缩后恢复以本档案最新节及`docs/N27R-LOCAL-TEST-BUILD.md`为准。三项延期语言/文案问题、九项已定选择、中文字幕基线与冻结事实保持。
> **用户签字（2026-09-30，N20 显示策略）**：① 等待期（译文未就绪：启动、暂停后、拖动进度条后）字幕区显示 **“翻译中…”**；② 译文最终失败或被安全网拦截时字幕区**完全空白**；③ 不再向屏幕输出 `[原文 / Original]` 与技术文案，原因一律只进诊断；④ 授权按此修订 ACCEPTANCE.md 的 A01 与 A13 判据（其余判据与冻结证据不得改动）；⑤ 字号档位可视化：滑轨上加 **5 个刻度点**、轨道下方一排**档名（超小/小/标准/大/超大）**并与刻度对齐，当前档高亮；**档名行不标注 px 数值**；拖动吸附与松手保存不变。
> **字号设计核验（2026-09-30，审阅者用 PIL 直接量 66.jpg / 67.jpg 原图）**：B站横屏全屏单字墨迹高 median **57px**（30 字样本，直方图峰值 58px，阈值 190；档案早前另一阈值测得“经”55/“频”56），B站竖屏详情页 median **45px**（20 字样本，峰值 46px）；与设定值 55.5 / 44.5 相差 ≤1.5px（全屏 2.7%、详情页 1.1%），属单字取样与阈值差异。五档常量、默认档、全屏 ×1.247、预览比例、旧值迁移、诊断字段均已逐项核对，**未发现谬误**。三处需知细节（均为既有设计，非缺陷）：排版排不下时字号下限压到**超小档**（34/42.4px）；评论区收窄 >20% 时字高随视频矩形同步缩小（N19 未改）；旧 r 值迁移以 1264px 为参考屏宽换算，仅影响升级瞬间一次。各档设备值（1264×2736 屏）：详情页 34 / 39 / 44.5 / 50 / 56px，全屏 42.4 / 48.6 / 55.5 / 62.4 / 69.8px。
> **用户最终决策（N19，覆盖 N17c 的单一 r 方案）**：
> 1. 默认大小按 B站实测：竖屏详情页 glyph **44.5px**（屏宽 1264 的 **3.52%**）、横屏全屏 **55.5px**（屏宽 2736 的 **2.03%**）；B站缩放比 = **55.5/44.5 ≈ 1.247**，详情页→全屏一律按此比例缩放，禁止再按屏宽等比。
> 2. 滑块保留现有交互（实时联动、松手保存），但只能吸附五个档位：**超小 34 / 小 39 / 标准 44.5（默认）/ 大 50 / 超大 56**（详情页 glyph px，1264 屏宽基准）；全屏值 = 详情页值 × 1.247。Shorts 同详情页。
> 3. 设置界面显示的字号数值一律为详情页大小；每档说明文案精简为只含关键信息（如“标准：详情页 44.5px · 全屏 55.5px”），常识性解释删除。
> 4. 设置预览（N17d 单幅 16:9 横屏）必须按“全屏 glyph px ÷ 2736 × 预览图实际宽度”渲染字幕，与真机全屏效果一一对应；切档即时生效。
> 5. 底层实现继续用运行时 Paint/FontMetrics 实测反解 textSize，禁止假设 density/fontScale 系数；跨设备用 r 换算保持可移植。计算采用精确实测比 55.5/44.5，1.247 为近似值。
> 6. 零 API 调用、零新增依赖；14 语种 XML、catalog、source-keys、Java fallback 同步更新；不动冻结计分板与任何验收判据；旧的无极 r 设置值需有迁移逻辑（旧值映射到最近档）。
> **N19 审阅结论（2026-09-30，审阅者复核）**：实现与上述六条决策一致（`CaptionFontSize` 五档常量、默认档 2=44.5、全屏 ×55.5/44.5、预览按 `fullScreenRatio × 预览宽度`、旧 `caption_glyph_height_ratio_bps` 单向迁移后写 `caption_size_tier`、滑块 0–4 五档并显示“档名 + 详情页px + 全屏px”）。提交 `10e9c85` 单卡单提交、工作区干净；Java 394/394、Python 27/27；冻结计分板复跑维持 4通过/4失败/4未验证、三类不可见时长全 0；冻结结果与 ACCEPTANCE.md 无改动；亮/暗×标准/超大四张 fixture 已目检，预览随档位变化正确。
> **像素实测来源（2736×1264 原图单字窗口测量）**：B站横屏全屏 glyph 55.5px（66.jpg“经”55/“频”56）、竖屏详情页 glyph 44.5px（81.jpg）；插件旧竖屏详情页 glyph 68.5px（85.jpg）。N17c 的详情页 25.6px、全形态单一 r=2.03%、连续范围 1.5%–3.0% 已被 N19 覆盖。
> 用途：任何 AI 会话（Kimi 或 Codex）接续本项目时，先读本文件，无需翻阅长对话历史。
> 本文件是唯一的“记忆”，对话记录不是。仓库 `docs/PROJECT-STATE.md` 为权威副本，每卡开工时同步。

## 0. 上下文压缩后恢复须知（2026-10-01，覆盖历史角色与卡序）

1. **先读本实时状态档案**：优先读本节、§4i、§4k、最新§4n及 `docs/N27-CRASH-REVIEW.md`、`docs/N27R-CRASH-REPAIR-TASK.md`；其后按需读历史/研究。用户已取消换对话，旧交接包是历史快照，不代表实时状态、不作为行动入口。上下文压缩后按最新文件恢复职责与在途状态，其他阶段的“待执行/下一步”不自动恢复执行。
2. **角色边界**：接任本聊天的 AI 负责官方资料研究、根因判断、方案定案、详细任务卡、只读审阅及管理档案维护；**DeepSeek Harness 只负责执行已定任务卡**（源码、测试、构建、提交、交付）；用户负责真机反馈与最终验收。规划者不修改产品代码、不跑产品测试/构建、不自行签名/推送/发布；可读仓库/证据及维护规划文档。旧文中“由 Codex 执行”不再是当前分工。
3. **审阅**：先在 E 盘执行仓库只读核对 HEAD、锚点、工作区及 diff，再审阅执行报告和真实证据。计分板由执行者按卡运行，规划者核对报告与产物，不把执行者结果说成自己复跑。不足以判定的证据如实写未验证，不改变冻结事实和 ACCEPTANCE 判据。
4. **当前阶段**：N27已交付（源码62c4916，HEAD28229e0），用户真机启动失败，尚未验收；手机日志+最终APK已定位到空值保护分支非法目标。N27r修复卡已由规划者准备，本轮尚未执行修复。优先修启动和补真实最终DEX审计，再由用户装机复验；不重复派发N27、不先进入语言功能。三项语言/文案问题仍开放并留最后统一修。
5. **执行顺序**：N27r启动修复及N27避让真机复验 → 语言档案/计数/方向/源目标路由及接入 → 原生自动翻译菜单十四语种多选与简中补丁合并 → 必须执行的最终全面本地化/功能说明/诊断英文审计修复。旧L1调查、N25后开feature/lang-menu的安排由此覆盖。
6. **已定选择**：九项选择见§4i，不重新询问。最新位置决定固定为“旁白翻译 → AI字幕翻译”，覆盖旧2A自然排序建议；其余项目相对顺序不动。中文现有体验保留；新语种读速软目标、两行与几何硬约束；语言菜单默认空集合且AI关闭仍保留。
7. **单执行者与同步**：任何时刻不让两个执行者同时改同一仓库；管理更新不清除、不擅自提交产品变更。仓库与外部档案需保持一致（§2.8），出现差异先读并核对时间/内容，不按路径盲目覆盖。交接包快照仅供恢复阅读，不自动覆盖更新后的权威文件。
8. **路径**：实际执行仓库 `E:\Projects\morphe-caption-v2`；本聊天C盘工作树 `C:\Users\14776\.codex\worktrees\d73e\morphe-caption-v2` 是旧N24状态，不用于当前施工或判断最新源码。JDK/SDK等环境见§1。真机发现记录为观察，不擅自加入或改写验收判据。

## 1. 项目与路径

- 产品：Morphe 字幕补丁（.mpp，v1.3.5 基线约 1.1MB），YouTube 自动翻译 → 用户自配 OpenAI 兼容 API → 播放器内字幕。目标 YouTube 21.07.247（N18r 起组合构建实际使用 21.16.256 + 官方 1.44.0），最低 SDK 28。
- 工作仓库：`E:\Projects\morphe-caption-v2`（从 GitHub main=v1.3.5 全新克隆）。
- 只读档案：`E:\Projects\morphe-ai-caption-translator-next`（旧研究区，39 提交/10 分支；ADR-006、P4d 失败报告在内；**禁止延续其任务序列，仅点名时查阅**）。
- 材料（均在仓库根目录）：`caption-diagnostics-1.3.5-20260927-084217.txt`（旧真机）、`caption-diagnostics-1.3.5-20260929-155802.txt`（0929 诊断）、英文源 SRT、`字幕参考.zip`；真机诊断输出目录 `D:\HONOR Share\Honor Share\`。
- API 环境变量（本机已配置，开发期可自由调用，成本可忽略）：`MORPHE_P4_API_KEY / MORPHE_P4_BASE_URL / MORPHE_P4_MODEL`（当前 qwen3.8-flash）。
- 测试环境：Gradle 用 **Zulu JDK 21**，路径注意嵌套：`D:\Program\zulu21.46.19-ca-jdk21.0.9-win_x64\zulu21.46.19-ca-jdk21.0.9-win_x64`（C 盘只有 JDK 25，与 Robolectric/ASM 不兼容，会环境性失败）；另需 `ANDROID_HOME=C:\Users\14776\AppData\Local\Android\Sdk`。计分板 `powershell -File scoreboard/run.ps1`（Python 走本机回退路径，WindowsApps 占位 python 不可用）。

## 2. 治理规则（不可违反）

本节施工限制约束执行者；规划者按用户授权进行资料研究、方案定案与管理文档维护，角色边界以§0为准。历史“新增案例”建议须先经用户授权，不自行修改ACCEPTANCE。

1. **任务卡制**：每张卡一个封闭任务，新会话执行，做完即停；汇报 ≤半页（改动文件、分数、建议不执行）。
2. **停止线**：新增依赖 / 任何下载（除卡内允许的 API）/ 改动超范围 / 单项超半天 / 想重设计架构 → 停下问用户。
3. **验收权在用户**：ACCEPTANCE.md 的判据只有用户能改；AI 提议修订须标“待用户确认”。自然度类最终由用户目检签字。
4. **禁止事项**：打包/运行时下载模型；新增运行时依赖；自我派生下一阶段；写研究文档/ADR；hard-coded 词表特判。
5. **计分板纪律**：冻结层（旧真机会话）保持历史原样；live 层单独存档带时间戳，永不混入冻结基线；token 必记账。
6. **提交纪律**：每卡结束提交、工作区干净；失败实验保留文档、回滚代码。
7. **真机规则**：真机发现新问题不现场改，记录时间位置+现象，回流为 ACCEPTANCE.md 新案例。
8. **状态档案同步**：每卡开工先比对仓库 `docs/PROJECT-STATE.md` 与外部 `C:\Users\14776\Documents\kimi\tasks\2026-09-29\00-35-52-ec1208d7\PROJECT-STATE.md`；无差异则跳过，有差异先读内容与最新反馈再同步，不按路径盲目覆盖。规划者管理更新须保留并按卡提交；审阅结束同步两处。交接包快照不自动覆盖实时文件。

## 3. 审阅与规划规程（当前角色修订）

1. 在实际E盘仓库分别执行只读Git状态、日志、锚点和差异检查，核对提交边界；保留正在执行的改动与管理档案改动。
2. 审阅执行者按卡产出的计分板/测试/组合/DEX及哈希证据；冻结须维持4通过/4既有失败/4未验证、三类不可见时长全0。未亲自运行的结果明确为执行者报告。
3. 必要时只读源码、diff、fixture与真实诊断；区分离线证据、实际运行证据和用户真机签字；不足以判定的项目不能写“完成”。
4. 报告本卡结论、整体进度与开放问题，将新反馈/决策同步两份档案。用户已经定案的选择不反复询问。
5. 研究与具体设计由规划者完成，DeepSeek只执行细化卡。禁止为翻绿放宽安全网、偷改冻结事实/验收判据或恢复被回退的样例特判。

## 4. 案例状态（A01–A17）

- 冻结计分板（旧会话）：通过 4 / 失败 4 / 未验证 4；三类不可见时长全 0。
- **A01 启动**：✅ 机制在（等待期有可读内容、译文同窗替换）。**判据已修订（用户 2026-09-30 签字，随 N20 落地）**：等待期不再显示源语言原文，改为显示“翻译中…”，译文仍只在同一归属窗内替换。
- **A02/A03/A05**：✅ 自然度用户已签（N7 live 新译；生效 prompt SHA `d841cf10…`）。
- **A04/A08**：✅ 结构 + N9 修复误杀（复数 `t-62s` 锚点）；真机复验待第三轮补测。
- **A06/A10**：🟡 N15r 新布局镜像 A06=3页/A10=4页；真机排版待第三轮补测。
- **A07**：✅（N2 解放 paragraph 误拦）。
- **A09**：📌 已知遗留（电报式源文，两轮引导无效，已止损回滚）。
- **A11/A12**：✅ 生成层（N6 本地显示合并）。真机待验。
- **A13**：✅ 机制（失败兜底：归属时间窗内的可读内容）。**判据已修订（用户 2026-09-30 签字，随 N20 落地）**：失败或被拦截时字幕区留空，不再显示 `[原文 / Original]`；原因只记录于诊断（`REBUILD_FALLBACK_BEGIN` 的 reason 等字段不变）。
- **A14**：📌 已签·已知遗留（双模型不可修复 + 复发变体绕过字面检测；检测到时原文兜底，漏检时接受偶发错译上屏）。
- **A15**：📌 已签（b109 块原文兜底为正确安全网行为；待补看 845s 后区段）。
- **A16**：📌 已签·已知遗留（比较极性反转，接受偶发上屏，依赖真机目检）。
- **A17**：📌 已签（登记为 A14 证据升级，不另立判据）。
- **孤字页**：N17a P1 已修（每页 ≥8 汉字格硬门槛）；第三轮补测复查。

## 4b. 第二轮真机结果（诊断 0929-230749，观看 631s，34/34 API 成功，80,429 tok）

**健康项**：启动源就绪 591ms；形态切换缩放正常；播放器过渡守卫工作；prompt SHA 无漂移；缓存 0 命中属预期（首轮冷缓存）。字号当时 23.18sp。
**问题（已回流立案）**：A16 比较极性反转（84.110–99.736s，ASR 把 than 误作 then）；A14 复发主语错接换壳（384.6–391.7s，措辞漂移绕过检测）；A17 孤字页×2（84.1s/125.6s）；`lines` 字段口径混乱（N17a 已修）。

## 4c. 第三轮真机首轮结果（诊断 `caption-diagnostics-1.3.5-20260930-140013.txt`，观看 233s，17/17 API 成功，37,663 tok，9,709 tok/观看分钟）

**包**：N18r 产物（YouTube 21.16.256 + 官方 1.44.0 + Patcher 1.14.1，84/84 PASS），**不含 N19 字号改动**。
**健康项**：prompt SHA `d841cf10…` 无漂移；字号标定机制工作正常（r=203bps 时详情页 25.66px/全屏 55.54px；r=300bps 时 37.92px/82.08px，两形态均命中目标，`glyph_height_px` 实测值与目标一致）；启动源就绪 786ms；形态切换与视频矩形识别正常。
**用户当场决策（已落地/待落地）**：
- 字号两难（详情页偏小、全屏偏大）→ 用户拍板 B站基准五档制 → **N19 已实现并审阅通过**。
- 暂停/拖动时反复出现“字幕过长，原文暂不可用”→ 用户要求字幕只显示译文、等待与报错只进诊断，并选择“等待期极简提示”方案 → **待落卡 N20**。
**问题（真机规则：只记录，回流立案）**：
- **待确认项（未真正执行）·缓存复看命中**：`Request-block disk cache: lookups 12 · hit blocks 0 · missed units 13`。该轮在 seek 回 0（232761→0）后即结束，未形成“同包复看同一段”的完整复看，判据不成立，**下轮须刻意复看**（先看一段 → 拖回已看区间 → 确认 hit > 0）。
- **overflow_status 文案（→ N20）**：`REBUILD_LAYOUT_FALLBACK mode=overflow_status` 时显示技术文案“字幕过长，原文暂不可用”，触发点集中在暂停、启动（`pending_engine`）与 seek（`late_unreadable`）。根因：原文兜底（英文整句）在大字号下排不进可用宽度，安全网从“显示原文”降级为“显示状态文案”。
- **大字号分页退化（观察，暂不立卡）**：r=300bps（全屏 82px）下出现碎片页，如事件 587-621 拆成 4 页、page2“例用于新装备和现代化，而”、page4“资、训练或维护现有系统。”，事件 687-721 出现 page3/5“在专门讨论了中国为何现”。N19 已将上限压到 56px/69.8px，第三轮补测时重点看大字档是否仍退化。

## 4d. 第三轮真机第二轮（诊断 `caption-diagnostics-1.3.5-20260930-222916.txt`，N22 包，观看 616s）

**健康项**：27/27 API 成功、0 失败、63,497 tok（**6,180 tok/观看分钟**，较上轮 9,709 明显下降）、输入缓存命中 49.5%；`size_tier=2`（标准档）全程生效，两形态 glyph 实测 44.0 / 55.0px（目标 44.5 / 55.5）；168 条 PRESENTED = 99 `caption_page` + 68 `caption` + 1 `status`；**`REBUILD_LAYOUT_FALLBACK` / `overflow_status` / `LATE_UNREADABLE` / 各类错误 / `CACHE_WRITE_FAILED` 全部为 0**；等待占位“翻译中…”出现 9 次（取代原文兜底），空白正文仅 1 条；无 <1.2s 短页；`REBUILD_FALLBACK_BEGIN` 仅 3 次且全为 pending 类；语义护栏 0 拦截。
**用户观察**：整体满意，仅提出一处版式小改（字号滑条与透明度滑条的长度/配色不一致 → 见 §6 小改卡）。
**审阅者发现**：
1. **长页偏多（建议后续处理，非阻塞）**：166 正文页中 **66 页 >4s**（占 40%），最长 **9.71s**，另有 8.02s / 8.46s 页；根因是长事件文本排得下 ≤2 行时不再按时间分页。Netflix 通用要求的单事件上限为 **7 秒**，我们已有多页越线；建议后续考虑“长事件按时间再分页”或设事件时长上限（属新版式议题，需用户确认）。
2. **孤字页已修复**：125.6s 处原 6 字碎片页现为整页“到2017年，部署在中国大陆的地对空导弹射程将覆盖台湾本身” ✓；本轮 9 条 3 字页全部是“翻译中…”占位，不是碎片页。
3. **A16 比较极性本轮未反转**：84.1s 段译为“……有些人对俄罗斯…印象深刻 / 胜过中国从无到有建立起航母舰队 / 在除美国外的所有国家之前部署第五代战斗机”，方向与原文一致（A16 仍为已签已知遗留，不作质量承诺）。
4. **缓存命中仍 0（26 lookups）**：与 N21 裁决一致（同 session 首次访问）；**仍需按 §6 第 4 项的新会话程序补测**。
5. **A15 未覆盖**：本轮观看 616s，未到 845s 区段。
6. 新增可见标记 `NATIVE_RENDERER_VIEW_NOT_FOUND`×4、`NATIVE_RENDERER_MASK_FALLBACK`×1；本轮无可见异常，暂记录观察。
7. 可读性告警 5 条（cps 4.36–6.03，均远低于 8）、质量告警 13 条全为 advisory，无需修复。
8. **译文准确性审阅（2026-09-30，审阅者用英文源 SRT 与 158 页逐段对齐核）**：总体准确，**实质性错误 2 处**（约 1.3%），其余为措辞生硬或可接受。**未复发的历史案例**：A02（"indexes"→"排名"）、A04（条件反问跨页重组后通顺）、A05（时间修饰已归位："甚至在入侵发生很久之前，我看到这些时真正想到的问题是：那中国呢？"）、A06（四类装备保留、2 页）、A11/A12（引导语不再独页、886ms 短页已消除）。**专名与数字抽检正确**：苏-57/歼-20/国民党/T-14"阿玛塔"、224 亿美元（22.4 billion）、2020/2035/21 世纪中叶。
   - **新发现 ①（实质漏译，327.0–331.2s）**：原文 "were either licensed or unlicensed copies or derivatives of soviet designs whether…" 的中文页仅作"无论是经授权还是未经授权，"，**丢掉中心词"苏联设计的仿制品或衍生型"**，与前后页拼起来语义不完整。建议登记为新案例（待用户签字）。
   - **新发现 ②（A14 复发第二轮，384.6–391.7s）**：原文 "foreign investment and the explosive economic growth that would follow **deng reduced** the share of gdp…"，中文页作"外资以及…经济爆发式增长，/ 降低了国内生产总值中用于解放军的份额"，**主语错接再次上屏且本轮语义护栏 0 拦截**。作为 A14 已知遗留的证据升级记录（与 A17 同类）。
   - **观察 ③（A16 相关，76.1–99.7s）**：比较方向本轮正确，但"印象深刻 / 胜过中国从无到有建立起航母舰队"被分页切断，"胜过…"单独成页为残句，属跨页硬断类问题（A08 同类）。
   - **轻微 ④（125.6–131.3s）**：原文过去时 "come 2017 … could range out over taiwan itself" 译作"…射程**将**覆盖台湾本身"，"将"读作未来；对应 N7 已签里"覆盖台湾本身"的措辞遗留观察。
   - **轻微 ⑤（468.7–470.9s）**：〔原字幕数字存疑〕前缀落在无数字的短页"相反，"上，提示位置不够精准（数字存疑判定为事件级，方向正确）。
   - **备注（设计后果，非缺陷）**：冷启动首个事件的头一页在"翻译中…"占位期内被跳过，其内容本轮未上屏（A01 选项 2 的固有代价）。
9. **两处问题的根因分析（2026-09-30，审阅者用 540 事件离线语料 + 源码定位）**：
   - **① 属采样方差，不是系统缺陷**：同一视频、同一 prompt SHA 的 N15r 全片语料里，**同一个源词范围 1043-1052** 的译文是"要么是授权或未经授权**仿制苏联设计的复制品或衍生型号**"（25 汉字，汉字/源词=2.50），而本轮同一范围只给了 11 汉字（1.10）并丢掉中心词 → 同一输入两次采样，一次完整一次漏译。**压缩比硬阈值不可行**：语料 540 事件比值中位 1.32，取 `ratio<1.10 且 words≥8` 会命中 105/540（**19.4% 误报**）。可行修法=扩展现有窄规则（`RebuildReview:67` 已存在 `licensed or unlicensed` 规则）：源含该短语而译侧未保留"仿制/复制/衍生/型号"类语义时报 `possible_omission`（repair=true）。
   - **② 属检测器条件过窄（wording drift bypass）**：`RebuildReview:45` 的 `possible_subject_attachment` 要求源匹配 `would follow <name> reduced` **且中文含"之后|之後"**；本轮译文用"**随之而来**"，条件不成立 → 未报警 → 无修复轮 → 错译上屏。语料里同一段被切成两个事件且主语归位（1219-1228"外资以及随之而来的经济爆发式增长" + 1229-1240"**邓小平降低了**国内生产总值中用于解放军的比例"）→ **模型能做对、修复轮有效**。最小修法=放宽或去掉中文侧条件（源侧模式本身已足够窄），命中后走既有修复轮；修不好则按已签 A14 策略**留空**，不再让错译上屏。
   - **验证方式**：两者都可在 540 事件语料上离线回放，报告新规则各命中多少条（预期 0–2 条，须给出实际数字），冻结计分板与验收判据不得变动。

## 4e. 频跳压力测试（诊断 0930-230120 luna / 0930-230442 qwen，用户主动每 1–2 秒跳转）

**场景**：用户以 luna（`openai/gpt-6-luna`）与 qwen3.8-flash 各跑一轮，相邻 seek 间隔中位 1.1s / 1.6s。
**问题一：频跳时等待偏长**（实测：luna 34 次 seek，seek→首个正文页等待中位 **4.41s**、p90 29.7s、**17/34 超过 3s**；qwen 13 次 seek，中位 **2.71s**、p90 13.5s、6/13 超 3s；占位页"翻译中…"分别出现 61 / 27 次）。三层根因：
1. **物理层**：一次块翻译＝一次模型往返，HTTP 中位 **5.6s / 5.5s**（luna 尾延迟 13–15s）。跳到未翻译位置必然要等一次往返，这是"块级翻译＋远程模型"的架构下限。
2. **调度层（可改）**：`RebuildController.IO` 是 `Executors.newCachedThreadPool`（**无并发上限**），seek 风暴下同时打出大量请求互相争抢——luna 一轮 34 seek 产生 50 请求／仅 39 响应，并出现 10 次失败（SocketException、output_truncated）与 13–15s 尾延迟；`REBUILD_HTTP_RESPONSE` 全部为 200，说明失败发生在传输/校验层而非 4xx。**当前块没有专属通道，预取与当前块同池竞争。**
3. **模型层**：luna 失败率约 20%、尾延迟更高；qwen3.8-flash 更稳（2/17 失败、尾 7.4s）。
   **缓存的实际作用**：luna 34 次 seek 中仅 1 次命中块缓存（`REBUILD_CACHE_RESTORED;block=16;network_calls=0`）——因为用户是向前探索而非回看；**回到已翻译区间会很快，向前探索一定慢**。
   **结论**：能显著改善（并发上限＋当前块优先通道＋风暴期暂停预取＋模型选择），但**不能消除**跳到未翻译处需等一次模型响应的下限（约 3–7s）。
**问题二：原生字幕空黑块（截图 90.jpg），AI 字幕同时不显示**。根因明确：插件靠"找到并隐藏 YouTube 原生字幕窗口 `SubtitleWindowView`"避免原生字幕出现，诊断里有两类失败标记——`NATIVE_RENDERER_VIEW_NOT_FOUND`（"TimedText is invisible, but no YouTube native caption window was found to hide in this player"，`CaptionMusicSuppressor:167`）与 `NATIVE_RENDERER_MASK_FALLBACK`（"Source track not ready within the short wait window; falling back to invisible ownership track"，`LoopbackCaptionServer:230-243`）。当"原生窗口未被隐藏"且"我们提供的轨/叠层此刻是空的（等待或按 N20 策略留空）"同时成立时，YouTube 就绘出**无文字的原生字幕底框** = 黑块。
**可修性**：问题二属工程健壮性缺陷，可直接修（无条件隐藏＋持续重试＋缓存视图引用＋扩大查找并补诊断）；问题一部分可修（调度层），物理下限不可消除，需在文案/预期上说清。

## 4f. N23 真机反馈与处置（2026-09-30 深夜，用户）

**用户反馈**：① 反应速度有提升，但仍有提升空间（D 段有效、未到位）；② 字号滑条**拉到最大/最小时两端刻度点不在轨道最左/最右端**；③ 预览应标注为**全屏**状态、并用**更长的示例句**；④ 滑条右上角不必再标数值（下方小字已有）；⑤ 标题去掉"（详情页）"；⑥ **翻译质量相关一律回退到 N22**。
**处置**：
- **B 段整体回退**（`RebuildReview` 两处：把 `possible_subject_attachment` 的中文侧条件 `之后|之後` 加回；删除 N23 新增的 `licensed or unlicensed → possible_omission` 整段），并同步回退 `RebuildN23ReviewTest` 中对应两条用例。**A14 回到已签基线**：检测到时留空，漏检时接受偶发错译上屏；320–331s 的漏译不再拦截。
- **A 段继续调整**：滑条可见轨道与 thumb 行程统一（两条滑条同一几何），使首/末刻度与轨道两端重合；删右上角数值；标题去括注（14 语种同步）；预览标注全屏 + 换长示例句，仍按全屏比例渲染。
- **C/D 保留**（原生空黑块修复、并发闸门与风暴期暂停预取）。
- **新锚点**：本卡产物 `-n24` + 标签 `anchor/n24-<短哈希>`。
**待用户确认（仍未决）**：327s 漏译与 384s A14 复发是否登记进 ACCEPTANCE（默认不登记，只留 §4d 观察记录）。

## 4g. N24 执行结果（2026-10-01 凌晨，用户卡：质量回退 N22 + 设置界面修正 + 保留 N23 工程改进 + 有界调度优化）

**A 翻译质量回退（用户明确决定撤销 N23 的 B 段）**：`RebuildReview.java` 用 `git checkout a482262 -- …` 还原，**相对 a482262 逐字节零差异**（blob 同为 `0a00c7049224b106293084600e6305b0ca613683`）。撤销两项：① `possible_subject_attachment` 恢复中文侧 `之后|之後` 条件（措辞漂移不再被救回，A14 回到已签基线：检测到留空、漏检时接受偶发错译上屏）；② 删除 N23 新增的 `licensed or unlicensed → possible_omission` 整段（327s 漏译不再拦截），仅保留 N22 的 `possible_authorization_expansion`。测试同步：撤销 2 条要求新行为成立的用例，新增 2 条反向回归用例证明回到 N22；540 事件回放台账保留，输出改到 `.verification/n24/review-hits.json`（`.verification/n23/` 历史结果未覆盖），实测两规则命中均为 0。**N23 的 C/D 工程改动全部保留**（`CaptionMusicSuppressor`、`LoopbackCaptionServer`、`RebuildController`、`DeepSeekSliderPreference` 及对应新增测试）。本回退**不代表** A09/A14/A16 已知遗留得到解决。

**B 字号滑条端点统一**：修前实测（420dp、density 1）平台轨道绘于 `[22,357]`（两端各内缩 6px，且内缩量与布局宽度无关），刻度却按 `[16,364]` 计算，thumb 行程又是第三套——三者互不相同，即用户所见「两端刻度不在轨道两端」。修法：新增 `DeepSeekSliderPreference.RailBar` 基类，两条滑条共用，自行绘制轨道/刻度并按同一公式落 thumb；可见轨道 = `[paddingLeft, width − paddingRight]`，刻度 = 端点 + 四等分，thumb 中心 = 同源插值。修后实测（亮/暗 × 超小/标准/超大）：两条轨道长度差 **0px**（均 362px，`[9,371]`）；首/末刻度 vs 轨道端点 **0.0px**；thumb 中心 vs 刻度 **0.0px**（5 档）；档名 vs 刻度 **0.5px**。等长、同边距、同取色来源，五档吸附/实时预览/松手保存/当前档高亮/档名对齐/RTL 全部保留；字号五档与 55.5/44.5 缩放、运行时字体测量、旧值迁移未改。

**C 文案与全屏预览**：`size` key 14 语种去掉「（详情页）」；**仅字号条**移除标题行右侧数值（透明度条保留实时百分比，新增回归用例锁死）；当前档说明继续显示详情页/全屏 px；`preview` key 14 语种改为「横屏全屏预览」语义。预览本就按全屏比例渲染（字幕在 2736px 参考系排版后整幅缩放一次），本卡补断言 `预览字高/预览宽 == 全屏字高/2736`。**示例句长度的硬限制**：预览宽 380px、字幕最大宽 ≈347px，标准档单字 advance 59px → 两行仅容约 11 字、超大档约 7 字；故示例句取「窄预览 + 五档全档位都不截断且为完整句」的 `字幕要自然。`，并由用例在 320/420/960 × 五档下断言不截断且行数 ≤2。这是预览画布尺寸限制，不是字号规则改动，未单独缩小预览字体。

**D 有界调度优化（本卡为执行段，设计由规划者给定）**：`MAX_FOCUS_CONCURRENCY=2`（`PRIORITY_IO` 1→2 路）、`MAX_PREFETCH_CONCURRENCY=2`、`MAX_TRANSLATION_CONCURRENCY=4`；前后台分池，后台结构上不占前台槽位；预算按**实际在途**统计（含旧位置在途请求）。新增 `Session.pendingFocus`：至多保留一个**已选中未派发**的最新前台请求，新 seek 直接替换旧待办，被替换者释放 `jobs[i]`、状态回 WAITING、**attempts/repairCount 零消耗**（尝试与修复改为在真正派发时 `markDispatched()` 记账）；一条在途 + 一条空闲时新落点立即使用空闲槽位。seek 仍不强行取消已发送请求。同块在途复用（`REBUILD_BLOCK_REUSED`，含后台转当前）、缓存恢复仍在网络派发之前。`allowAhead` 去掉「存在任何非当前块在途作业即禁止」条件，改由后台预算 + 30 秒范围 + 暂停/时钟条件决定，取消「每轮只起一个新作业」限制。供应商请求、超时、429/5xx 冷却、Retry-After、校验与修复上限**逐字未改**；本卡 0 次远程 API。诊断新增 `REBUILD_FOCUS_PENDING_HELD`/`_REPLACED`/`_PROMOTED`、`REBUILD_LANE_RELEASED`、`REBUILD_BLOCK_REUSED` 与 `focus_in_flight`/`prefetch_in_flight`/`pending_focus_block`/`dispatched`/`sent`；排队与网络、本地处理分列。**离线只证明调度与排队行为**（6 条新用例驱动生产 `schedule()`/`time()` 与真实 dispatch/translate 生命周期 + 本地 MockWebServer 门闸：旧前台阻塞时新落点用第二槽位、两条在途时只留最新待办且不耗重试额度、两路合格预取可并存且超 30 秒不派发、在途总数 ≤4/前台 ≤2/后台 ≤2、同块复用与缓存恢复与会话结束释放、旧结果不上新落点）；**不代表真实 API 延迟改善**。

**交付**：`build/local-test/patches-1.3.5-本地测试包-n24.mpp`（1,060,333 B，+3,714 相对 N23，`F7811B72…`）、`extension-1.3.5-本地测试包-n24.mpe`（2,698,036 B，+6,620，`A12CBA46…`）、`build/n24-composition-final/YouTube-21.16.256-本地测试包-n24-unsigned.apk`（196,800,948 B，+2,972，`59C0B243…`；未签名确认）。组合 **84/84 PASS**（既有 82/82 全在 + 官方新增 2/2）；`DEX_AUDIT_PASS classes=58028`；`verify_bundle` / `N8Verify`（72 条目 / 14 locales / CRC / root DEX）通过；12 个历史产物前后 SHA-256 全一致（N22/N23/N18r/v1.3.5 均未覆盖）。Java **429/429**（53 套件，0 失败/错误/跳过）、Python **27/27**、本地化 129 keys × 14 语种、冻结计分板 **4 通过 / 4 既有失败 / 4 未验证**且三类不可见时长全 0、冻结证据与 ACCEPTANCE.md 未改。
**真机仍待用户验证**：滑条两端刻度/thumb/档名、全屏预览比例、原生空黑块，以及缓存命中与未翻译区间各自的等待表现；并对比未缓存落点的 `slot_wait_ms` 与 `network_ms`、停止频跳后首条译文呈现时间、缓存恢复时间、请求数与 token 消耗。**离线不做任何延迟改善承诺。**

## 4h. N25 执行结果（2026-10-01，用户卡：设置排版修正与十四语种完整本地化）

**A 预览去重复标题**：`SubtitleStylePreview.onCreateView` 删掉可见的「横屏全屏预览」标题（非空串占位、未换重复标题），节标题「字幕样式」由外层 section 提供；画布 `contentDescription` 保留该本地化文案。现为「画布 + 说明行」两个子视图，上间距 6dp／说明 2dp。**顺带修一处真实缺陷**：`sampleLabel` 原以 `AT_MOST` 测量，`TextView` 会回答自己想要的完整宽度，长句因此被画到视频框外、尾部被裁；现按上限（参考宽 ×0.92 − 2×6dp = 2505px）`EXACTLY` 测量并排版。

**B 五档标签与 RTL 几何**：新增两条滑轨共用的「标签让位」内缩 `inset`，由**实测档名宽度**与行宽取满足「两端档名在行内 `i ≥ w/2`」与「相邻档名不相碰 `i ≥ (2(w_j+w_{j+1}) − S)/4`」的最小值，上限 `2S/5`。可见轨道 = `[paddingLeft+inset, width−paddingRight−inset]`，首末刻度＝轨道端点＝对应 thumb 中心，五档等距；两条条同宽同档名故等长同边距同取色。实测 420dp/row 380/inset 32/轨道 `[41,299]` 长 258px：`label_widths=[64,32,52,33,62]`、`label_lefts=[9,90,144,218,268]`、`tick_centers=[41,105.5,170,234.5,299]`，最大中心偏差 4.5px；320dp 窄行五名齐全、无重叠、全在行内。超份额的档名在**份额内换行**（≤2 行），不裁切／不省略号／不重叠。**RTL**：新增唯一映射 `physicalFraction()`／`physicalCenterX()`（RTL 取 `1−logical`），thumb／刻度／填充带／档名行全部只消费它，消除原「刻度镜像而 thumb 不镜像」的双重反转；`rtl()` 先读已解析祖先方向，未附着时回退到本地化配置，绝不用未解析默认值。**边界如实报告**：Robolectric 不把布局方向下推到普通 `View`（RTL 父的 RTL 子仍报 LTR，探针实测），无法搭建 RTL 控件树；`N25TierRailTest` 因此验证映射的两半（行方向＝上下文已解析方向；轨道端点／thumb／五刻度／标签中心／填充带边界全部随该方向取值）＋透明度条与字号条几何全等，**RTL 真机仍须目检**。

**C 示例资源键**：新增 `preview_sample`，经 `CaptionStrings.settings` 读取（与页面其余文案同源、受 Morphe 语言覆盖），不再依赖「对未登记中文调用 localize 再回退」。十四语种按卡片意图落地，仅俄语一处笔误（混入一个汉字）已修为「Мир огромен. Посмотрим вместе!」。**实测**（参考宽 2736/预算 2505、最大档全屏字高 69.84px）：十四语种完整 advance 最大为**越南语 1277px**（余量 1228px），标准档最大 1010px——五档下全部单行完整显示，断言用 `Layout.getLineCount()==1` ＋ `getLineEnd` 覆盖整串 ＋ `measureText` ≤ 预算 ＋ 非 ellipsize 四项，并渲染 14 张 fixture 目检。另新增「真实 `Preview` 画到位图、用墨迹实测字幕底框左右边界」的用例。

**D UI 本地化补齐**：新增 **76** 键 × 14 语种，总数 129→**220**。修掉：诊断长说明无整句映射（substring 留混合语言）、「保存完整诊断」无资源条目、复制成功 Toast 绕过本地化、保存失败／保存位置拼接中文片段、清空确认说明、Android 9 分段复制的标题／选项／成功提示／Clipboard 标签、模型行全部状态与「已选择」无障碍（`selected_suffix`）、预览示例、`CaptionDiagnostics.uiText` 与 `TokenCostAudit.uiText` 的固定英文标题与说明（52 个 `audit_*` 键）。**UI 与 raw 分离**：`uiText(c)` 默认本地化、新增 `uiText(c,false)`，`fullText` 只调后者，导出原始报告表头逐字不变；REBUILD_* 事件名／JSON 键／错误码／源文译文时间戳请求ID计数值全保留。**顺带修一处真实缺陷**：`DeepSeekActionPreference` 原用 `message.startsWith("API 可用：")` 判断成功，文案本地化后即失效、成功时不再刷新配置；改为布尔标志并把三句各做成整句模板。非 UI 字符串（质量检测正则、供应商提示词、YouTube 原生按钮识别词、目标语言名表、内部异常标识、原始报告表头、字体标定样本、构建材料）分类留档，清单 `.verification/n25/ui-localization-inventory.md`（18 调用点 → 资源键 → 覆盖状态 ＋ 8 类「刻意不翻译」及理由）。未用全局 `Locale.setDefault`，未改写用户数据。

**交付**：`build/local-test/patches-1.3.5-本地测试包-n25.mpp`（1,101,113 B，+40,780 相对 N24，`36F885BE…`）、`extension-1.3.5-本地测试包-n25.mpe`（2,713,520 B，+15,484，`C75B1067…`）、`build/n25-composition-final/YouTube-21.16.256-本地测试包-n25-unsigned.apk`（196,935,268 B，+134,320，`9FE3A03F…`；**未签名确认**）。组合 **84/84 PASS**；`DEX_AUDIT_PASS classes=58028`；`verify_bundle`／`N8Verify`（72 条目 / 14 locales / CRC / root DEX / MPE 与包内扩展逐字节一致）通过；12 个历史产物前后 SHA-256 全一致（N18/N18r/N22/N23/N24 及既有本地包均未覆盖）。Java **437/437**（56 套件，0 失败/错误/跳过；N24 为 429）、Python **27/27**、本地化 **220 keys × 14 语种**、冻结计分板 **4 通过 / 4 既有失败 / 4 未验证**且三类不可见时长全 0、冻结证据与 ACCEPTANCE.md 未改。提交 `99e7be5`，锚点 `anchor/n25-99e7be5`（未推送）。
**自查修正（记录在案）**：初版本地化表头把 `label_separator` 套在已带冒号的头名上（中文「引擎：：」、英文「Engine: :」），已改为裸标签＋单一分隔符模板并在管线内加断言与幂等归一；相应产物与哈希为本卡最终值。
**真机仍待用户验证**：预览去标题后的观感、系统大字体下五档档名排版、RTL 系统语言下滑轨方向与拖动一致性、十四语种界面文案与诊断摘要可读性。**本卡不做真机、不签名、不发布；L 线未启动。**
**未解决（沿用观察，本卡未改）**：§4d 的两处译文问题（327.0–331.2s 中心词漏译、384.6–391.7s A14 主语错接复发）与另两处轻微项仍为观察记录，未登记进 ACCEPTANCE；560s「到本世纪中叶」错接修饰对象只有生成记录、未见上屏记录；353.559–355.513s「军队拥有数百万人员」关联源字幕 shared、疑似源字幕 ASR 错误，**无音频故不得把推测的 shed 当作已确认原文**；另一视频的 3 次 `source_quote_mismatch` 属源引用格式差异而非网络失败；7 条 `overflow_status` 展示记录只涉及 3 个事件，其中选中译文按码点计 CPS 为 9.026／9.020／8.286，均越过既有 8 门槛，分页器在测量布局前即返回空计划——**不能一概归因于像素溢出**。这些问题的通用且低回退修法仍未确立，本卡未动提示词、CPS 口径、修复候选排序、源引用容错或分页参数。

## 4i. 后续规划用户定案与 N26 待执行（2026-10-01）

用户已确认九项选择：
1. 1A：AI 字幕设置迁入“视频”，只留一个入口。
2. 用户最新调整：固定紧邻旁白翻译，前后位置由规划者决定；已确定放在旁白翻译之后。保留视频父页现有 sort_by_key，利用外层导航key morphe_vot_screen__ai_captions 在宿主排序中紧跟 morphe_vot_screen，内部持久化key不变；旧外层导航key作为移除旧入口的别名保留。若旁白补丁未安装，则在视频页正常排序位置显示。此决定覆盖先前2A的自然放置安排，不改其他项目顺序。先前BY_TITLE描述已纠正，N25实际父屏为 morphe_settings_screen_12_video_sort_by_key。
3. 3A：首轮播放器避让仅常规详情页与横屏全屏；Shorts 暂不纳入。
4. 4C：追加语言集合默认空，不自动勾选简中或十四种语言。
5. 5A：AI 关闭时仍保留所选追加语言，原生路径需验证。
6. 6A：保留现有中文体验，繁中补回归，不将9CPS/16字参考直接改成中文新门槛。
7. 7A：新语种读速先作为软目标/告警，结构与几何仍为硬约束。
8. 8A：新语种7秒展示页目标，中文先不变，不设全部语种7秒硬上限。
9. 9A：菜单目标语言优先，已保存自定义要求不改写。

用户已装机目视确认 N25 本轮提出的内容修复妥当。此为所观察界面问题签字，不扩大为十四种译文语义均已验收。下次小改已授权：预览框下说明改为“样式预览（全屏）”，十四语种同步，不恢复删除的重复标题。

四阶段：
- 第一阶段/N26：入口迁入视频并紧跟旁白翻译之后、无图标、单入口、保留父页实际排序和其他条目顺序；修改 preview_hint；必要验证与新测试包。只改资源和文案。
- 第二阶段：常规播放器控件避让；仅显示层临时偏移，保留用户基准，不改字体、分页、归属或API请求。
- 第三阶段：十四语种参数档案、字素/行长计数、方向、实际源/目标语言路由；可拆为“档案与计数回归”和“接入呈现/提示/缓存并验证”两个闭合执行卡。中文基线保留。
- 第四阶段：十四种候选多选补入自动翻译菜单、默认空集合、去重/排序/持久化、第二个简中补丁合并到AI root；保留字幕记忆独立补丁。

先完成并审阅上一阶段再发下一阶段执行卡，任何时刻只允许一个执行者修改仓库。各阶段保留独立回退锚点。历史中让新DeepSeek对话承担研究/规划或直接启动旧L1调查卡的安排，由本节最新角色分工与四阶段顺序覆盖；DeepSeek只执行本聊天已明确的任务卡。

N26 开工基线为 2f7841d；本节是规划者的管理文件更新，当前 docs/PROJECT-STATE.md 未提交修改应随 N26 提交，不回滚成旧档案。四阶段中**第一阶段（N26）已执行完毕**（见 §4j），第二至第四阶段仍未实现；其他历史真机清单与验收判据不变。

## 4j. N26 执行结果（2026-10-01，用户卡：设置入口迁入视频页 ＋ 全屏预览说明）

**A 入口迁入视频页**：外层导航 key `morphe_settings_screen_13_ai_captions` → **`morphe_vot_screen__ai_captions`**；旧 key 作为删除别名保留，带它的残留节点在装配时被清除。AI 子屏内部 17 个持久化 Preference key、类名、控件顺序、SharedPreferences 名称与 Keystore alias 全部未动，**无用户数据迁移**。装配按字面 key 绑定已验证的 `morphe_settings_screen_12_video_sort_by_key`（该后缀由官方补丁 `PreferenceScreenPreference$Sorting.appendSortType` 在打补丁阶段拼出），**先确认父屏唯一存在再删除、最后插入**；旁白项 `morphe_vot_screen` 存在时插其直接后继，不存在时挂入视频父屏尾部，不新增旁白补丁依赖；存在 Morphe 设置却找不到唯一视频父屏时**抛具名 `PatchException`**，不回退顶层、不回退原版 YouTube 设置。**排序事实**：宿主排序在运行时进行并重赋 `android:order`（三套资源中该属性出现 0 次），故 DOM 位置不是相邻性的证明。从交付的官方扩展 DEX 反读 `AbstractPreferenceFragment`：`_sort_by_key` 组内所有子项（含子屏）以 `Collator.compare(childKeyA, childKeyB)` 排序，Collator = `Collator.getInstance(MORPHE_LANGUAGE.get().getLocale())` 且 `setStrength(SECONDARY=1)`，无 key 的 `NoTitlePreferenceCategory` 取 `getPreference(0).getKey()`，其余 null key 抛异常；子项排序模式默认继承父组。新 key 是旁白 key 的严格前缀，故十四语种下均紧随其后。规划者先前把默认 `BY_TITLE` 当作视频页实际排序的说法**已在 §4i 纠正并在此以字节码证据定案**。

**B 入口样式**：入口图标属性面为空（不设 `android:icon`／`android:layout`／`app:iconSpaceReserved`），与三套资源中 **25 个原生嵌套子屏（含紧邻旁白项）逐属性相同**；旧 `@layout/preference_with_icon`（含 18dp 边距的 `@android:id/icon` 槽）与旧 `@drawable/deepseek_caption_settings` 引用已从设置行移除，该 drawable 仍因 `CaptionQuickToggle` 使用而保留生成。framework 行渲染：AI 项／旁白项／同页叶子项标题左内缩**同为 16px**，真带图标的对照行为 **72px**（探针灵敏度已验证），亮暗主题一致。

**C 预览说明**：`preview_hint` 十四语种改为「样式预览（全屏）」系，只改该键显示值；未新增重复标题、未改 `preview_sample`／比例／字体／五档／透明度／交互；N24 删除的「横屏全屏预览」标题未恢复。`localization/catalog.json` → `tools/generate_localization.py` 重生成 14 份 XML ＋ `source-keys.tsv` ＋ Java fallback；新增 `tools/apply_n26_preview_hint.py`。`preview_hint` 不在 `n25_ui_strings.py` 的 `EN`/`REUSE` 中，故重跑 `apply_n25_catalog.py` 不会还原旧文案；`git diff` 仅 14 行改动。

**验证**：Java **440/440**（58 套件；N25 为 437/56，新增 `N26EntryRowTest` 1 条 ＋ `N26PreviewHintTest` 2 条）；Python **27/27**；本地化 **220 keys × 14 语种**；冻结计分板 **4 通过 / 4 既有失败 / 4 未验证**且三类不可见时长全 0，`frozen-baseline.json` 与 `ACCEPTANCE.md` 未改；组合 **84/84 PASS**、`DEX_AUDIT_PASS classes=58028`；结构/幂等（二次装配不变形、父屏缺失与重复均被拒绝、交付 DEX 内含具名失败消息）、**宿主真实排序 14 语种 × 3 套资源**、**交付 APK 内三套 XML 含新 key 且不含旧 key**、**`resources.arsc` 字符串池精确包含十四语种新说明且不含任何旧说明**、`verify_bundle`／`N8Verify`／ZIP CRC／11 根 DEX 头均通过；20 个历史产物前后 SHA-256 全一致。未做真机、未签名、未发布、未推送。

**交付**：`build/local-test/patches-1.3.5-本地测试包-n26.mpp`（1,103,820 B，+2,707 相对 N25，`01B80F88…`）、`extension-1.3.5-本地测试包-n26.mpe`（2,713,536 B，+16，`4C0D21FF…`）、`build/n26-composition-final/YouTube-21.16.256-本地测试包-n26-unsigned.apk`（196,935,284 B，+16，`4BC02228…`；**未签名确认**，aapt 确认 `app.morphe.android.youtube` 21.16.256／minSdk 28／targetSdk 36）。详见 `docs/N26-LOCAL-TEST-BUILD.md`。

**真机仍待用户验证**：顶层旧 AI 入口消失、视频页只有一个正常风格且紧跟旁白翻译之后的 AI 入口、旁白与其他设置不受影响、原 API/profile 与字号/透明度/位置/开关读回不变、进入子屏与返回导航正常、预览下方为「样式预览（全屏）」且随应用语言变化。**离线不做真机结论**；下一阶段为常规详情页与横屏全屏的播放器控件避让（尚未执行），本卡未启动。

## 4k. N26 真机新反馈、最终语言闭环与 N27 边界（2026-10-01）

N26提交边界已核对：509d50a含源码/文案，anchor/n26-509d50a指向它；HEAD7f9c639较锚点仅改docs两文件。用户已装机，报告三项新问题，以下全部未关闭：
1. 视频页AI入口summary仍是“修改后自动保存”，应像旁白翻译summary一样介绍功能；最终阶段更新为功能说明并适配全部界面语言。
2. 十四语种运行时适配不完整且表现不同。英语/法语等切换后，默认翻译要求、预览示例、字号/档名等仍有中文。不能因catalog/交付资源/离线fixture通过而宣称运行时适配完整；最后必须从应用语言实际解析、配置默认值来源与绑定、资源fallback/缓存和所有用户可见调用点统一审计，并明确按应用语言取值。用户自定义内容不得被误当默认值而改写。
3. 用户最新明确要求程序性诊断内容为英文，当前仍出现中文。最终阶段统一检查：技术正文/表头/字段/事件/原因/状态/计数等使用英文；设置页外壳、功能说明、按钮与Toast等用户操作文案适配界面语言。源字幕、实际译文、用户自填配置与提供商原始证据不为“英文诊断”而改写。

用户明确：暂不执行以上修复，留待已规划全部功能做好后最后统一检查，避免中途改动再次引入遗漏。后续闭环顺序为：N27避让 → 语言档案/计数/方向及接入 → 菜单多选与简中root合并 → 最终全面本地化/功能说明/诊断英文审计修复。最终阶段不是可选项；在它完成前不写“十四语种适配已全部完成”。本次真实反馈优先于N25/N26此前对本地化覆盖的推断，历史离线测试事实保持，不重写历史结果。

N27只做普通视频详情页和横屏全屏的显示避让，默认生效，不新增设置控件；Shorts/小窗/PiP排除。仅在与已验证控件矩形相交且存在安全空间时临时上移；控件隐藏后回原基准，不修改用户保存的位置、字体、分页、源时间、原生/AI ownership和API调度。新增N27诊断事件/字段/原因全部英文，不趁机重构旧诊断正文或修复本节语言问题。

N27开工HEAD7f9c639。本节是规划者管理更新，docs/PROJECT-STATE.md未提交改动须保留并随N27提交，两份档案同步。研究和任务设计由本聊天负责；DeepSeek只按已明确的N27任务卡执行，不自派后续阶段。

## 4l. 历史交接准备（用户随后取消，当前以§4n为准）

已准备交接目录 `C:\Users\14776\Documents\morphe-caption-handoff\2026-10-01`，入口 `HANDOFF.md`，新对话首条提示词 `START-HERE.txt`，附N27当前执行卡、研究草案、语言参数草案、N24审阅记录、N26交付记录和本档案快照。新对话仍是规划/审阅者，DeepSeek只执行。此交接只修订管理说明，未改产品源码、未跑测试/构建、未生成N27产物、未提交/签名/推送/发布。

交接核对：E盘HEAD7f9c639、源码锚点anchor/n26-509d50a；规划者只修改docs/PROJECT-STATE.md管理文档；末次只读核对另见DeepSeekCaptionPatch.kt、Fingerprints.kt修改，以及CaptionControlsAvoidance.java和CaptionControlsAvoidancePatch.kt新增未跟踪文件，均不是规划者修改。N27卡已交付用户，执行疑似已开始；未收到N27报告，尚无N27提交/锚点证据。不读取在途工作为最终实现，也不清除这些改动。接任者必须读取实时Git状态，若N27已在执行或已完成，不重复派发卡片。

**研究旧值注意**：§7b–§7d为历史调查记录，不作为当前执行指令。英文普通成人字幕参考读速已由2026-10-01逐语种指南复核为20CPS，旧“英文17”不可套用；中文保留现行基线，外部9CPS/16CPL不是新门槛；字素/行长计数与断行参数需要在第三阶段明确化和校验。研究JSON中的“pending/unapproved”是草案生成时的旧标签，九项原则性选择已经确定，不能因此再问用户一遍，也不能把尚未实现的细节宣称已经上线。

## 4m. N27 执行结果（2026-10-01，用户卡：普通播放器控件避让）

**A 可见性输入**：本 patch 内自建 `PlayerControlsVisibilityFingerprint`（公开无参 `getPlayerControlsVisibility`、返回 `L`、过滤器 `IGET`＋`INVOKE_STATIC`，即官方 `PlayerControlsVisibilityEntityModelFingerprint` 的同构模式），**唯一匹配**为 `classes3.dex Lbfec;->getPlayerControlsVisibility()Lbfee;`；真实状态字段 `Lbfed;->e:I`、真实 enum 工厂 `Lbfee;->a(I)Lbfee;`（`Lbfee;` 经确认是 `Ljava/lang/Enum;` 子类，常量名与 `UNKNOWN/WILL_HIDE/HIDDEN/WILL_SHOW/SHOWN` 对应）。注入点是与官方**同一条实体模型构造器路径**：`<init>(Lbfed;)V` 内确认持有者存储 `iput-object v1, v0, Lbfec;->c:Lbfed;` 恰一次、其后无 return、末尾 `return-void`，再把**已初始化**的 enum 交给自有回调 `DeepSeekCaptionHookV2;->onPlayerControlsVisibility(Ljava/lang/Enum;)V`。与官方块的关键差别是**先从 owner 寄存器重载持有者**，使官方 hook 先行把该寄存器覆写成 enum 时本卡仍成立；**交付 APK 内实测两个 hook 同处该构造器**（13 条指令，官方在 [2]–[5]、本卡在 [6]–[11]），两种注入顺序的寄存器类型抽象解释均为 safe，删掉重载的对照重放会被判 collision（非空转）。绑定失败一律具名 `PatchException`，不猜混淆名与字段语义、不静默回退；作为 AI root 内部字节码依赖接入，用户不需另选补丁，官方同名类未重打包、官方 hook 未替换或删除。enum 通知只是触发信号，几何在**主线程/下一绘制帧**读，跨 Activity/视频/模式的排队回调按 epoch 丢弃。

**B 控件与坐标**：12 个控件名**按名称**运行时解析（无 `0x7f` 硬编码），并已对交付 APK 的 `resources.arsc` 复核真实编号（如 `youtube_controls_bottom_ui_container=0x7f0b1841`、`player_seekbar=0x7f0b0ee1`、`timestamps_container=0x7f0b163e`）；交付 APK 自带布局亦已解出（`res/QYO.xml`/`res/Q1y.xml` 为底部条，`res/zi9.xml` 为按钮行与各 `*_touch_area`，`res/zmf.xml` 证明 `youtube_controls_overlay` 与 `inset_controls_overlay_wrapper` 都是全屏透明容器，**明确排除**）。绑定只在当前 player 子树内 `findViewById`，弱引用缓存，重绑走既有 ≈500ms 低频路径与可见性触发，不开无界定时器；矩形要求已附着、`isShown()`、祖先 alpha 连乘 ≥0.15、真实裁剪后非空并与视频矩形求交。折叠进度红线双重防护：底部簇以底部容器可见为前提，且合并簇高 <12dp 判为进度线。字幕碰撞盒取**真实 TextView 的盒**（含背景与内边距），不用 anchor 全宽透明条。

**C 算法与动画**：以本帧基准盒求“刚好放到相交障碍上方 +8dp”的最小上移；抬起后再遇更高障碍继续上移，迭代次数以障碍数为界；结果必须完整落在视频矩形内，否则保留原位、记一次 `no_safe_space`，不缩字、不隐藏有效正文、不推出视频。控制器隐藏即目标归 0、回用户原基准。偏移用 anchor 的 `translationY`，**基准布局与临时偏移分离**：`render()` 照旧重设 `LayoutParams`，动画不会被重 render 归零、上移也不会漂移进基准；水平位置/宽度/字体/字号/正文/分页完全不变，未改 `LayoutBudget`、未触发翻译或重译。参数（本卡实施值，未宣称真机已测）：8dp 间隔、200ms 减速、有效几何当拍即开始、目标取整像素、同目标不重启、<1px 不重启、目标变化从当前视觉位置重新定向。隐藏/空白/GONE、guard 抑制、Activity 销毁、换视频、播放模式切换均取消动画并清偏移；旋转或视频矩形变化先取消旧方向目标再按新矩形与原归属基准重算。拖动沿用 N26 350ms 阈值与保存行为：开始拖动只取消动画并保留当时视觉偏移，拖动期间冻结该偏移，保存仍为“原基准 + 手势增量”，**避让偏移从不写入配置**；空白/最终失败态不建空框。

**D 整合**：`CaptionOverlay`（实际 class `CaptionOverlay`）仅在 attach/render/hideView/detach 与 drag 路径新增协调器通知；正文选择、`RebuildPageLayout`、`RebuildController` 与 API 调用逻辑未动；V2 门面新增 `onPlayerControlsVisibility` 并转发 Activity/video/type 清理。DEX 契约检查证明协调器只引用平台视图/几何/动画、自有包、`CaptionDiagnostics.mark` 与 `CaptionSurface.isShorts/player`，禁用面（API 客户端、翻译/分页/重建、缓存、质量追踪、诊断归档、目录字符串、`getSharedPreferences`/`edit()`、`LayoutBudget`/`RebuildPageLayout` 成员）**命中 0**。

**E 诊断与验证**：新增 `CAPTION_UI_AVOIDANCE_BOUND/TARGET/RESET/UNAVAILABLE`，字段 `epoch/player_type/controls_state/video_rect/caption_base_rect/obstacle_rects/offset_px/reason/trigger/obstacle_ids`，事件名、字段名与全部 reason 值**从第一行起全部英文**（新代码不重构旧诊断正文、不补本期无关文案）；按 `epoch|offset|reason` 等键去重，稳定障碍只记一次。N27 测试 **24 条 + 1 套件**，全部经真实 `CaptionOverlay` 渲染/隐藏/拖动路径与真实协调器运行：无碰撞与隐藏 UI 偏移 0、底部碰撞最小上移、多障碍有限迭代、无安全空间回原位并只记一次、非零 host 偏移换算、祖先 alpha/不可见/脱离 View/全视频透明容器排除、折叠红线单独存在不抬高、原基准与字体/文本/页身份/保存位置不变、显示→隐藏→显示、同目标不重启、中途重定向不跳变、受控时钟下 200ms 收敛、拖动视觉冻结且偏移不入配置、换视频/旋转取消旧目标、晚 inflate 在低频路径重绑、陈旧 epoch 回调丢弃、Shorts/迷你/PiP 不应用、诊断全英文无重复。**帧图**由既有 Android 渲染路径（Robolectric NATIVE ＋ 真实 overlay/控件树，非 HTML）导出 **24 张**：`{详情页 360×640, 横屏全屏 640×360} × {小/标准/超大} × {before, showing, after, hidden}`，`showing` 帧使用从交付资源读出的底部条形状。

**离线范围与实测边界**：控件名与真实编号、宿主布局、注入指令与两 hook 并存顺序均有交付物证据；**YouTube 运行时是否真的把这些 View 挂到当前 player、以及 8dp/100ms/200ms 的真机观感仍待用户真机确认**（离线用名称→id 注入 ＋ 既有 `CaptionSurface` 几何接缝，合成播放器不能证明真实控件已绑定）。Robolectric 的 `ValueAnimator` 会把时间线塌缩成一拍，故动画改由主 Handler 按 `SystemClock` 逐帧驱动（生产同为主线程逐帧、200ms 减速不变），使离线受控时钟下的推进/取消/重定向真正可验证；真机时延未测。MPP/MPE/APK 的 ZIP manifest 带构建时间戳，同一源码两次构建整包哈希不同；两次构建的内部 `classes.dex` 与 `extension.mpe` 逐字节相同，两次组合的 APK 之间只有 `classes2.dex`（patcher 元数据）不同——交付哈希为**本次实例**哈希。交付 APK 由**交付 MPP 本身**重新组合与审计。

**验证**：Java **464/464**（59 套件；N26 为 440/58）；Python **27/27**；本地化 **220 keys × 14 语种**；冻结计分板 **4 通过 / 4 既有失败 / 4 未验证**且三类不可见时长全 0，`ACCEPTANCE.md` 与 `frozen-baseline.json` 未改；组合 **84/84 PASS**（`structure.txt` 含 `onPlayerControlsVisibility=1`）、`DEX_AUDIT_PASS classes=58034`；`verify_bundle` 与 N27 产物审计通过（MPP 74 条目、内嵌扩展逐字节一致、14 份 locale XML 与源一致、N25/N26 标记全保留、12 个 N27 控件名在扩展内、两个全屏排除 id **不在**扩展内；APK 16604 条目 / 11 根 DEX / 无签名文件 / 三套设置 XML 仍为新 key / `resources.arsc` 含十四语种新说明且不含旧说明 / 官方 hook 与本卡观察者同处 `classes.dex`）；39 个历史产物前后 SHA-256 全一致。

**交付**：`build/local-test/patches-1.3.5-本地测试包-n27.mpp`（1,117,781 B，+13,961 相对 N26，`BB197188…`）、`extension-1.3.5-本地测试包-n27.mpe`（2,727,272 B，+13,736，`45F2C9B6…`）、`build/n27-composition-final/YouTube-21.16.256-本地测试包-n27-unsigned.apk`（196,943,040 B，+7,756，`BF4C53EB…`；**未签名确认**，aapt 确认 `app.morphe.android.youtube` 21.16.256／minSdk 28／targetSdk 36）。锚点 `anchor/n27-62c4916` 指向唯一实现提交。详见 `docs/N27-LOCAL-TEST-BUILD.md`。

**真机仍待用户验证**：普通详情页与横屏全屏下 UI 出现且遮挡时字幕自然上移、不遮挡时不动；UI 隐藏后回原位置；暂停不因本功能翻页；拖动/旋转/seek/换视频无跳跃或残留；Shorts 原样。**离线不做真机结论**；下一阶段仍为语言档案/计数/方向及接入（本卡未执行、未自派），最终全面本地化/功能说明/诊断英文审计修复仍为必须步骤。

## 4n. N27 真机启动失败、根因与 N27r 修复定案（2026-10-01）

**用户最新要求**：取消换对话，继续由本聊天负责研究/判断/任务卡/审阅，DeepSeek Harness只执行。所有必要状态与资料索引需落本地，确保上下文压缩后仍可恢复，不依赖旧对话全文或旧交接快照。

**提交边界核对**：E:\Projects\morphe-caption-v2 HEAD28229e0；源码62c4916，anchor/n27-62c4916指向它；其后只有docs。N27执行者报告Java464/464（59套件）、Python27/27、组合84/84、DEX类58034及三件套见§4m/交付记录，这些是其有限离线检查结果，不能等同ART/真机启动通过。用户已安装，确认“点开应用立即闪退”。此前N27在途快照已过时。

**已证实根因**：用户连接手机授权读取日志。只读adb crash buffer中7个此包进程（18:47:59–18:52:03）均为`java.lang.VerifyError: Verifier rejected class bfec: void bfec.<init>(bfed): [0x10] target dex pc 0xd is not at instruction start.`。已装包21.16.256/minSdk28/targetSdk36，手机Android17/API37。对本地最终N27交付APK独立读取codeUnits/PC/signed offset亦完全相同：PC0x10 `if-eqz` relative=-3→0x0d；官方invoke在0x0b、宽3，故落入其操作数字。正确目标应是实际RETURN_VOID（当前坏包0x1b，修复不能硬编码）。不是API/语言/几何问题，类验证发生在方法运行之前，即使holder非空也会拒绝。

**机制与漏检**：N27的CaptionControlsAvoidancePatch.kt以普通addInstructions插入含分支及末尾裸标签片段，最终序列化标签重定位失效。旧N27ControlsHookCheck用原始APK+独立assemble+ArrayList拼接，主要做寄存器类型模拟，未校验真实patcher注入后的最终分支地址；旧dump无PC/offset，Java/Robolectric渲染回归不执行宿主构造器DEX。不得因历史PASS忽略真机失败，不改写历史冻结结果。

**N27r方案已定**：只把本卡注入改为addInstructionsWithLabels，ExternalLabel绑定插入前捕获的真实return指令，保留owner重载、空值保护、enum callback与官方hook；不得数值修补/裸标签/nop碰运气/删除避让/try-catch掩盖。补常规管线中的最终APK全根DEX分支边界与本卡目标审计；生产标签API注入→序列化→重新读回的回归覆盖官方先后与无官方hook。新增审计必须拒绝保留的旧N27坏包，报告0x10→0x0d；新包target必须是正确return。

**本期卡与证据**：`docs/N27R-CRASH-REPAIR-TASK.md`（完整执行卡），`docs/N27-CRASH-REVIEW.md`（根因/漏检/边界）。证据目录 `C:\Users\14776\.codex\visualizations\2026\10\01\01a0f56a-7d8c-75e0-8def-3fa2f0b542ef\n27-crash-review`：`n27-crash-androidruntime.txt`仅此包PID日志、`n27-final-constructor-pc.txt`直接从最终APK读出的PC/宽度/relative/target、`patcher-instruction-extensions-javap.txt`确认现有标签API。原始buffer含其他应用记录，仅本地留存，不作为分享材料。只读DEX分析工具在同目录，不是产品改动。

**当前状态与交付约束**：N27标记“已交付，真机启动失败，未验收”，优先N27r；不推进语言功能。**（本节为N27r开工前的定案记录；N27r已于同日执行完毕，结果见§4o。）** N27r尚未执行，没有修复产物；须保留本轮管理文件改动随修复卡提交。新产物用-n27r独立路径/新源码锚点，旧N27/N26及其他历史产物不覆盖；不要amend、推送、签名或发布。静态新审计通过不宣称真机修复完成，用户装机复验正常启动及避让后才闭环。未授权安装/清数据/卸载，规划者本轮也未做这些。

**后续顺序**：N27r恢复启动＋N27普通播放器避让复验 → 十四语种档案/计数/方向/源目标路由与接入 → 菜单十四语种多选/简中root合并 → 必须执行的最终入口功能说明/全部运行时本地化/程序性诊断英文审计修复。三项延期不趁此卡修改；九项定案不再询问。

**研究恢复索引**：`C:\Users\14776\.codex\visualizations\2026\10\01\01a0f56a-7d8c-75e0-8def-3fa2f0b542ef\future-plan-research\N25-next-feature-study.md`与`language-profile-draft.json`，逐语种官方指南和开源实现均在该目录。旧pending/unapproved及2A自然放置已被§4i决定覆盖；英文普通成人参考20CPS（旧§7d英文17不可套），中文保持现行，新语种速率软、几何/两行硬。N24质量审阅与诊断拆解在同根`caption-n24-review`目录，真实诊断/SRT路径见根因记录与该审阅。所有细节草案尚未上线，不把接口资源覆盖当运行时或语义质量通过。

## 4o. N27r 执行结果（2026-10-01，用户卡：修复启动VerifyError ＋ 补齐最终DEX分支审计）

**A 最小生产修复**：`patches/src/main/kotlin/app/yydarlinker/patches/deepseekcaptions/CaptionControlsAvoidancePatch.kt` 由普通 `addInstructions`＋片段末尾裸标签，改为 `addInstructionsWithLabels` ＋ `ExternalLabel("yydarlinker_caption_controls_return", originalReturn)`；`originalReturn` 是插入前捕获的构造器末尾真实 `RETURN_VOID` 的 `BuilderInstruction` 对象，不计算、不写死 relative/PC。交付 APK 实测同一条 `Lbfec;-><init>(Lbfed;)V`：PC0x10 的 `if-eqz` 由 `signed_offset=-3 → target_pc=0x0d`（落在 0x0b、宽 3 的官方 `invoke-static` 操作数字内部，即 ART 报的 `[0x10] target dex pc 0xd is not at instruction start`）变为 **`signed_offset=+11 → target_pc=0x1b return-void`**（`target_is_method_last=true same_method=true`）。官方 hook 与本卡观察者仍并存且顺序未变，从 owner 重载 holder／空值保护／enum 回调／指纹与类型约束全部保留。**未采用**数值偏移、盲加 nop、删除空值判断、关闭避让、host/hook catch-all、回退 N26；未改 API/分页/语言策略。MPE 与 N27 逐字节相同（本卡未触碰扩展源码）。

**B 最终 DEX 审计（进入常规管线）**：新增 `patches/src/test/kotlin/validation/`：`DexBranchAudit.kt`（容器遍历 APK／MPE／MPP／裸 DEX＋嵌套、逐方法 16 位 code unit 地址映射、goto/if 目标、`packed-switch`/`sparse-switch` 的 payload 引用/种类/对齐与 case 目标「相对 switch 指令」、`fill-array-data` payload、try/catch 范围、并用自写解析器读裸 `code_item.insns_size` 与 `Σ codeUnits` 双向核对；方法漏读／解析失败／code size 不一致一律 FAIL；内置变异自检每次运行都必须拒绝「落入操作数字」与「越界」两种改写）、`ControlsHookBindingAudit.kt`（按真实方法引用＋读数指纹形状识别实体模型，检查 null guard 目标恰为非空路径落入的同一 `RETURN_VOID`、路径顺序、invoke 与 move-result 相邻、寄存器界限/word 数/类型、本卡 callback 恰 1 次、官方 hook ≤1 次，并打印真实混淆名与整张 PC 表）、`FinalDexBranchAudit.kt`（CLI 入口，非 0 退出）、`InjectionOrderRegression.kt`（真 patcher 注入→序列化→从磁盘重读）。Gradle 新增 `:patches:auditFinalDex`、`:patches:verifyInjectionOrder`；**同时接入既有组合/交付入口** `CompositionDexAudit`（`:patches:auditComposition`），在旧判据之前调用同一 validator，失败即构建非 0。旧 `TYPE_SAFE_SUMMARY` 类型模拟与其产物保留，不再充当分支/最终产物证明。审计如实输出实际读到的 DEX 数，不写死 11。

**C 证据**：① 旧 N27 真实坏包被新 validator 直接拒绝——`FAIL_BRANCH … class=Lbfec; method=<init>(Lbfed;)V source_pc=0x10 signed_offset=-3 target_pc=0xd target_valid=false reason=target_inside_instruction containing_pc=0xb containing_width=3 containing_opcode=invoke-static` ＋ `FAIL_BINDING guard_target_not_instruction_start target_pc=0xd`，非 0 退出；同一坏包送进既有组合入口同样 `IllegalStateException: Final DEX branch/controls-hook audit failed` → `BUILD FAILED`（坏包是预期失败样本，未被包装成 PASS）。② 新交付 APK `DEX_BRANCH_AUDIT_PASS label=n27r-final dex_units=11 methods=322002 branch_edges=624712 switch_cases=112402 try_blocks=46579 invalid_branches=0`，随后既有 `DEX_AUDIT_PASS classes=58034`。③ 真 patcher 注入回归 `official-first`／`ai-first`／`no-ai` 三组，序列化后重读全部 `signed_offset=11 target_pc=0x1b return-void`；两种顺序在本 bundle 下被 patcher 归一成同一布局，**如实报告未产生不同宽度前缀**。④ 同一 validator 亦通过交付 MPE（1 unit／12,600 方法）、交付 MPP（内嵌扩展＋patcher `classes.dex`，2 units）与未补丁原版 APK（7 units）。**边界（三次真实尝试）**：官方 1.44.0 下无法构造「官方 hook 未被选中」的组合——只选 AI root 会因缺 `FlyoutUtils` 失败；去掉 `Hide player overlay buttons`（83 补丁）后 hook 仍在，因为 `GmsCore support` 声明依赖它；再去掉 `GmsCore support`（82 补丁）hook 仍可经共享内部依赖到达；只留 `Hide player flyout menu components` 则缺 `morphe_settings_screen_12_video_sort_by_key`。依赖表由 patcher 自身 `Patch.getDependencies()` 读出留档（`build/n27r-records/official-patch-list.txt`），注入方由官方 bundle 内携带该 smali 串的类定位（`PlayerControlsOverlayVisibilityPatchKt`），未猜测；validator 仍报告 `official_hook_count` 并在 >1 时失败，未因无法构造该组合而放宽判据。

**验证**：Java **464/464**（0 失败／0 错误／0 跳过，59 套件，与 N27 基线一致；本卡未新增 JUnit，新增回归以仓库既有 `validation` 入口方式落地）；Python **27/27**；localization **220 keys × 14 语种**；冻结计分板 **4 通过 / 4 既有失败 / 4 未验证** 且三类不可见时长全 0，`ACCEPTANCE.md` 与 `frozen-baseline.json` Git 无差异；组合 **84/84 PASS**（`onPlayerControlsVisibility=1`）；`verify_bundle.py 1.3.5` 与 `N8Verify`（74 条目／14 语种／CRC／根 DEX／内嵌扩展逐字节一致／交付 patch dex 含新 `ExternalLabel` 名且不再含旧裸标签）通过；`aapt` 确认 `app.morphe.android.youtube` 21.16.256／minSdk 28／targetSdk 36；`apksigner` 报 `DOES NOT VERIFY`（未签名）；**43 个历史产物 SHA-256 前后全一致**（含 N27 坏包本身，未被覆盖）。

**交付**：`build/local-test/patches-1.3.5-本地测试包-n27r.mpp`（1,117,960 B，+179，`5428E17A…`）、`extension-1.3.5-本地测试包-n27r.mpe`（2,727,272 B，+0，`45F2C9B6…`，与 N27 逐字节相同）、`build/n27r-composition-final/YouTube-21.16.256-本地测试包-n27r-unsigned.apk`（196,943,036 B，−4，`31071874…`；未签名确认）；交付 APK 由**交付 MPP 本身**组合、组合后从磁盘重读做新增审计。详见 `docs/N27R-LOCAL-TEST-BUILD.md`。

**真机未覆盖**：**最终 DEX 检查通过，启动修复待用户装机验证**；本卡未签名、未安装、未启动、未采新日志，不写「实测不再闪退」。用户复验顺序：先启动／主页／设置，再普通详情页与横屏全屏显示隐藏控件，然后暂停／旋转／seek／换视频／拖字幕。三项延期语言/文案问题与最终全面本地化/功能说明/诊断英文审计仍为必须步骤，本卡未提前修改。

## 5. 已确认的关键决策

- **字号五档制（N19）**：见文首决策块。旧的单一 r（203bps）与连续滑块范围作废。
- **分页风格（N15r）**：偏好 12–18 字/2–3.5s、硬上限两行/CPS≤8、硬下限≥1.2s、刀口只在语义接缝、页数上限由窗长推导；全片对比改后中位 16 字/3.468s、一行 86.3%、12–18 字页 41.6%、零硬违规。
- **设置预览（N17d）**：单幅满宽 16:9 横屏，字高按 N19 的全屏比例渲染。
- **review risk=0 ≠ 语义正确**（N13b 实证）——真机目检不可省略。
- **成本**：第三轮真机 9,709 tok/观看分钟；全片捕获一次性 32 万 tok（N15r 授权）。

## 6. 待办队列

**当前最高优先级：用户装机复验 N27r 启动与避让（`-n27r` 三件套已交付，见§4o）；以下旧阶段安排不得绕过此阻断。**修复卡`docs/N27R-CRASH-REPAIR-TASK.md`已执行完毕，交付记录`docs/N27R-LOCAL-TEST-BUILD.md`；本卡未做真机。

- ~~N17a 修复卡~~ ✅ `d40cfa3`（孤字页 ≥8 汉字格硬门槛）。
- ~~N17b 字号架构卡~~ ✅ `640905c`（后被用户推翻）。
- ~~N17c v2 字号重标定~~ ✅ `2ddfa14`（已被 N19 覆盖）。
- ~~N17d 预览简化卡~~ ✅ `00f53c8` 已审阅通过（设置页样式预览只保留满宽 16:9 横屏；14 语种文案同步；Java 388/388、Python 27/27、API 0）。
- ~~N18r 重建包卡~~ ✅ `581112b` 已审阅通过（YouTube 21.16.256 + 官方 1.44.0 + Patcher 1.14.1；84/84 PASS；三产物 mpp 1,051,347 / mpe 2,681,016 / apk 196,792,087 字节；未签名确认；API 0）。**该包不含 N19 改动。**
- ~~N19 字号五档卡~~ ✅ `10e9c85` 已审阅通过（五档 34/39/44.5/50/56、默认标准 44.5、全屏 ×1.247、预览按全屏比例、旧值迁移；Java 394/394、Python 27/27、14 语种 ×129 keys、API 0）。
- ~~N20 字幕文案与档位可视化卡~~ ✅ `c124fb5` 已审阅通过：A 部分等待类显示“翻译中…”、失败与拦截留空、`[原文 / Original]` 与 `caption_overflow` 全链路移除（主源码与 14 语种资源零残留）、源/配置类提示保留、诊断字段逐项保留（空白仍打点且去重）；B 部分滑轨 5 刻度 + 档名行，审阅者像素实测刻度中心等距 72–73px、档名中心偏差 ≤1.5px、当前档高亮、档名行无 px。Java 402/402、Python 27/27、14 语种 ×129 键；被删断言 11 条全属原文兜底类，新增断言 90 条；A10 整行与历史证据列未动。
- **审阅发现（N20，三项均非阻塞）**：**F1（建议修）** ACCEPTANCE 的 A01/A13“可自动检测的判据”列在替换时削薄了证据纪律——A01 丢了“不得用预装全部响应制造‘启动通过’”与冻结值 `event_end − first_caption_time`（3.845 秒）引用，A13 丢了“锁定源词归属、状态抢跑和 0ms 状态独占的离线策略镜像；真机待验”；**F2（可顺手修）** 空白/等待态持续时预绘制监听每 ~100ms 走一次完整 render（identity 非空且 anchor 为 GONE 使早退不成立），可见行为不变、仅属无谓文本测量；**F3（记录）** `render()` 中 `String source` 成死变量、`fallback` 恒为空，且“仅原文”模式不再带 `[原文 / Original]` 前缀（模式本身保留，测试已同步）。
- **审阅补充（N20 后专项核查，用户提问：暂停是否翻页 / 无原字幕段是否残留）**：**暂停**——时钟 `RebuildClock.position()` 仅在 `STATE_PLAYING` 按速度外推，`STATE_PAUSED` 直接返回上报位置并清掉累积外推，80ms tick 重算分页索引，故暂停期间**不会因时间推进而翻页**；位置越过末页 `end` 后 `RebuildPageLayout.indexAt` 返回 -1 → 空白并隐藏视图。两个例外（均非时间翻页）：① 暂停期间当前区块的译文/修复响应到达会重建分页，屏上文字随之变化（正在等待占位时属期望行为）；② 恰好停在页边界且播放器上报位置有 ±1 帧抖动时（暂停分支无死区）理论上可能来回跳一次。**空档**——静音 ≥650ms 是硬边界：`RebuildPlanner.boundary()` 用它切块，`RebuildProtocol` 对事件做结构硬拒（`crosses_source_break`，且属 `RebuildReview` 不可放行类），故跨静音事件上不了屏；位置落在无 cue/无事件的空档时控制器每 tick 重算得到空串 → `CaptionOverlay.hide()`，N20 后空串统一 `hideView()`（视图 GONE）→ **不残留上一句、不留空字幕框**，最坏 ~80ms 残留（tick 周期）。边界说明：<650ms 短停顿视为连续语音（有意），词级时间全为 `ESTIMATED`（cue 内均匀分配），边界精度受 cue 时间精度限制。
- ~~N21 缓存命中调查卡~~ ✅ `c5f3f57` 已审阅通过（审阅者独立重跑：50 套件 / 405 测试 / 0 失败 / 0 错误 / 0 跳过；`.verification/n21/cache-lookup-output.txt` 13 次 `N21_LOOKUP` 全部 `miss_reason=file_not_present`、namespace 无漂移、且自标 `key_scope=partial_source_and_fixture_config_not_device`）。**裁决 (b) 设计内行为**：块 key = 协议版本 | 配置 fingerprint(baseUrl+model+prompt) | 目标语言 | 生效 prompt SHA | **整片全部源词**（文本/起止/精度），文件名另含块序号与词区间；不含 API key、视频 ID、session/generation；零风险计划在 `ACCEPTED` 前同步落盘（临时文件 + fsync + ATOMIC_MOVE），风险非零不写盘；上限 256 块/64MiB 按写入时间淘汰、**无 TTL**；源缓存为独立子目录。12 次记账 = 启动 1 次查 2 块 + 后续 11 次各查 1 块，13 missed units 与逐块文件读取一一对应；三次修复不再查盘。**同 session 拖回复看走内存复用、不查盘，故 `hit blocks` 本可恒为 0；`current block hits` 两处调用点均传 `false`，结构上恒为 0，不得用作判据。** 无需修复、无需清缓存。
- **第三轮真机清单第 4 项程序修正（依 N21 证据，判据 `hit blocks > 0` 不变）**：改为“**新会话**复看”——同包先看一段至 `REBUILD_EVENTS_ACCEPTED;review_risks=0` 且无 `REBUILD_CACHE_WRITE_FAILED`，**保留 app 数据与缓存**，重启 app 或退出重进同一视频（须见新的 engine session/启动链），保持模型/base URL/prompt/目标语言与源轨不变；首两块看 `REBUILD_SOURCE_READY.cache_hits>0`，懒读块看 `REBUILD_CACHE_RESTORED;block=…;network_calls=0`，汇总 `hit blocks` 必须 >0。同 session 拖回仅算“未执行盘命中验证”，不得据此翻绿；不得用 current block hits、提供商 cached tokens 或仅 `REBUILD_REQUEST` 判定命中。
- ~~N21b 微修卡~~ ✅ `66c1e00` 已审阅通过（审阅者独立重跑：50 套件 / **413 测试** / 0 失败 / 0 错误 / 0 跳过）。五项逐条核对：① A01 判据补回请求时序、防预装“启动通过”与冻结值 3.845 秒引用，A13 补回源词归属/状态抢跑/0ms 独占镜像条款，并追加 N21b 签字记录；② 死计数器 `unit_cache_current_hits` 已删（形参、两处 add、摘要片段一并移除），摘要行现为 `Request-block disk cache: lookups 12 · hit blocks 0 · missed units 13`，取证测试改为断言字段不存在；③ 空白态早退用 `lastBlankIdentity` 实现，同一 identity 且视图 GONE 时跳过文本测量，新空白 cue 仍各自渲染与打点；④ `render()` 死变量 `source` 与恒空 `fallback` 清理，`showEvent` 形参语义保留；⑤ 暂停期位置粘滞落地——`pausedDisplayPosition`/`pausedHookState` 随 session、cancel 清空，`displayPosition()` 只影响显示与分页（`s.position` 调度时钟不变，诊断 `time=` 记原始观测值），非暂停态一律清冻，显式 hook 优先于抖动，1500ms 严格安全阀，并已按契约补齐四条取证测试（页面边界抖动 16ms 的真实回归夹具、恢复推进、暂停 seek 立即生效并递增 generation、换 session 清冻），另覆盖全部非暂停态与缺状态清冻。
- **遗留文档小瑕疵（F4，不影响判据与产物，随时可顺手修）**：ACCEPTANCE.md 的 A14 行仍写“N9 原文兜底已有 Java 单测与离线镜像”，而 N20 起该窗已改为留空；建议措辞改为“该归属窗留空已有 Java 单测与离线镜像”。**不改判据语义，故不单独开卡**，与后续任一改文档的卡一起处理即可。
- **N23 合并卡**：✅ `45a830a` 已完成（滑条一致性 + C 原生黑块修复 + D 频跳调度），**B 段两条护栏修正已被 N24 按用户决定撤销**；锚点 `anchor/n23-45a830a` 保留（不推送）。
- **N24 执行卡**：✅ `9049591` 已提交并打标签 `anchor/n24-9049591`（不推送）。四段全部完成，详见 §4g 与 `docs/N24-LOCAL-TEST-BUILD.md`。**质量行为已回到 N22**（`RebuildReview` 与 `a482262` 逐字节一致），N23 的 C/D 工程改进保留且 D 段升级为有界调度（前台 2 路 / 后台 2 路 / 总 4 路 / 最新待办替换 / 两路预取生效）。测试数 421→429。产物 `-n24` 三件套与 84/84、`DEX_AUDIT_PASS classes=58028` 见 §4g。
- **N25 执行卡**：✅ `99e7be5` 已提交并打标签 `anchor/n25-99e7be5`（不推送）。四段全部完成，详见 §4h 与 `docs/N25-LOCAL-TEST-BUILD.md`。测试数 429→**437**，本地化 129→**220 keys × 14 语种**，产物 `-n25` 三件套与 84/84、`DEX_AUDIT_PASS classes=58028` 见 §4h。**未改**：翻译提示词、`RebuildReview`、语义分块、协议校验、重试与分页算法、前台2／后台2／总4 调度、源词时间归属、字号五档与 55.5/44.5 缩放、缓存键、用户自填配置；ACCEPTANCE.md、冻结计分板与历史诊断证据逐字节未改。
- **N27r 修复卡**：✅ 已执行完毕并交付 `-n27r` 三件套（**未验收，待用户装机复验**），详见 §4o 与 `docs/N27R-LOCAL-TEST-BUILD.md`。生产注入改为 `ExternalLabel` 绑定真实 `return-void`（`signed_offset` −3→+11、target 0x0d→0x1b），新增仓库内最终 DEX 分支审计并接入既有 `:patches:auditComposition`；旧 N27 坏包被直接拒绝。测试数 **464/59 未变**（新增回归以 `patches/src/test/kotlin/validation` 入口落地，非 JUnit）；MPE 与 N27 逐字节相同。**未改**：N27 协调器 Java、Overlay、HookV2 显示算法、8dp/100ms/200ms、五档字号、分页/正文/时间归属、原生与 AI ownership、缓存、调度、API、语言策略及全部用户持久化值；ACCEPTANCE.md 与冻结证据逐字节未改。**四阶段顺序不变**，下一阶段仍是语言档案/计数/方向及接入，本卡不执行、不自派。
- **N27 执行卡**：⚠ 已交付但用户真机启动闪退、未验收（见§4n/§4o）；已提交并打标签 `anchor/n27-62c4916`（不推送，指向含全部源码与最终文案的提交；本条状态补记与文档收尾为其后的独立提交，源锚点与 HEAD 的关系见下）。五段（可见性输入与运行时接入 ＋ 已确认控件与坐标 ＋ 避让算法与动画 ＋ 渲染/拖动/生命周期整合 ＋ 英文诊断与离线验证）全部完成，详见 §4m 与 `docs/N27-LOCAL-TEST-BUILD.md`。测试数 440→**464**（套件 58→59，新增 `N27ControlsAvoidanceTest` 24 条），产物 `-n27` 三件套与 84/84、`DEX_AUDIT_PASS classes=58034` 见 §4m。**未改**：正文、页索引与时间归属、字体大小/样式、API 协议/调度/重试、原生与 AI 轨道 ownership、缓存、菜单、语言策略、设置入口及全部用户持久化值；翻译提示词、`RebuildReview`、语义分块、协议校验、分页算法、`LayoutBudget`；ACCEPTANCE.md、冻结计分板与历史诊断证据逐字节未改。**四阶段顺序不变**，下一阶段是语言档案/计数/方向及接入，本卡不执行、不自派。**源锚点 `anchor/n27-62c4916` 指向源码与最终文案提交；其后仅有把本行占位短哈希写实的状态补记提交，不含任何源码或文案改动。**
- **N26 执行卡**：✅ `509d50a` 已提交并打标签 `anchor/n26-509d50a`（不推送，指向含全部源码与最终文案的提交；本条状态补记与文档收尾为其后的独立提交，源锚点与 HEAD 的关系见下）。三段（入口迁入视频页 ＋ 入口样式与同页普通项一致 ＋ 预览说明改全屏）全部完成，详见 §4j 与 `docs/N26-LOCAL-TEST-BUILD.md`。测试数 437→**440**（套件 56→58），产物 `-n26` 三件套与 84/84、`DEX_AUDIT_PASS classes=58028` 见 §4j。**未改**：播放器与避让、翻译提示词、`RebuildReview`、语义分块、协议校验、重试与分页算法、调度、源词时间归属、字号与几何、缓存键、用户配置存储、诊断 raw 格式、`preview_sample` 与预览交互；ACCEPTANCE.md、冻结计分板与历史诊断证据逐字节未改。**四阶段顺序不变**，下一阶段是常规详情页／横屏全屏的播放器控件避让，本卡未启动。**源锚点 `anchor/n26-509d50a` 指向源码与最终文案提交；其后仅有把本行占位短哈希写实的状态补记提交，不含任何源码或文案改动。**
- **N24 真机验收清单（用户本人执行；发现问题只记录不现场改）**：
  1. **滑条两端**：拉到超小/超大档，确认两端刻度点落在可见轨道两端，thumb 中心压在刻度上，档名与刻度对齐；两条滑条等长、同左右边距、同取色。亮/暗主题、RTL 布局各看一次。
  2. **全屏预览比例**：设置页预览标题应为「横屏全屏预览」；字幕占画面比例应与真机全屏一致；切五档与拖动透明度时即时更新、示例句不被截断。
  3. **字号文案**：标题为「字号」（无「（详情页）」）；字号滑条标题行右侧无 px 数值；下方档位说明仍显示「详情页 xx px · 全屏 xx px」；**透明度条右上角百分比保留**。
  4. **原生黑块**：频跳与等待期观察是否仍出现无文字原生底框（N23 C 段效果）。
  5. **等待表现对比（D 段核心）**：未缓存落点记录 `REBUILD_WAIT_BREAKDOWN` 的 `slot_wait_ms` 与 `network_ms`（重点：旧前台阻塞时新落点是否不再排在其后，即 `slot_wait_ms` 是否不再是秒级）；停止频跳后到第一条译文呈现的时间；缓存命中时 `REBUILD_CACHE_RESTORED` 的恢复时间；请求数（`REBUILD_REQUEST` 计数）与 token 消耗。**离线不做延迟改善承诺，以上为真机待验证项。**
  6. 缓存命中（**新会话**复看，程序见 §6 N21 行）：首两块 `cache_hits>0`、懒读块 `REBUILD_CACHE_RESTORED;network_calls=0`，汇总 `hit blocks` 必须 >0。
  7. 既有第三轮补测项（字号五档逐档、A15 845s 后、A16 84.1s 抽查、视频比例不变性、评论区边界、85.jpg 疑点）继续按 §6 清单执行。
- **L 线（自动翻译语言菜单多选）**：**等 N25（或 N24）真机验证通过后，从新锚点 `anchor/n25-*` 开分支 `feature/lang-menu` 开发**（原计划的 `anchor/n23-*`、`anchor/n24-*` 起点依次作废）。改由新开的 DeepSeek Harness 对话承担规划与审阅；覆盖范围=本 patch 的 14 个 UI 语种；**不承诺任何语种的译文质量**（用户 2026-09-30 决定），只承诺版式合规与平台可用。详见 §7b/§7c/§7d。
- **N22 重建包卡**：✅ `a482262`（三产物 n22：mpp 1,054,972 / mpe 2,687,056 / apk 196,797,207 字节；锚点标签同卡创建）——已由用户在 2026-09-30 真机验证（见 §4d）。
- **第三轮真机补测清单（用户本人，用 N22 新包执行；发现问题只记录不现场改）**：
  1. 字号五档逐档核对：详情页 34/39/44.5/50/56px、全屏 42.4/48.6/55.5/62.4/69.8px（全屏=详情页×1.247）；默认档应为标准 44.5/55.5。量法：原分辨率截图、单字窗口、只量实心字（不算黑框/阴影/描边）。
  2. 预览一致性：设置页预览中字幕占画面比例应与真机全屏一致，切档即时变化。
  3. 文案行为：暂停/启动/拖动进度条时**不得**再出现“字幕过长，原文暂不可用”或 `[原文 / Original]`；等待期只显示约定占位。
  4. 缓存命中（程序见 §6 N21 行）：**新会话**复看，首两块 `cache_hits>0`、懒读块 `REBUILD_CACHE_RESTORED;network_calls=0`，汇总 `hit blocks` 必须 >0。
  5. 孤字页复查：84.1s、125.6s 两处应无 <8 格页 —— **125.6s 已复验通过（§4d），84.1s 段落本轮未见碎片页**；A06/A10 位置（约 183s 起）排版；大字档（大/超大）分页是否退化。
  6. A15 补看：845s 后 b109 块行为（本轮只看到 616s，**未覆盖**）；A16 位置（84.1s）译文抽查（本轮方向正确）。
  7. 视频比例不变性：同方向切换不同宽高比视频，字高应不变。
  8. 评论区边界：收窄 >20% 缩放、关闭恢复；进入时已打开的现象记录（已知边界）。
  9. 控制层采集（为 T3 供料）：三形态点按露出/隐藏控制层，记录进度条遮挡与截图。
  10. 85.jpg 疑点定案：同一视频 36.9–39.9s 暂停同画面，分别关原生 CC / 隐藏插件叠层各截原分辨率图对照（程序见 `docs/N17c-FONT-RECALIBRATION.md` P6 节）。
- **T3 控制层上抬小卡**（第三轮控制层采集信号后补）：播放器界面露出时字幕上抬让进度条，隐藏回落，系统默认短动画。
- **发布流程**（第三轮真机通过后另议）：版本号/CHANGELOG/semantic-release；不得覆盖 v1.3.5 资产。

- **待用户拍板 ①（登记）**：两处译文问题是否登记进 ACCEPTANCE —— 327.0–331.2s 中心词漏译（建议登记为新案例）与 384.6–391.7s A14 主语错接复发（建议登记为 A14 证据升级）。根因见 §4d 第 9 条；不登记则仅保留观察记录。
- **待用户拍板 ②（新版式议题）**：§4d 第 1 条观察——166 正文页中 66 页 >4s、最长 9.71s，超 Netflix 单事件 7 秒上限；是否立"长事件按时间再分页"卡，由用户决定。
- **预期管理（已建议写入说明文案）**：块级翻译 + 远程模型的架构下，跳到未翻译位置必然要等一次模型往返（实测中位 2.7–4.4s）；N24 的 D 段已把**调度层**的排队（旧前台串行阻塞、无界线程池争抢、预取预算不生效）按 §4g 修掉，但只能把等待压到接近单次往返，做不到"跳过去立刻有译文"，也**不代表网络往返本身变快**。

## 7. 提交序列（morphe-caption-v2）

`c67d63b` 基线对齐 → `fb41189` 验收表+计分板 → `c198c21` N2 → `dc24b7e` N3 → `1ecb662` N4 → `a97bbae` N5 → `7ecc94c` N6 → `4d3e97f` N7 → `c3af18d` N7b记录 → `f360a12` N7b回滚 → `a31f778` N9 → `a1c0541` N10 → `932d24d` N11 → `f2c3aa9` N12 → `5b51c8b` N13 → `d67e8e1` N13b → `a063e47` N14 → `30deae4` N15 停止记录 → `cd97869` N15r → `33c288e` N16 建包 → `d40cfa3` N17a → `640905c` N17b（被推翻）→ `cc29229` N18 旧包（作废）→ `2ddfa14` N17c v2 → `00f53c8` N17d → `581112b` N18r 重建包 → `10e9c85` N19 五档字号 → `c124fb5` N20 字幕只显译文 + 字号档位可视化 → `c5f3f57` N21 块缓存 0 命中离线取证 → `66c1e00` N21b 判据补回 + 暂停粘滞 + 诊断清理 → `a482262` N22 重建包 n22 + 回滚锚点 → `45a830a` N23 滑条一致性 + 原生遮蔽 + 有界重建调度 → `9049591` N24 质量回退 N22 + 滑条端点统一 + 字号文案与全屏预览 + 有界调度（前台 2 路 + 最新待办替换 + 两路预取），锚点 `anchor/n24-9049591` → `8941244` + `c139144` + `99e7be5` N25 设置排版修正（预览去重复标题）+ 五档标签完整显示与统一 LTR/RTL 几何 + 预览示例资源键 + 十四语种完整 UI 本地化 + 表头分隔符修正与交付记录，锚点 `anchor/n25-99e7be5` → **`509d50a` N26 设置入口迁入 Morphe 视频页（新导航 key `morphe_vot_screen__ai_captions`，紧跟旁白翻译）+ 入口与同页普通项同样式 + 预览说明改「样式预览（全屏）」十四语种**，锚点 `anchor/n26-509d50a`（指向源码与最终文案提交）→ **N27 普通播放器控件避让**（本 patch 自建可见性指纹并沿官方同一实体模型构造器路径注入自有观察者，字幕与真实可见操作控件相交时最小上移、隐藏回原基准，Shorts/小窗/PiP 排除，新增诊断事件全英文），锚点 `anchor/n27-62c4916`（指向含全部源码与最终文案的提交；短哈希由本卡提交后不含源码改动的状态补记提交写实）→ **N27r 启动 VerifyError 修复 ＋ 最终 DEX 分支审计**（本卡注入改用 `ExternalLabel` 绑定构造器真实 `return-void`，空值分支 offset −3→+11、target 0x0d→0x1b；新增仓库内 `FinalDexBranchAudit`／`DexBranchAudit`／`ControlsHookBindingAudit`／`InjectionOrderRegression` 并接入既有 `:patches:auditComposition`，旧 N27 坏包被直接拒绝），锚点 `anchor/n27r-<实现短哈希>`。

## 7b. 后续功能线：自动翻译语言菜单多选（L 线，交接给新 DSH 对话）

- **目标**：把现有第二个补丁 `simplifiedCaptionLanguagePatch`（"Add Simplified Chinese to auto-translate"，单语言、default=false）升级为**用户自选多语言**加入 YouTube 自动翻译语言菜单，并**合并进 "AI caption translator" 补丁**。
- **既有轮子检索（2026-09-30，审阅者）**：本机官方 `patches-1.44.0.mpp` 全量字符串扫描显示，官方与字幕相关的补丁只有 `captions`、`autoCaptionsPatch`（禁止自动开启字幕）、`captionCookiesPatch`（Timed Text API 请求带 cookies）、`transcriptPatch`，以及 `voiceOverTranslationPatch`（"Voice over translation"，语音配音翻译，另一功能）；**没有任何补丁改动自动翻译语言列表**。GitHub 在本机 web_fetch 被策略拦截（解析到非公网 IP），故为"官方包权威扫描 + 搜索引擎（含 anddea/revanced-patches、4pda Morphe 帖）指示性结果"；**开卡第一步仍需 Codex 联网复核**一次。
- **本仓机制现状（可直接泛化）**：`NativeCaptionBridge.augmentTranslations()` 已实现"找 timedtext 原型条目 → 克隆 → 改写 code/标签/URL → 按 Collator 顺序插入 → 已存在则修正而非重复"；`LanguageMenuOrder` 用 UI 语言环境的 Collator 对 NFKC 归一化后的显示名排序（即"遵照 YouTube 在各语言下的排序"）；`CaptionLanguageMetadata` 写入条目字节字段（目前硬编码 `zh-Hans`）；`simplifiedUrl/simplifiedVss` 负责 `tlang`/`t<code>` 改写。**用户四项要求里，去重、排序、插入已有实现，缺的是"多选集合 + 每个 code 的元数据/URL 改写 + 设置项 UI"。**
- **用户已拍板（2026-09-30）**：① 覆盖范围 = **本 patch 支持的 14 个 UI 语种**（不用 YouTube 原生全集；理由：后续要按语种适配翻译策略，收窄范围才能保证体验）；② 多选 UI 落点 = 设置页新增一项（候选列表 + 已存在标记 + 不支持标记）；③ **不承诺任何语种的译文质量**——用户明确表示不会核查其他语种效果，“尽力适配即可”；因此 §7c 的标定成本不由用户承担，改为“标准值先行 + 客观版式指标 + 运行时验证”（见 §7d）。
- **建议卡序列**：L1 调查/设计卡（联网复核现成轮子 + 定位承载语言列表的类与挂点 + 候选来源与 tlang 编码规则 + 多选 UI 落点 + 最小实现方案与风险，只查不改）→ L2 实现卡（多选/去重/排序/14 语种文案/测试/fixture）→ L3 建包 + 真机验证。
- **回退锚点**：`66c1e00`（N21b，语言菜单开发前的最后代码点）。建议在下一张卡里创建 `anchor/n21b-66c1e00` 标签；功能线在独立分支（如 `feature/lang-menu`）上开发，主线保持可回退。

## 7c. 翻译策略的语言对适配性（审阅者核查结论）

- **架构上语言对无关**：`RebuildApi.prompt(cfg,lang)` = 主 prompt + 保真 prompt + `Target language: <lang>` + 用户偏好；源语言不显式声明（由 `source_text` 推断）；`RebuildSource.tokens()` 是脚本感知的（Han/假名/谚文逐字切，拉丁按词，数字/百分号/复合词单独成 token）；排版由 Android `StaticLayout` 按真实字体测量；目标语言就是所选菜单条目的 code，全链路可换目标。
- **但阈值是为"英文源→中文目标"标定的**：`RebuildPageLayout` 的偏好 12–18 码点、`MAX_CPS=8`、`8L*|len−15|` 计分、12/18 惩罚项、`MIN_PAGE_MS=1200` 全部按**字符数**；拉丁/西里尔目标下同样字符数承载信息少得多，CPS=8 也偏紧，会明显多分页。`RebuildProtocol` 段落护栏已按 `cj ? 60 : 155` 自适应（部分自适应）。`RebuildReview`/`RebuildSemantics` 的语义护栏（主语错接、数字替换、锚点泄漏、比较极性）来自中英失败样本，换语言对最好情况是惰性、最坏情况是误报。
- **结论**：换语言对"能跑通但不等于体验成立"；每加一个目标语言，工程代价 = 重标定分页/可读性阈值 + 一轮该语言对的真机样本。这直接影响 §7b 的覆盖范围决策。

## 7d. 分语种参数依据：Netflix Timed Text Style Guide 实测数值（2026-09-30 检索，审阅者）

- **检索方法**：`web_fetch` 对 github.com / *.github.io 被策略拦（解析到保留段 198.18.0.111），改用 shell 的 `curl` 抓 Netflix Partner Help Center 正文 + `gh` 做社区检索。
- **通用要求**（[General Requirements](https://partnerhelp.netflixstudios.com/hc/en-us/articles/215758617-Timed-Text-Style-Guide-General-Requirements)）：最短 5/6 秒、最长 7 秒、**最多两行**、"不超过字符上限时保持一行"；断行规则=标点后断、连词/介词前断，不得拆开冠词与名词、形容词与名词、名与姓、动词与主语代词、动词与助动词/否定。每事件两行上限与我们的"每页 ≤2 行"是同一单位，可直接对齐。
- **每行字符上限**（[官方 QC 表](https://partnerhelp.netflixstudios.com/hc/en-us/articles/215274938-What-is-the-maximum-number-of-characters-per-line-allowed-in-Timed-Text-assets)）：多数语言建议 **42**；强制上限——韩 23、简中 23、繁中 23、阿 50（Originals 档：韩/简中/繁中 **16**、阿 42）。
- **阅读速度（正片 / 儿童 / SDH）**，取自各语言 TTSG：**简中·繁中 9 / 7 / 11**（每行 16 全角）；**日 4（重对话 7）/ — / 7**，每行 13 全角（SDH 16），半角与空格按 0.5 计；**韩 12 / 9 / 14**，每行 16（拉丁/空格/标点按 0.5 计）；**阿 20 / 17 / 23**；**印地 22 / 18 / 25**；**英·德·法·西·葡·俄·越·印尼 17 / 13 / 20**。
- **对现行常量的判定**：`MAX_CPS=8` 全局单一值**只对中日文成立**（中文 8 与官方 9 同档、日文官方仅 4 需下调），对拉丁/西里尔/阿拉伯/印地/越南/印尼**过严约一倍**（官方 17–22），会造成大量无谓分页；"12–18 码点"只适用于 CJK，拉丁语种应以**实测行宽**为预算；计数单位应改为**显示宽度单位**（全角=1、半角/空格/标点=0.5，韩日官方即此口径）或**字素簇**（印地/阿拉伯），而非 `codePointCount`。
- **质量承诺范围建议（待用户拍板）**：把"能把语言加进菜单"（14 种，平台能力）与"承诺译文质量"（仅中文简繁，可选加英文）分开；其余语种只承诺**版式合规与平台可用**，设置页文案如实标注，避免对无法验收的语言作质量承诺。

## 8. 项目由来（一句话）

旧研究线（morphe-ai-caption-translator-next）两天几百元、10 分支、以 413MB 本地模型方案 FAIL 收场；本线（v2）以“验收案例表 + 冻结计分板 + 任务卡”模式重启，是目前唯一有效的工程路线。教训：约束先行、任务切碎、验收权归人、每步可测。
