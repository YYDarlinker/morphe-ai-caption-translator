# N44 — 全语种显示保证：根因、架构与执行计划（2026-10-19）

- 输入：
  - 本轮诊断 `caption-diagnostics-1.5.4-20261019-215759.txt`（N43 已逐条分析）；
  - **历史语料** `D:\HONOR Share\Honor Share\caption-diagnostics-*.txt` 共 **32 份**（2026-10-15 至
    10-19，约 50 MB，152 个翻译会话，946 个被接受的块，177 次块级拒绝）；
  - 生产源码全量检索（`translation-core`、`caption-shared`、`application-host`、`ui`）；
  - 外部标准：Netflix Timed Text Style Guides（逐语种 CPS/CPL）、UAX #14/#29、CLDR/UTS #35。
- 方法：历史语料统计 + 生产类离线探针（32 例跨语种矩阵）+ 逐失败路径代码审计 + 外部标准对照。
- 本轮只读：没有改产品代码、没有重建 MPP、没有连接手机。
- 复现材料：`validation/runs/20261019-n44-language-coverage/`。
- 与 N43 的关系：N43 解释“为什么这一次坏了”；N44 回答“如何保证**任何语种、任何视频**都不再出现
  整段无译文”，即把结论提升为可执行的设计与验收方法。
- 实施状态（2026-10-19）：P1/P2 + P4-lite 已实施，见 docs/N44-REPAIR-20261019.md；P3/P5 与完整语言注册表未实施。

---

## 0. 结论摘要

1. **问题不是“某个语种的翻译坏了”，而是“一个失败点会吃掉整块、而且没有终点”。**
   请求块、校验块、显示块是同一个 ~20–30 s 的 block；任何**一个** event 的**任何**一项检查失败，
   整个 block 都不发布；失败状态停在 `WAITING` 并被无上限慢重试。观众看到的是整段
   “翻译中…”或空白。
2. **大部分“检查”其实是质量建议，却被当成硬门禁。**
   32 份诊断的 177 次拒绝里，`source_quote_mismatch` 111、`source_coverage` 21、
   `dependent_source_end` 11、`numeric_substitution` 11 —— **前四项占 87%**。最新一份（10-19 21:57）
   的 11 次拒绝里 `numeric_substitution` 占 7 次（64%）。这些都不是“译文不可用”，而是
   “身份判定过严”或“质量启发式误判”。
3. **语种适配目前是“已知 15 种 + 其余 GENERIC”，而 YouTube 的自动翻译目标约 140 种。**
   68 个候选 code 中 51 个落到 GENERIC（75%）；RTL 只登记 `ar`；韩语被当成无空格文字；
   裸 `zh` 反而丢掉中文指标（历史诊断里确实出现过 2 个 `zh` 目标）；`th` 有 profile 却不在
   设置菜单里。更严重的是：同一句忠实译文在跨语种矩阵里 **22/32 被拒绝**（designation 10/16、
   scale 5/8、sign 4/4、阿拉伯数字分隔符 1/1、Unicode 引文 2/2）。
4. **没有一个“覆盖不变量”**：系统从不检查“此刻有语音的时间段是否已经有可显示的计划”。
   没有计划就 `hide()`；READY 但时间落空、authored 契约拒绝、UI 投影失败也都是空白，
   而且**没有任何 watchdog 会重新请求或补页**。
5. **根本解法不是再加语种表或再调阈值，而是三件事：**
   - **把失败单元从 block 降到 segment**：部分发布 + 只重试缺口 + 有界降级；
   - **把校验分成 identity（硬）与 quality（软）**：只有“不可用/不属于这个源”才拒绝，
     数字、语义、句末、长度一律 advisory；
   - **把语言属性从“语言 id 特例”改成“脚本/ICU/CLDR 推导 + 运行时注册”**：
     任何 YouTube 给出的 code 都能得到一个可用 profile，永不因“没登记”而失败。
6. **可验收的定义**：一套“语种 × 文本形态 × 源格式 × 模型输出形态”的 conformance suite，
   对忠实译文要求 **0 误拒、100% 时间覆盖**；对对抗样本要求**按 segment 拒绝且不牵连邻段**；
   对故障注入要求**在截止时间内进入终态**（不出现无限“翻译中…”）。

---

## 1. “所有语种”到底有多大

| 维度 | 现在的假设 | 真实空间 |
|---|---|---|
| 目标语言 | 15 个具名 profile；菜单 14 个 code | YouTube `translationLanguages` 约 140 个 code（vendor 数据，运行时以播放器元数据为准）<https://docs.supadata.ai/youtube/supported-language-codes> |
| 文字 | 中文特例 + 少量脚本表 | Latin/Cyrillic/Greek/Arabic/Hebrew/Devanagari/Bengali/Tamil/Telugu/Kannada/Malayalam/Gujarati/Gurmukhi/Sinhala/Thai/Lao/Khmer/Myanmar/Han/Kana/Hangul/Georgian/Armenian/Ethiopic… |
| 数字/单位 | 小数、分数、万/亿、k/million/billion | 小数逗号、分组符、`억/億/万/亿`、lakh/crore、负号、单位换算（MP、km/mile、°C/°F）、序数、版本号 |
| 源格式 | JSON3/VTT/SRV3/SRT，人工/ASR | 逐词 / cue 级 / `<s t>` / 粗重建；时间精度从 native 到 estimated |
| 模型输出 | 完整 JSON、按句/按分句 | 截断、拒绝、错脚本、回声、丢词、ID 偏移、跨句合并、空文本 |
| 用户场景 | 已测视频 | 任何视频、任何说话人、任何字幕作者 |

测试视频不可能覆盖这个空间；**只能覆盖“类”**。N44 的矩阵就是这个“类”的起点。

---

## 2. 证据一：32 份真实诊断说明失败集中在哪里

统计脚本：`validation/runs/20261019-n44-language-coverage/README.md`（同一方法可复跑）。
原始输出：`.tmp/analysis/corpus.txt`（本轮临时证据，不随仓库交付）。

### 2.1 规模

| 指标 | 数值 |
|---|---:|
| 诊断文件 | 32 |
| 翻译会话（`LANGUAGE_PROFILE_BOUND`） | 152 |
| 被接受的块 | 946 |
| 被拒绝的块尝试 | 177（15.8%） |
| 拒绝 ≥2 次且**从未接受**的块 | **22** |
| HTTP 失败 | 55（其中 51 cancelled、4 content_filter） |

被尝试的目标语言：`zh-Hans` 133、`en` 11、`zh` 2、`ja/ru/vi/id/es/ko` 各 1。
**87.5% 的会话只测了简体中文**，9 个目标语言——这正是“我的测试覆盖不了所有场景”的量化版本。

### 2.2 拒绝原因

| 原因 | 次数 | 占比 | 性质 |
|---|---:|---:|---|
| `source_quote_mismatch` | 111 | 62.7% | 身份判定（引文逐字） |
| `source_coverage` | 21 | 11.9% | 覆盖判定（整块） |
| `dependent_source_end` | 11 | 6.2% | 质量启发式 |
| `numeric_substitution` | 11 | 6.2% | 数值启发式 |
| `empty_translation` | 6 | 3.4% | 空译文 |
| `event_shape` | 5 | 2.8% | 结构 |
| `authored_cue_boundary` | 4 | 2.3% | 人工 cue 契约 |
| `content_filter` | 4 | 2.3% | 供应商 |
| `json` / `missing_source` / `output_truncated` / `untranslated_source` | 3/2/1/1 | 4.0% | 结构/覆盖 |

前四项 = **154/177（87%）**。其中 `quote_mismatch` 的多数来自 N40/N41 之前的
“`join()` 在 CJK 标点后插空格”问题（已修），但最新一份诊断仍有 3 次；而
`numeric_substitution` 在最新一份里占 **7/11（64%）**，是**当前**的主要拒绝原因。

### 2.3 观众侧“没有文字”的帧

| `REBUILD_PRESENTED` 原因 | 帧数 |
|---|---:|
| `translation_failed` | 121 |
| `source_gap` | 243 |
| `pending_translation` | 370 |
| `pending_engine` | —（并入 pending） |

`translation_failed` 是块进入 FAILED 后**永久空白**的帧；`source_gap` 包含“计划就绪但时间落空”
与“没有块”；`pending_translation` 是“翻译中…”或空白。历史上 22 个从未恢复的块，每一个都
至少贡献一整段这样的帧。

### 2.4 结论

失败不是网络（55 次 HTTP 失败里 51 次是用户取消），而是**校验与重试架构**。只要不改
“整块 fail-closed + 无界重试”，每增加一个语种、每换一种字幕，就多一类新的误拒。

---

## 3. 证据二：32 例跨语种矩阵——忠实译文也会被拒绝

探针 `TmpLanguageCoverageProbeTest`，每行 = 同一句英文源文的**忠实**译文，走生产
`RebuildProtocol.parseBound`（完整块）与 `RebuildNumbers.compare`。

| 形态 | 语言 | 结果 | 数值判定 |
|---|---|---|---|
| designation `four T-14 tanks arrived` | ru / uk / el / ar / he / fa / hi / bn / th / ko | **REJECT `numeric_substitution`** | `[14]` vs `[-14]` |
| 同上 | ja / zh-Hans / zh-Hant / de / es / vi | ACCEPT | `[14]` vs `[14]` |
| scale `a 200 megapixel camera` | zh-Hans / zh-Hant / ja | **REJECT** | `[2E+2]` vs `[2E+8]` |
| 同上 | ko | **REJECT** | `[2E+2]` vs `[2]`（2억） |
| 同上 | hi | **REJECT** | `[2E+2]` vs `[2E+1]`（20 करोड़） |
| 同上 | id / es / ru | ACCEPT | `[2E+2]` vs `[2E+2]` |
| sign `fell 5 degrees below zero` → `-5` | en / zh-Hans / ru / de | **REJECT** | `[5]` vs `[-5]` |
| Arabic thousands `١٬٢٣٤`（=1234） | ar | **REJECT** | `[1234]` vs `[1.234]` |
| NFD 引文（café → cafe + combining accent） | fr | **REJECT `source_quote_mismatch`** | — |
| bidi 引文（希伯来语 + U+200F） | he | **REJECT `source_quote_mismatch`** | — |
| 长句 | de | ACCEPT | — |

**合计 22/32 拒绝（68.8%）**。这不是“模型翻得不好”：每一条都是该语言的正确写法；
拒绝来自校验器自己的词法/语义假设。

### 3.1 这些拒绝的共同结构

1. **数字 token 的左边界只排除 ASCII 字母**：`Т-14`（西里尔）、`Τ-14`（希腊）、`ت-14`（阿拉伯）、
   `ט-14`（希伯来）、`टी-14`（天城文）、`ที-14`（泰文）、`티-14`（韩文）都会把连字符读成负号；
   拉丁字母的 `T-14` 不会。**同一个型号，换一种文字写就失败。**
2. **量级词表是封闭的**：`万/亿/千/百/thousand/million/billion/k` 之外，`억`（韩）、`億`（日，已列）、
   `करोड़`（印地 crore）、`lakh` 等都不认识；而 `200 megapixels = 2亿像素` 是**正确**换算。
3. **符号位没有语境**：源文用 `below zero`，译文用 `-5`，被判成 5 vs -5。
4. **阿拉伯/波斯数字分隔符映射反了**：`normalize()` 把 U+066C（千位）映射成 `.`、U+066B（小数）
   映射成 `,`（`RebuildNumbers.java:112-114`）。正确的 `١٬٢٣٤`（1234）被读成 `1.234`，
   与源 `1234` 冲突。
5. **引文比较没有 Unicode 规范化**：NFD/组合符号、bidi 控制符（U+200F 等）都会破坏
   `compact()/identity()` 的逐字比较；而 event 的 `from/to` 本来就已经是权威身份。

### 3.2 一条坏 event 吃掉三条好 event

探针 `oneBadEventRejectsFourGoodOnes` 构造 4 个 event，其中第 2 个是 `Т-14`：

```text
event 0 numbers=UNKNOWN individually=OK
event 1 numbers=CONTRADICTED individually=BAD
event 2 numbers=UNKNOWN individually=OK
event 3 numbers=UNKNOWN individually=OK
BLOCK verdict=REJECT:numeric_substitution individuallyOk=3/4
```

3/4 的 event 单独看都是可发布的，但块级 parser 一条都不发布。**这就是“整段没有字幕”的
最小可复现单元。**

### 3.3 语言属性覆盖

探针 `profileCoverage`（68 个 code）：

- 17 个 code 得到具名 profile（`en/es/pt/fr/de/ru/ar/hi/th/vi/id/ja/ko/zh-Hans/zh-Hant` 以及
  `zh-CN`/`zh-TW` 的映射）；其余 **51 个（75%）落到 GENERIC**（17 cps / 42 cpl / FIRST_STRONG）。
- **裸 `zh` 是反例**：`CaptionLanguageProfile.fromCode("zh")` 返回 GENERIC（17/42），丢掉中文的
  9 cps/16 cpl 与 CJK 行计数。历史诊断里确实有 2 个会话的 target 是 `zh`。
- `th` 有 profile，却不在 `CaptionLanguageSelection.CODES`（14 个菜单 code）里，只能从
  YouTube 原生菜单选到。
- 代码自己的 `TargetLanguage` 名称表列了 46 个目标，其中 33 个没有专属 profile（含
  `it/pl/uk/tr/he/fa/ur/bn/ta/te` 等）。
- RTL：只有 `ar` 被登记为 `RTL`；`he/fa/ur` 是 `FIRST_STRONG`，`CaptionRenderSpec.apply` 对它们
  设 `LAYOUT_DIRECTION_LTR`（纯 RTL 行可能被 ICU first-strong 救回，但混合数字/Latin 时没有契约）。
- `CaptionScript.compact`：`ko/ja/zh/th/km/lo/my` 为 true。`km/lo/my` 正确，**`ko` 错误**
  （韩语用空格分 eojeol）。
- 标点/terminal 表不完整：`RebuildSource.terminal` 只认 `. ! ? 。 ！ ？`，缺阿拉伯 `؟`、
  乌尔都 `۔`、天城文 `।/॥`、希伯来 `׃`；`SourceReadingWindows.CLAUSE` 缺 `،/؛` 与 danda；
  `RebuildPageLayout.weight` 也不计这些停顿。句/分句边界在阿拉伯/希伯来/印地语源上会漏判。
- Indic 连字保护只有天城文（`CaptionUnicode.java:53-79`）；孟加拉/泰米尔/泰卢固在 API28 上仍可能
  在字素簇内切。
- 字号标定用汉字墨迹（`SubtitleStyleMetrics` 量 `经/频`），所有目标共用；阿拉伯的升降部、
  天城文的元音符号、泰文的高元音、越南的声调符号的视觉大小会与标定不一致。
- 质量启发式与提示词大量挂在 `canApplyEnglishToChinese` 上：en→zh 有额外 review/语义锚点/
  规划保护，其它 pair 走 neutral。这是**质量不对称**，不是“整段消失”的直接原因，但会让
  “非中文目标没有等价保护”成为长期债务。

对照 Netflix TTSG（可引用）：英 20 CPS，韩 12，中 9，日 4，泰 17，阿 20；每行上限
拉丁 42、韩 23（Originals 16）、中 23（16）、日 13、阿 50（42）、泰 35。现有 profile 的
**数字大体正确**（ja 4/13、zh 9/16、ko 12/16、en 20/42、ar 20/42、th 17/35），问题不在
“表里的数字”，而在：
- 表只覆盖 15 种（且裸 `zh` 掉出去）；
- 表只用于**“停留太久”**与合并容忍，**从不惩罚“文字太密”**；
- 表之外的语种静默退化，且没有任何诊断说明；
- 表里除 en/ja/ko 外的数值没有可引用来源（`docs/N28-CODEX-CORE-TASK.md:144` 只给了三个链接）。
- `reading_units` 计空格/标点，与 Netflix 的计数规则一致（对 half-width=0.5 的语种偏保守）；
  计数本身不是问题，问题是它没有被用作页密度下限。

### 3.4 源侧与提示词的语种偏置（不会直接造成空白，但会放大风险）

- **源轨选择偏好英文 pivot**：`SourceTrackPolicy` 在没有“语音语言的人工轨”时，把人工英文轨
  排在 ASR 之前，平票也偏向英文（`SourceTrackPolicy.java:230,239-240`）。法语/印地语/泰语视频
  可能从英文中介翻译，而不是从原声；这会改变数字、名称、句子边界，进而触发上面的校验误判。
- **提示词尺寸只给 CJK/Latin**：`RebuildProtocol.java:33` 要求“12–30 CJK 字符或 30–76 Latin
  字符/event”，阿拉伯、Indic、泰、韩没有对应指导；段落保护按“是否含 Han/Kana/Hangul”切
  60/100 与 155/260（`:387-388`）。
- **display_hint 分叉**：en→zh 给 `approx_cjk_columns_per_line`，其它目标给像素宽 +
  `presentation_policy=legacy_n26`（`RebuildApi.java:197-222`），而 neutral prompt 也硬编码
  同一个旧标签。
- **`SourceReadingWindows` 的宽脚本判定**只含 Han/Kana/Hangul/Thai，不含 Lao/Khmer/Myanmar，
  这些紧凑脚本会拿到拉丁尺寸的 84 单元页（`SourceReadingWindows.java:134-151`）。

这些偏置不会直接触发拒绝，但会让非中文 pair 的输入更“奇怪”，从而提高误判概率；P4 把它们
一并收进“按脚本推导”的注册表。

---

## 4. 失败分类学：25 个 `Invalid` 码与多条显示空白路径

### 4.1 所有 `Invalid` 都拒绝整块

`RebuildProtocol.parseInternal` 在单个 event 循环里抛异常，整块 `parseBound` 失败
（`RebuildProtocol.java:326-438`，`RebuildApi.java:350`）。没有“丢弃这个 event、保留其它”的路径。
流式 `parsePrefix` 只把前缀**显示**在屏幕上，不进入 plan、不缓存、最终失败即清除
（`RebuildController.java:376-390, 2013-2020`）。

### 4.2 校验分类（本设计的核心表）

| 现有 code | 新类别 | 新动作 |
|---|---|---|
| `json`、`block_identity`、`event_count`、`event_shape` | 响应结构 | 重试（有界）；失败则整批降级 |
| `index_type`、`text_type`、`protocol_leak`、`time_order` | segment 结构 | 丢弃该 event，保留其余 |
| `source_coverage`、`missing_source` | 覆盖 | 保留已覆盖 event，请求缺口 |
| `source_quote_mismatch`、`source_quote_required` | **引文提示** | ID 有效则接受；无效才用引文对齐；仍失败则丢该 event |
| `empty_translation` | segment 可用性 | 非语音→空段；否则丢该 event，请求该范围 |
| `crosses_source_break` | segment 边界 | 在断点处拆 event；保留两侧 |
| `information_collapse`、`target_language_mismatch`、`untranslated_source` | segment 可用性 | 丢该 event，只重试该范围一次 |
| `numeric_substitution`、`semantic_anchor_leak`、`numeric_range_invention`、`dependent_source_end` | **质量** | 只记 advisory；**发布** |
| `authored_cue_boundary`、`authored_cue_coverage`、`authored_cue_hole` | authored segment | 丢该 event；按 display group 请求 |
| `output_truncated` | 响应 | 有界重试 |
| `content_filter` | 供应商 | 该块 `UNAVAILABLE`（不重试），中性标记 |
| `configuration_*` | 会话 | 终止并提示配置 |
| HTTP 429/5xx/timeout | 传输 | 有界退避 |

只有第一列最后一组（响应结构）和“会话/供应商”才允许影响整块；其它一律 segment 级。

### 4.3 显示层还有多条“空白路径”

除块失败外（详见审计）：

- 计划 READY 但当前时刻不落在任何 unit（估算时间空洞）→ `source_gap`，无 watchdog；
- authored cue 契约在渲染时拒绝缓存/旧 plan → units 为空 → 空白，状态仍是 READY；
- ≤4 字符的允许空洞不产生任何标记（`withinHole`）；
- `content_filter` → FAILED 后整个会话不再重试；
- 源加载可重试失败无次数上限；不可重试失败直接终止会话；
- 页计划为空会被 `TranslationPresentation` 兜底成全页（好），但 UI 投影失败会空白；
- `outside_owned_window`/`preparing_layout` 有 800–1200 ms hold，属于正常瞬态。

**共同点：没有“覆盖账本”。** 没有任何组件回答“此刻有语音，屏幕上有没有对应的东西”。

### 4.4 重试没有终点

块级：尝试用尽后 `attempts = maxAttempts - 1` 并重新派发（`RebuildController.java:1425-1429`），
永远每 5/15 s 一次；源加载重试也无次数上限。`content_filter` 反而永久不重试。两者都错：
**确定性失败不应该重复付费，瞬态失败应该有预算。**

---

## 5. 根因

### R1（单元错位）：请求/校验/显示共用 block，失败却是 event 级

block 覆盖 8–30 s；一个 event 通常 2–4 s。用一个 20 s 的单元去承载 2 s 的错误，代价被放大 5–10 倍。
部分发布、部分缓存、部分重试都不存在。

### R2（类别错位）：质量建议被当成身份门禁

数字、语义锚点、句末完整性、长度都是“译文可能不够好”；引文逐字、时间顺序、ID 所有权才是
“这条译文不能算作这个源的”。现在两类混在 `Invalid` 一个异常里，全部导致整块丢弃。

### R3（语种错位）：语言知识写死在表/特例里，未知语言静默降级

15 个 profile、14 个菜单 code、en→zh 特例、Hangul 误判、数字词表封闭、RTL 只登记 ar。
任何 YouTube 允许的目标语言都可能落在外面的“generic + 未测试”区域，且没有诊断告诉你
“这个语种没有专属支持”。

### R4（时间错位）：没有覆盖不变量与截止时间

计划可以 READY 却整段不可见；失败可以无限 `WAITING`；`render()` 在没有 unit 时只能 `hide()`。
系统没有“语音时间轴 → 显示状态”的账本，因此“整段没有字幕”既不会被告警，也不会被兜底。

### R5（反馈错位）：诊断只记“拒绝了什么”，不记“观众少看了什么”

`REBUILD_EVENTS_REJECTED` 有 reason，但没有“该 range 最终是否显示/降级”；没有 per-session
覆盖率；没有“这个语种用了哪个 profile（GENERIC?）”。所以每次只能靠用户肉眼发现“有一段没有”。

### 为什么“再加语种表/再调阈值”不是根本解

- **加 profile 只改排版**：把 140 个语言都配上 CPS/CPL，`Т-14`、`2亿`、`-5`、NFD 引文仍会在
  每个语种上误拒——它们走的是 identity/质量门禁，不是排版。
- **调阈值只是移动边界**：数字/引文的“正确写法”是开放集合（新单位、新型号、新 normalization、
  新 bidi 控制符）；任何字面规则都会被下一个语种/视频绕过。
- **单点修补会留下同类**：N40 修了 join 空格、N42 修了分数，但 block 粒度、quote 权威、
  数字类别、无界重试没变，所以新诊断又长出 `Т-14` 和 `2亿`。
- 根本解必须同时改三件事：**谁能否决（identity vs quality）**、**失败粒度（block vs segment）**、
  **未知语言怎么办（注册/合成而不是静默 GENERIC）**。

---

## 6. 设计不变量（I1–I7）

**I1 覆盖不变量**：对每一个“有语音”的源时间区间，显示状态必须是
`TRANSLATED | HELD | UNAVAILABLE(reason,since) | SILENT` 之一；`PENDING` 只允许存在到截止时间。
不允许“无状态空白”。

**I2 segment 原子性**：校验、修复、发布、缓存、显示的单位都是 **segment**（连续源 token 范围 +
自己的时间窗，通常一句/cue）。block 只是**批次**。批次里合法的 segment 立即发布。

**I3 校验分类**：只有 identity（结构、ID、所有权、覆盖、可用性）能拒绝 segment；
quality（数字、单位、语义、句末、密度）只写 advisory，永不抑制显示。

**I4 语言中立**：目标语言 = 播放器给出的任何 code；profile 由 ICU/CLDR 的脚本与 locale 推导；
方向/空格/断行/字素来自 UAX #9/#14/#29；阅读速率来自可引用表 + 脚本回退；任何未知语言
都能工作，只记 `PROFILE_SYNTHESIZED`。

**I5 有界重试**：每个 range 的尝试按**策略**计数（整块 → 缺口 → 单句 → 降级），不是按
相同请求重复；同一 (range, reason, prompt, model) 第二次不再付费；每块有截止时间。

**I6 降级阶梯**：接受文本 → 跨短停顿保持上一页 → 有界 `UNAVAILABLE` 中性标记。
绝不显示源文（除显式原文模式）；绝不在截止后继续“翻译中…”。

**I7 可观测/可验收**：每会话输出 `COVERAGE_REPORT`（语音 ms、已译 ms、保持 ms、不可用 ms、
原因、语种、profile 来源）；CI 有全语种 conformance suite，误拒率与覆盖率是发布门禁。

---

## 7. 架构

### 7.1 Segment 与部分发布

- `RebuildProtocol.Plan` 增加：`coveredFrom/coveredTo`、`holes`（显式缺口）、`partial`。
- 新增 `parseSalvage(reply, source, block, context)`：
  1. 解析 JSON 外壳（失败=响应结构，整批重试）；
  2. 逐 event 做 **segment identity** 检查，通过的加入 `accepted`；
  3. 记录未覆盖的 token 范围（不限于 4 字符；允许任意缺口，但缺口必须有记录）；
  4. 若 `accepted` 非空，返回 `partial` plan；否则按原错误重试/降级。
- `parsePrefix`（流式）复用同一套逐 event 检查；它本来就是“部分 plan”的雏形。
- 控制器：
  - 接受 partial plan → `plans[i]` 可立即显示已覆盖 event；
  - 计算未覆盖范围 → 生成**子请求**（`Block` 的子范围，稳定 id 如 `b4_243_313#2`）；
  - 子请求最多 2 级（整块 → 缺口/单句），仍失败 → 该范围 `UNAVAILABLE`。
- 缓存：以 segment/range 为 key（源 token hash + target + prompt + model），partial 也可缓存。
- 人工 cue：子范围按 `RebuildCueContract.groups()` 的 display group 边界切，保证一个 cue
  不会被拆到两个请求里。

### 7.2 校验分类（改动点）

- 新增 `RebuildProtocol.Reject`（identity）与 `Observation`（quality）；`Invalid` 保留给 identity，
  quality 走 `RebuildReview.Issue`（已有）。
- 逐 event 循环：
  - identity 失败 → 丢弃该 event，记录 `SEGMENT_REJECTED`，继续下一个；
  - quality 失败 → 加入 `issues`，继续。
- 引文：ID 有效即接受；`source` 只用于 **ID 无效时**的重对齐；比较前做
  `Normalizer.normalize(NFKC)` + 去除 `Character.FORMAT`（bidi 控制符/ZWJ/ZWNJ）+ 标点/空白归一。
- 目标脚本：由语言注册表给出“允许脚本集合”；不匹配只丢该 event（并重试一次），不丢块。
- 数字/单位：见 7.4。

### 7.3 语言注册表（运行时，不是 14）

- 输入：播放器 `translationLanguages`（诊断里可记录快照）+ 用户选择；
- 归一化：ICU likely-subtags + 别名（`iw`→`he`、`in`→`id`、`zh-CN`/`zh-SG`→Hans、
  `zh-TW`/`zh-HK`→Hant，但**保留原 code 作为 target 身份**）；
- 推导：`Locale` → `UScript` → 方向（RTL 脚本集合）、空格（space-delimited 脚本集合）、
  行/词/句/字素边界（ICU `BreakIterator`）、允许脚本集合；
- 阅读参数：`(cps, cpl, maxLines=2)` 表 + 脚本回退；表项注明来源（Netflix TTSG 等）；
- 未知 code：合成 profile（保守 cps/cpl、FIRST_STRONG、spaced），记 `PROFILE_SYNTHESIZED`，
  **仍然工作**；
- 空格规则：Han/Kana/Thai/Lao/Khmer/Myanmar 紧排；**Korean 属于空格分隔**；拉丁/西里尔/
  希腊/阿拉伯/希伯来/天城文等用空格；合并事件时用 UAX #29 词边界决定“是否需要空格”，
  不再用“compact 布尔”一刀切。

### 7.4 数字/日期/单位：从“门禁”改为“数量模型 + advisory”

- 词法：数字 token 的左右边界用 `\p{L}\p{N}`（Unicode），`+/-` 只有在前面是空白/括号/
  运算符时才视为符号；`字母+连字符+数字` 整体识别为 identifier（`T-14`、`МиГ-29`、`T-62`）。
- 数量：解析 `(value, unit, scale)`；单位表包含 `megapixel/MP`、`million/亿/억/crore/lakh`、
  `km/mile`、`°C/°F` 等；比较**值**，允许单位换算；不确定→`UNKNOWN`。
- 日期/时间：用 locale 的 CLDR 模式或保守的“月名/数字对”解析；不跨语言比较字面。
- **默认不拒绝**：`numeric_substitution` 变成 `numeric_observation`（advisory）。
  若产品要求“数字必须对”，也只对**证明是 quantity 且单位相同**的单值冲突做**该 event**的
  定向重试，不丢块（默认关闭，见第 10 节）。
- 理由：用户本轮的第一优先级是“不要整段没有字幕”；错一个数字仍能看到句子，空白则什么也没有。

### 7.5 引文/Unicode

- 以 ID 为权威；引文是“修复提示”，不是“身份”。
- 比较前：NFKC/NFC、去格式控制符、标点/空白归一、可选大小写折叠（仅用于对齐，不用于接受文本）。
- 这样消除 NFD、bidi 控制符、全角/半角、弯引号等整类失败。

### 7.6 重试与降级

- 每块一个 deadline：`max(block.end, now) + 2 s`（可配）；到点必须出终态。
- 策略阶梯：`WHOLE → MISSING_RANGES → SINGLE_SENTENCE → UNAVAILABLE`；每级最多 1 次。
- 确定性 memo：`(range, reason, prompt_hash, model)` 第二次直接跳过；不同策略不算重复。
- `content_filter`：块级 `UNAVAILABLE`，不重试；`configuration_*`：会话终止；
  429/5xx：有界退避（已有）。
- 部分 plan 已显示时，后续修复只能替换“尚未显示”的范围（沿用现有 `adoptPendingPlan` 的
  “只在未显示时替换”原则）。

### 7.7 覆盖账本与显示

- `Session` 增加 `Coverage`：按源时间轴记录每个 speech 段的终态；`render()` 在无 unit 时先查账本：
  - 若在 `HELD` 窗口（≤ `HOLD_MS` 或声明停顿）→ 保持上一页；
  - 若 `UNAVAILABLE` → 显示中性标记（例如 `…` 或本地化的“字幕暂不可用”，**不是源文**）；
  - 若 `PENDING` 且未到 deadline → 现有“翻译中…”；
  - `SILENT` → 空白（正确语义）。
- 每会话结束（或诊断导出）输出 `COVERAGE_REPORT`。

### 7.8 诊断

新增：
- `LANGUAGE_PROFILE`：code、归一化、脚本、方向、spacing、cps/cpl、来源（表/合成/回退）；
- `SEGMENT_DECISION`：range、identity/quality、动作（accepted/dropped/retried/degraded）；
- `COVERAGE_REPORT`：speech_ms、translated_ms、held_ms、unavailable_ms、pending_max_ms、
  reasons、per-language；
- `PAGE_DENSITY`：units、duration、cps、limit、over_limit；
- `RETRY_DECISION`：strategy、attempt、memo_hit、deadline_remaining。

---

## 8. 全语种 conformance suite

### 8.1 轴

| 轴 | 取值（示例） |
|---|---|
| 目标语言 | 运行时 `translationLanguages` 快照（约 140 code），CI 用固定快照 |
| 脚本 | Latin/Cyrillic/Greek/Arabic/Hebrew/Devanagari/Bengali/Tamil/Telugu/Kannada/Malayalam/Gujarati/Gurmukhi/Sinhala/Thai/Lao/Khmer/Myanmar/Han/Kana/Hangul/Georgian/Armenian/Ethiopic |
| 文本形态 | designation、scale/unit、decimal comma、date、time、currency、percent、range、negative、ordinal、quote、name、mixed script、emoji、long、fragment、no punctuation、RTL marks、NFD |
| 源格式 | JSON3（逐词）、VTT（cue）、SRT、SRV3/XML（`<s t>`）、粗重建 |
| 模型输出 | 一句一 event、逐分句、合并、prefix、截断、拒绝、错脚本、回声、丢词、ID 偏移、跨句 |
| 故障注入 | 429/5xx/timeout/content_filter/config/空响应/坏 JSON |

### 8.2 Oracle（验收判据）

1. **忠实语料**：`parse` 必须接受；页必须覆盖 100% 时间窗；`COVERAGE_REPORT` 的
   `unavailable_ms=0`、`pending_max_ms < deadline`；误拒率 = 0。
2. **对抗语料**（回声、错脚本、ID 越界、丢词、编造文本）：必须**按 segment** 拒绝，
   邻段仍发布；块级拒绝率为 0（除非整个响应不可解析）。
3. **故障注入**：必须在 deadline 内进入 `UNAVAILABLE` 或 `TRANSLATED`；不得出现
   `PENDING` 超过 deadline；重试次数 ≤ 策略预算。
4. **语言公平性**：每个语言的接受率与全局均值之差 ≤ ε（如 2%），否则视为回归。
5. **分页**：页界只落在 UAX #14 合法断点；韩语不得在 eojeol 内翻页（除非 emergency）；
   `PAGE_DENSITY.over_limit` 有上限（如 < 20% 页）并被诊断。

### 8.3 现有矩阵如何扩展

本轮的 32 例是种子。扩展方式：
- 每脚本至少 1 个语言 × 每个文本形态（约 24×18 = 432 例）；
- 每源格式至少 1 例（JSON3/VTT/SRV3/SRT/粗重建）；
- 每模型输出形态至少 1 例（12）；
- 故障注入 8 例；
- 组合采样（pairwise）控制在 ~600 例，CI 可在数分钟内跑完（纯 JVM，无网络）。

---

## 9. 执行计划（P0–P5）

| 阶段 | 内容 | 关键文件 | 验收 |
|---|---|---|---|
| **P0（本轮，已完成）** | 32 份语料统计、32 例矩阵、代码审计、外部标准、本设计 | `docs/N44-*`、`validation/runs/20261019-n44-*` | 矩阵可复跑；结论有证据 |
| **P1（先做，零行为变化）** | 把矩阵变成 CI conformance suite；加 `LANGUAGE_PROFILE`/`SEGMENT_DECISION`/`COVERAGE_REPORT` 诊断；记录 `translationLanguages` 快照 | 新 `translation-core/src/test/.../LanguageConformanceTest`、`RebuildController`/`TranslationRuntime` 诊断 | 现有行为下矩阵失败数被记录为基线；诊断能回答“哪个语种/range 没显示” |
| **P2（最大收益）** | 校验分类：引文→提示、数字/语义/句末→advisory、目标脚本→per-event；Unicode 规范化；`parseSalvage` + 部分发布 + 缺口子请求 | `RebuildProtocol`、`RebuildReview`、`RebuildSemantics`、`RebuildNumbers`、`RebuildController` | 忠实语料误拒 0；矩阵 22/32 → 0；历史 87% 拒绝原因不再丢块 |
| **P3** | 覆盖账本 + deadline + 策略阶梯 + 中性标记 + 有界重试 | `RebuildController`、`ui/CaptionOverlayV2`、`RebuildApi` | 故障注入全部在 deadline 内终态；无 `translation_failed` 永久空白 |
| **P4** | 语言注册表：运行时 code、脚本推导、方向/空格/断行、阅读参数扩展与来源标注；裸 `zh`、`th` 菜单、33 个无 profile 目标、韩语修正；标点/terminal 表补 RTL/Indic；字号按脚本标定 | `CaptionLanguageProfile`、`CaptionScript`、`CaptionRenderSpec`、`RebuildDisplayGroups`、`CaptionLanguagePager`、`CaptionLanguageSelection`、`RebuildSource`、`SubtitleStyleMetrics` | 140 code 全部有 profile（具名或合成）；裸 `zh` 走 9/16；`th` 可被菜单选中；韩语空格/页界正确；RTL 由脚本推导；阿拉伯/希伯来/印地语句界可识别 |
| **P5** | 页契约：密度下限、引子/残尾语言无关、`PAGE_DENSITY`；发布门禁（误拒 0、覆盖 100%、公平性 ε） | `RebuildPageLayout`、`CaptionLanguagePager`、CI | 矩阵与真实语料双通过；发布前自动跑 |

每阶段都可独立回滚（P2 的 advisory 化可用一个内部开关灰度；P3 的中性标记可用设置关闭）。

---

## 10. 与现有约束的取舍

1. **不显示原文**（N41）：中性标记不是原文，符合约束；保持上一页跨短停顿是既有行为。
2. **错一个数字 vs 整段空白**：本设计默认“先显示、记 advisory”。若产品要求数字正确优先，
   可对“单值 quantity 冲突”做**该 event** 的定向重试，仍不丢块；默认关闭。
3. **成本**：部分重试只针对缺口，通常比整块重试更省；deadline + memo 封顶。P2 后
   `translation_failed` 帧应趋近 0。
4. **缓存**：block → segment/range key；partial 可缓存；旧缓存按版本失效（`caption-reading`/
   `protocol` 版本号已有，需加 `coverage-v2`）。
5. **提示词**：不再有“非中文走另一套”的暗示；数字/型号保留要求保留，但校验不再据此拒绝。
6. **模型质量**：本设计不承诺译文语义正确；只保证“不因校验误判而整段消失”。语义质量仍由
   供应商与母语验收决定（N23/N39 边界）。

---

## 11. 附录

### A. 32 份诊断统计

见第 2 节；可用 `validation/runs/20261019-n44-language-coverage/corpus_statistics.py` 复跑
（默认读取 `D:\HONOR Share\Honor Share`，可传目录参数；只读、不写文件）。本轮临时完整输出在
`.tmp/analysis/corpus.txt`。

### B. 32 例矩阵输出

`validation/runs/20261019-n44-language-coverage/probe-output.txt` 的 `KIND ...` 段。

### C. 代码锚点（关键）

- 块级校验：`RebuildProtocol.java:297-438`（`parseInternal`、`withinHole`）；
- 全部 `Invalid` 列表与行号：见第 4.2 节（逐条来自源码）；
- 无界重试：`RebuildController.java:1407-1430`、`:1626-1696`；
- 显示空白：`:1965-2055`（`render`）、`:1810-1826`（authored 契约）；
- 数字：`RebuildNumbers.java:39-40, 202-227`；
- 引文：`RebuildProtocol.java:549-596`（`sameSource`/`identity`/`compact`）；
- 语言表：`CaptionLanguageProfile.java:46-62`；
- 脚本/空格：`CaptionScript.java:29-43`、`RebuildDisplayGroups.java:369-383`；
- 页：`RebuildPageLayout.java:108-193`、`CaptionLanguagePager.java:23-39, 388-487`；
- 语言选择/记忆：`NativeCaptionBridge.java:536-628, 802-822`、`CaptionVideoHandoffV2.java:52-86`。

### D. 外部标准

- Netflix 阅读速度（含空格/标点计数规则）：<https://partnerhelp.netflixstudios.com/hc/en-us/articles/115001352212-How-is-reading-speed-measured-Do-punctuation-and-spaces-count>
- Netflix 每行字符上限：<https://partnerhelp.netflixstudios.com/hc/en-us/articles/215274938-What-is-the-maximum-number-of-characters-per-line-allowed-in-Timed-Text-assets>
- 逐语种 TTSG（英/韩/中/日/泰/阿）：见各语言 guide（例：英 <https://partnerhelp.netflixstudios.com/hc/en-us/articles/217350977-English-USA-Timed-Text-Style-Guide>、韩 <https://partnerhelp.netflixstudios.com/hc/en-us/articles/216001127-Korean-Timed-Text-Style-Guide>、日 <https://partnerhelp.netflixstudios.com/hc/en-us/articles/215767517-Japanese-Timed-Text-Style-Guide>）
- UAX #14 断行（含 ZW/U+200B、韩语两种断行风格）：<https://www.unicode.org/reports/tr14/>
- UAX #29 字素/词/句边界（含韩语 eojeol 说明）：<https://www.unicode.org/reports/tr29/>
- CLDR/UTS #35 数字/分组/本地化格式：<https://www.unicode.org/reports/tr35/tr35-numbers.html>
- YouTube 目标语言列表（vendor 数据，CI 快照用；运行时以播放器元数据为准）：<https://docs.supadata.ai/youtube/supported-language-codes>

## 12. 边界与未决

- 32 份诊断跨越 10-15 至 10-19 的多个版本，拒绝构成是**历史**的；最新一份的构成（numeric 7/11）
  更能代表当前树。报告已分开陈述。
- 矩阵的“忠实译文”由本报告编写，目的是触发校验器的语种假设，不用于评价模型质量。
- 语言列表来自 vendor 文档，不是 Google 官方；设计上不依赖固定列表，而是运行时读取
  `translationLanguages`，CI 用快照。
- 部分发布会让“已显示文本”和“稍后修复文本”共存，必须沿用现有“只在未显示范围替换”的规则，
  否则会跳字。
- 中性标记的文案/样式是产品决定；本设计只要求“非源文、可见、有界”。
- 本轮没有改产品代码；P1–P5 的实施需要按 AGENTS 的验证路径（构建、测试、真机 after 诊断）。




