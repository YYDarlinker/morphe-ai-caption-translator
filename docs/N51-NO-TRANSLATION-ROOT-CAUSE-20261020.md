# N51 — 译文字幕不显示（14.4 秒“翻译中…”）的根因调查（2026-10-20）

> 状态：**只读调查 + 可复现离线探针**。本轮没有修改产品代码、没有重建 MPP、没有连接手机、没有写入设备。
> 结论针对现场安装的构建；该构建已用字节证据锁定（§1.2）。
>
> **实施记录（2026-10-20 晚）**：§7.2/§7.3 的两层投影已在 `docs/N51-REPAIR-20261020.md` 落地
> （本地 1.5.5 MPP）；本报告 §7 保留为设计依据，实际代码以修复记录为准。
>
> 输入：`D:\HONOR Share\Honor Share\caption-diagnostics-1.5.4-20261020-173353.txt`
> （531,749 B，SHA-256 `D424CC470CA74D1DF10824AE1DB22627569A7653C3FA9D1C4B550A0DF5A561EF`，
> 导出于 2026-10-20 17:33:53）。
>
> 探针：`validation/runs/20261020-n51-no-translation-root-cause/`
> （`TmpN51CueDeadlockProbeTest.java`、`probe-output.txt`、`time-budget.txt`）。

---

## 0. 一句话结论

**这次“不显示翻译”不是因为模型没有翻译。** 出问题的块（`b4_268_374`，视频 44.250–58.750 s，4 个
人工 cue）里，模型把该块 **107 个 token 全部、连续、无空洞**地译成了 3 条中文（原始 token
268–291 / 292–315 / 316–374）；作者的第一条 cue 则是 268–285。第一条事件从 268 一直连到 291，跨过了
285/286 的 cue 边界；显示层把“页”定义为**必须与作者 cue 精确对齐**，于是：

1. 严格校验以 `authored_cue_boundary` 拒绝**整个块**（4 次，4/4 同错）；
2. 局部 salvage 只接受“被事件精确铺满的 cue”：它不会把“包含整条 cue 的大事件”裁成该 cue 的页，
   也不接受“只覆盖一部分”的 cue，所以同样一行也发布不出来；
3. 块保持 `WAITING`，屏幕在整块 14.5 秒里显示“翻译中…”；第 4 次拒绝后的 15 s 退避已超过块的结束
   时间（54.089 + 15 = 69.1 s > 58.750 s），**任何重试都不可能在该块结束前到达**。

根因是结构性的：**显示是“计划驱动”（plan-driven）的，而计划的“页”被绑定为作者的 cue；只要模型
对句子边界的切分与作者的 cue 不一致，已经存在的译文就无法变成屏幕上的字幕。** 这不是某个模型、
某个语种或某次重试的局部错误——cue 边界是作者按时间切的，不是语法边界；任何按句子翻译的模型都会
遇到它，任何语种都会遇到它。

---

## 1. 输入、构建身份与整体时间预算

### 1.1 诊断基本信息

| 项目 | 值 |
|---|---|
| 导出时间 | 2026-10-20 17:33:53（`exported_at=1792488833359`） |
| 会话 / 视频 | `session=25`；`video=FzRyrjFmZSQ`（AppleUnsold 主题） |
| 语言对 | `source=ja`（人工轨 `ja/MANUAL`）→ `target=zh-Hans` |
| 引擎 | Event rebuild / 1.5.4，`core=rebuild-1.4.0/ac65fe8`，`policy_version=n28b-policy-v1` |
| 诊断记录 | 1,181 条带时间戳记录；`REBUILD_PRESENTED` 82 条；`REBUILD_UNIT_MODE` 37 条 |
| 覆盖 | 墙钟 66.01 s；日志内视频位置 0 → 63.9 s |

### 1.2 构建身份（字节证据）

诊断头写的是 `ui=1.5.4; engine=1.5.4; build=release-1.5.4`，但工作树 HEAD 的
`gradle.properties` 是 `1.5.5 / local-1.5.5`。字节证据把现场构建锁定为**版本号尚未提升的 N48 构建**：

- `build/n39/gradle/patches/libs/patches-1.5.4.mpp`：**592,741 B**，SHA-256
  `BB906F3EA60EABED517EB7FC7C8F2422547BD6838C2F015A22D96F31522A558D`，mtime 2026-10-20 13:31:30。
  这个哈希与 `docs/PROJECT-STATE.md` §18（N48 验证记录）**完全一致**。
- 该 MPP 解包后包含 `salvage_authored_cue_boundary`、`unitsPartial`、`REBUILD_PARTIAL_ACCEPTED`、
  `REBUILD_CUE_CONTRACT_REJECTED`、`display_cues`；不包含 `RememberedCaptionSelection`。
- `version=1.5.5` 是 `8ed5866`（2026-10-20 14:07:43，晚于构建）才改的；所以 13:31 构建出来仍报
  1.5.4 / `release-1.5.4`。
- 除版本号外，现场代码内容 = 当前 HEAD `8ed5866`（构建后 36 分钟才提交；诊断里的事件词汇与 HEAD
  代码一一对应）。本报告的代码行号均对应该工作树。

### 1.3 整体时间预算（按 `REBUILD_PRESENTED` 状态持有时间）

| 状态 | 时长 | 占比 | 说明 |
|---|---:|---:|---|
| 译文可见 | 43.11 s | 65.3% | 正常翻译页 |
| **“翻译中…”占位** | **14.40 s** | **21.8%** | 块 4 四次被拒，整块无输出（§2） |
| “…” 不可用标记 | 3.28 s | 5.0% | 块 1 的 salvage 空洞覆盖整条 cue（§6.5） |
| 播放器/所有权抑制 | 2.84 s | 4.3% | `player_suppressed` 2.73 s（COMPACT/小窗）+ `owner_invalid` 0.11 s，属预期 |
| `source_gap` 空白 | 1.63 s | 2.5% | 跨块/句间静默的页缝（§6.6） |
| 布局未就绪空白 | 0.59 s | 0.9% | 新页首帧 `overflow_status`（§6.7） |
| `outside_owned_window` | 0.15 s | 0.2% | 页窗切换的毫秒级缝隙 |

即：**约 22.9 秒（34.7%）没有任何译文字幕**，其中 14.4 秒集中在同一块上。

---

## 2. 现场时间线：一块 4 cue、14.5 秒的视频，只有“翻译中…”

块 4 = `b4_268_374`，视频窗口 44.250–58.750 s，含 4 条人工 cue。诊断里四条
`waiting:4:{start}_{end}` 显示记录恰好把该块切成四段——这就是四条 cue 的窗口（占位窗 = 
`cue ∩ block`，见 `RebuildController.render()` 的 `waiting` 分支）：

| cue | token（推断） | 视频窗口 | 时长 |
|---|---|---|---:|
| 16 | 268–285 | 44.250–46.958 | 2.708 s |
| 17 | 286–315 | 46.958–50.792 | 3.834 s |
| 18 | 316–351 | 50.792–55.708 | 4.916 s |
| 19 | 352–374 | 55.708–58.750 | 3.042 s |

请求/拒绝（墙钟，相对导出前 66 s 的日志起点）：

| 墙钟 | 事件 | 结果 |
|---:|---|---|
| 16.32 | `REBUILD_REQUEST` 75（prefetch，position=14254） | 200，2.713 s |
| 19.08 | `REBUILD_EVENTS_REJECTED` 75 | `authored_cue_boundary; range=268-291; crosses display group 16 (268-285); attempts=2; session_repairs=1` |
| 24.09 | 请求 76（prefetch，position=22018） | 200，3.617 s |
| 27.73 | 拒绝 76 | 同一错误；`attempts=3; session_repairs=2` |
| 42.83 | 请求 84（prefetch，position=40768） | 200，3.110 s |
| 46.01 | 拒绝 84 | 同一错误；`attempts=2; session_repairs=2` |
| 46.38 | 视频进入块 4 | 显示“翻译中…”（cue 16 窗） |
| 51.02 | 请求 89（**focus**，position=48957） | 200，3.022 s |
| 54.09 | 拒绝 89 | 同一错误；`attempts=3; session_repairs=3` |
| 54.09→ | 退避 | `attempts>=3 → 15000 ms`，下一次 69.09 s > 块结束 58.750 s |
| 60.88 | 块 5 的译文出现（视频 58.798 s） | 块 4 从未显示 |

四次拒绝的 `detail` **完全相同**（`range=268-291; crosses display group 16 (268-285)`）。重试请求的
`repair` 字段已经把这个具体范围写给了模型，模型仍然给出同样的切分。reset 之后的 7 次请求中
**3 次接受、4 次拒绝**，四次拒绝全部是同一个块。

> 注：四次拒绝后 `session_repairs=3`（`MAX_SESSION_REPAIRS=6`），所以“再来一次”的预算也只剩 3 次；
> 而块只剩 4.7 秒，退避本身已经越过了块尾。

---

## 3. 被丢弃的回复：内容完整，只有边界不合

`REBUILD_QUALITY_WARNING` 的 `repair_candidate=false` 只是数字提示。真正的内容在质量证据里
（请求 89 的 `source` + `response`，原文照录）：

```json
{"block":"b4_268_374","events":[
 {"from":268,"to":291,"text":"无论是国内还是海外，热度都几乎还没起来，所以，"},
 {"from":292,"to":315,"text":"除了抢先去“自爆”之外别无选择了吧♡"},
 {"from":316,"to":374,"text":"此前揭露过各种电商平台上滋生的无数黑暗面，我们这位博主怎么可能不投身于这压倒性的黑暗之中呢。"}
]}
```

- 事件在 token 上**连续铺满** 268–291 / 292–315 / 316–374，无空洞、无重叠；`owned_tokens` 共 107 个，
  **107/107 都被翻译覆盖**（探针逐个 cue 验证：18/18、30/30、36/36、23/23）。
- 只有两处“错位”：事件 1 越过 cue 16/17 的边界（285/286），事件 3 越过 cue 18/19 的边界。
- 严格校验（`RebuildCueContract.validate`，`translation-core/.../RebuildCueContract.java:159-241`）在
  `event.to > group.to` 时抛 `authored_cue_boundary`（:189-202），一个事件越界就丢掉**整块所有 cue**。
- `parseBestEffort`（`RebuildProtocol.java:322-332`）随后尝试 salvage；`RebuildSalvage.parse` 把越界
  事件直接丢弃（`RebuildSalvage.java:98-108`），而 authored 轨道只有在 `unitsPartial` 非空时才接受
  （:215-216）；`unitsPartial` 要求某个 cue 被事件**精确铺满**（`RebuildCueContract.java:286-317`，
  特别 :305-313：`owned.get(0).from == group.from && owned.get(last).to == group.to`）。
- 本回复中：
  - cue 16 完全**包含**在事件 1（0–23）里，却因为事件 1 多带了 cue 17 的头 6 个 token 而被丢弃；
  - cue 17 只得到**部分覆盖**（前缀 18–23 被事件 1 吞掉，事件 2 从 24 才开始）；
  - 最后两条 cue 被事件 3（48–106）一次跨过 → 事件 3 整体丢弃。
  
  结果没有任何一条 cue 被精确铺满 → salvage 返回 `null` → `parseBestEffort` 重新抛出严格错误 →
  `rejected()` 把块置为 `WAITING`。

探针用**同一份回复**遍历了 1,894 种与“cue 16 = token 0–17”一致的四 cue 切法：

```text
PARTITION trials=1894 salvage_accepted=28 accepted_at_model_event_boundary=28 accepted_elsewhere=0
```

也就是说，**只有模型的边界恰好落在某条 cue 边界上时（token 24 或 48），salvage 才能发布**；
98.5% 的切法一行都发布不出来。现场诊断里没有任何 `REBUILD_PARTIAL_ACCEPTED`，说明实际切分正落在
这 98.5% 里。

---

## 4. 代码路径：一条“计划驱动”的显示链

### 4.1 请求单元 = 最多 4 条 cue 的块

`RebuildPlanner.planAuthored()`（`RebuildPlanner.java:99-123`）把连续 cue 打成请求块：
`MAX_AUTHORED_CUES=4`、`MAX_AUTHORED_CHARS=600`、`MAX_AUTHORED_SPAN=20 s`（:90-92）。本块正好 4 条
cue、14.5 s，所以**一个 HTTP 请求对应 4 个显示页**。

### 4.2 提示词同时要求“整句”和“不跨 cue”

- `RebuildGuidance.SENTENCE`（`RebuildGuidance.java:49-58`）：
  “one event is one complete clause or sentence… A long source sentence may stay in one event, or be
  split only where each side keeps its own subject and predicate… never leave a prepositional phrase,
  a verb and its object… on opposite sides of an event boundary, and never shorten an event merely to
  make it fit.”
- `RebuildGuidance.displayCueRule`（:17-24）：
  “Authored display groups are fixed page boundaries… Every event must lie inside exactly one group…
  never merge two groups into one event and never move text across a group boundary.”
- 两者同时进入 authored 请求（`RebuildApi.prompt`）；`RebuildProtocol.payload`（:248-254）附上
  `display_cues` 与上次失败原因作为 `repair`。

本块 cue 16→17 的边界正好切在句子中间（cue 16 以 `…上がっていない` 的 `い` 结尾，cue 17 以
`というわけで、` 开始）；cue 18→19 的边界同样把一句话切成“投稿者が / この圧倒的な闇に…”。**模型无法同时满足两条规则**：
要么把句子切断（违反 SENTENCE），要么跨 cue（违反 displayCueRule）。探针输出确认提示词里两条规则
同时存在（`PROMPT has_display_cue_rule=true`、`has_sentence_rule=true`），且 `display_cues[0]` 的
source 以 `…い` 结尾。

### 4.3 显示页 = 计划页，计划页 = 作者 cue

`RebuildController.render()`（`RebuildController.java:2237-2466`）的选择逻辑：

1. `p = s.plans[i]` 或 stream 前缀（:2282-2287）；
2. `s.display.select(...)` 在 `displayUnits(...)` 里取覆盖当前时刻的 unit（:2288-2292）；
3. READY 且有 unit → 显示 `unit.text`；否则：
   - `WAITING`：若当前时刻在块内某条 source cue 里，显示“翻译中…”，窗口 = `cue ∩ block`
     （:2301-2319；注释明确“Waiting displays keep each source cue's own window”）；
   - `FAILED`：同一窗口显示 `…`，id `unavailable:failed:…`（:2320-2341）；
   - READY 但此刻**不落在任何 unit** 内：若该时刻落在 salvage 空洞里显示 `…`
     （id `unavailable:{block}:{hole}`，:2342-2354），否则 `source_gap` 空白（:2375-2381）。

也就是说：**屏幕上有无译文，取决于“是否存在一个覆盖当前时刻的 unit”**；unit 全部来自计划，而计划
是否成立又取决于作者 cue 是否被精确铺满。

### 4.4 失败后的状态机只会等

`rejected()`（:1877-1945）：

- 结构错误不致命 → `s.states[i] = WAITING`（:1915）；
- 退避 `attempts>=3 ? 15 000 : attempts>=2 ? 5 000 : 1 200`（:1916-1921）；
- 3 次尝试后进入慢重试，`MAX_SLOW_RETRIES=3` 再放弃（:1488-1512）。

块 4 的尝试间隔正是这条阶梯：日志里可见 5 s → 15 s → 5 s 三段（更早还有一次 1.2 s 的重试，在日志起点
之前）；第 4 次拒绝发生在 54.089 s，块结束 58.750 s，下一次请求排在 69.1 s——**在该块的生命周期内没有
任何重试可能成功**。

### 4.5 网络时延 ≈ cue 时长，重试在原理上救不了

本诊断 7 次 HTTP 的实际往返：2,137 / 2,713 / 3,617 / 2,521 / 3,110 / 2,578 / 3,022 ms（均值 2.81 s）；
块 4 的四条 cue 时长是 2.708 / 3.834 / 4.916 / 3.042 s。**一次请求的时延与一条 cue 的寿命同量级。**
即使把重试粒度改成“单 cue、立即重试”，也大概率在该 cue 结束之后才拿到结果。结论：显示层必须使用
**已经收到的回复**，不能把“等到更好的回复”当作字幕可用性的前提。

### 4.6 其余“无译文字幕”的路径（同一结构问题的不同表现）

| 现象 | 代码 | 机制 |
|---|---|---|
| “翻译中…”整块 | `render` :2301-2319 | 块 `WAITING`，按 cue 窗显示占位 |
| “…”整条 cue | `render` :2342-2354 + `fillHoles` :1779-1860 | salvage 空洞；补洞请求只发一次（`slowRetries<1`）且以“最大洞”为目标，没有按 cue 的截止时间安排 |
| `source_gap` | `render` :2342-2356 / :2375-2381 | READY 但当前时刻不在任何 unit；跨块/跨句的静默缝 |
| 布局空白 | `CaptionOverlayV2.render()`（见 `docs/ARCHITECTURE.md` 与 N43 记录） | 新页首帧 `preparing_layout`，无旧文本可 hold 时直接 `visible=false` |

---

## 5. 根因

### 5.1 直接机制（本诊断，可复现）

模型的回复**完整覆盖**该块并给出了可用译文；显示层因为“事件边界 ≠ 作者 cue 边界”拒绝了整块；salvage
又因为“必须精确铺满某条 cue”而无法发布任何一条；块停在 `WAITING`，占位符占据整块 14.5 秒；等退避
结束时块已经过去。**译文存在，只是没有一条“合法”的路径把它送到屏幕。**

### 5.2 结构根因

**显示以计划为中心，而计划页被定义为作者 cue；模型的事件边界被当作“页的身份/有效性”，而不是
“文本的投递格式”。**

- 请求/校验/重试/发布共用同一个单位：4 cue 的块；块内任何一页不成立 → 整块无输出。
- 页的成立条件是“事件精确铺满 cue”，这比“该 cue 的文本已被翻译”强得多：**包含**（事件比 cue 大）
  与**部分覆盖**（事件只盖住 cue 的一部分）都被判为不可用，而这两种情况恰恰是“模型按句子翻译”的正常
  产物。
- 从“回复里有译文”到“当前 cue 有页”之间**没有投影层**（没有裁剪、没有合并、没有按时间取文），只有
  全有或全无。

### 5.3 为什么“任何模型、任何语种”都会中招

- cue 边界是作者按**时间**切的（2.7–4.9 s 一条），不是语法边界；日语、英语、中文、任何语言都会出现
  “一条 cue 只是半句”的情况。
- 提示词同时要求“整句”和“不跨 cue”。当 cue 边界切在句子中间时，两条要求**互斥**；不同模型只会在
  “切句子”与“跨 cue”之间做不同选择，不存在同时满足的解。
- 一个块里只要**一条** cue 的边界落在句子内部并导致越界，就足以让整块归零；块越大，命中概率越高
  （4 cue/14.5 s 的块是当前配置的上限）。

### 5.4 为什么“多试几次 / 换个模型”不是修复

- 4/4 同样的**切分**（措辞略有不同，事件范围都是 268–291 / 292–315 / 316–374），且重试请求已把
  `range=268-291; crosses display group 16 (268-285)` 写进 `repair`；这不是模型“没听懂”，而是两条
  规则冲突。
- 退避 1.2/5/15 s 与块 14.5 s 的寿命不匹配；第 4 次拒绝后剩余 4.7 s，下一次 15 s 后才到。
- 一次请求的往返（2.8 s 均值）≈ 一条 cue 的时长；把重试当兜底，等于把字幕可用性押在网络上。

### 5.5 与既往修复的关系（为什么“修过还在”）

- N41/N42 把“页”从模型事件改成作者 cue，修复了“翻页落在句子中间/半句”的问题；
- 但这条修复把“模型必须与作者切分完全一致”变成了显示的前提条件；
- N44 增加了 salvage，但只覆盖“事件恰好铺满某条 cue”的情况；本诊断的“包含 / 部分覆盖”不在覆盖范围内。
- 所以现象从“显示原文”变成了“显示占位/空白”，**根没有变**：显示仍然依赖模型的切分去适配作者的分页。

---

## 6. 可复现探针

### 6.1 材料

- `validation/runs/20261020-n51-no-translation-root-cause/TmpN51CueDeadlockProbeTest.java`
- `validation/runs/20261020-n51-no-translation-root-cause/probe-output.txt`
- `validation/runs/20261020-n51-no-translation-root-cause/time-budget.txt`

探针不使用手写样例：107 个 token、3 条回复文本、4 条 cue 窗口全部从诊断文件程序化提取
（`owned_tokens`、`response`、`waiting:4:*` 窗口）；只有 cue 内部的 token 切分是推断的，因此探针
**遍历了所有与已知 cue 16 边界一致的切法**，而不是只验证一种猜测。

### 6.2 运行（pre-fix 快照）

> **重要**：这个探针是**修复前**的复现，针对构建 `8ed5866` / MPP `BB906F3E…`（§1.2）。在 1.5.5
> 修复后的树上，严格路径已不再拒绝跨 cue 事件，探针里有意的“拒绝”断言会失败——这是预期，不是
> 回归。修复后的可复现证据是 `N51AuthoredProjectionTest`（6 tests）与 `N51AuthoredDisplayTest`
> （真实引擎端到端），见 `docs/N51-REPAIR-20261020.md` §2。

```powershell
Copy-Item validation\runs\20261020-n51-no-translation-root-cause\TmpN51CueDeadlockProbeTest.java `
  translation-core\src\test\java\app\yydarlinker\deepseekcaptions\
.\.devenv\dev.cmd :translation-core:testDebugUnitTest `
  --tests app.yydarlinker.deepseekcaptions.TmpN51CueDeadlockProbeTest
# 读取 build\n39\gradle\translation-core\test-results\testDebugUnitTest\TEST-...TmpN51...xml
Remove-Item translation-core\src\test\java\app\yydarlinker\deepseekcaptions\TmpN51CueDeadlockProbeTest.java
```

pre-fix 树上执行结果：**3 tests，0 failures，0 errors**（输出见 6.3）。

### 6.3 关键输出（原文，pre-fix 树）

```text
BLOCK b4_0_106 authored=true words=107 span=44250-58750
GROUP cue=0 tokens=0-17  source=[国内・海外ともにまだほぼ上がっていない]
GROUP cue=1 tokens=18-47 source=[というわけで、これはもう一番乗りで自爆しに行くほか無いですよね♡]
GROUP cue=2 tokens=48-83 source=[これまで様々な通販サイトにはびこる数多の闇を暴き出してきたうちの投稿者が]
GROUP cue=3 tokens=84-106 source=[この圧倒的な闇に飛び込まずにいられるはずがない]
EVENT 0-23 withinOneGroup=false
EVENT 24-47 withinOneGroup=true
EVENT 48-106 withinOneGroup=false
STRICT parseBestEffort -> authored_cue_boundary ; range=0-23; crosses display group 0 (0-17)
SALVAGE plan=null
UNITS partial=0
COVERAGE cue=0 tokens=0-17  covered=18/18
COVERAGE cue=1 tokens=18-47 covered=30/30
COVERAGE cue=2 tokens=48-83 covered=36/36
COVERAGE cue=3 tokens=84-106 covered=23/23
PROJECTION page=1 window=44250-47724 chars=23
PROJECTION page=2 window=47724-50792 chars=18
PROJECTION page=3 window=50792-58750 chars=46
PROJECTION covered_ms=14500 block_ms=14500
PROMPT has_display_cue_rule=true
PROMPT has_sentence_rule=true
CUE0 tail=[にまだほぼ上がっていない]
CUE1 head=[というわけで、これはもう]
PARTITION trials=1894 salvage_accepted=28 accepted_at_model_event_boundary=28 accepted_elsewhere=0
```

### 6.4 这组输出证明了什么

1. 回复对**每一条 cue** 都有 100% 的 token 覆盖；译文并非缺失。
2. 严格路径拒绝（`authored_cue_boundary`），salvage 也发布不了（`plan=null`、`partial=0`）。
3. 同一条回复如果只是“按事件时间投影”，就是 3 页、覆盖整块 14.5 s——正是观众需要的东西。
4. 1,894 种四 cue 切法里，只有 28 种能 salvage；这 28 种全部要求“模型事件边界恰好等于 cue 边界”。
   **可用性取决于模型是否恰好与作者一致，而不是取决于译文是否可用。**

### 6.5 附带的第二种“不显示”：`…` 空洞

时间预算里还有 3.28 s 的 `…`，id 为 `25:8:unavailable:1:9375_12583`——来自 `render` 的 READY+hole
分支（:2342-2354）：块 1 的 salvage 计划在视频 9.375–12.583 s 有一条整 cue 的空洞，补洞请求没有在
该 cue 结束前补齐。它与块 4 是同一结构问题的另一面：**洞的修复以“最大洞”为单位、只有一次机会，
没有按 cue 的截止时间安排。**

### 6.6 `source_gap` 空白

`source_gap` 合计 1.63 s（0.36 s / 0.68 s / 0.59 s），都出现在**块内 cue 与 cue 之间的静默缝**（例如
视频 6.250–6.750 s 的两条 cue 之间）。自动轨的 `RebuildDisplayGroups` 有 1 s HOLD 延伸（:154-157），
但人工轨的页窗由 `RebuildCueContract.unit(...)` 严格设成 `group.end`（cue 结束），**没有 HOLD**；所以
作者 cue 之间的空隙直接变成清屏。这不是本次的主因，但属于“字幕闪断”的体验缺陷。

### 6.7 布局空白

`preparing_layout` 引起的 `overflow_status;visible=false` 共 9 次、累计 0.59 s；出现在“当前没有旧文本
可 hold、新页还在后台排版”的首帧（seek 后第一次出现）。它不是本次主因，但在
`source_gap` 后紧接着出现时，会让一次断流变成两次。

### 6.8 未在本诊断触发、但必须一起堵住的“粘性空白”

`CaptionOverlayV2.render()` 在排版失败（`measurement_mismatch` / `unprojectable_pixel_width` /
`hard_textview_geometry`）后会把当前 `pendingIdentity` 记为 `lastBlankIdentity`；此后 100 ms 的
pre-draw watchdog 因 `dirty=false && lastBlankIdentity==pendingIdentity` 直接返回，控制器的 80 ms tick
又因签名未变而短路。结果是：**一条 READY 的译文页可以在整个 unit 内保持空白，直到换页/换块/seek**
（`CaptionOverlayV2.java:936-943`、`:1006-1042`、`:1121-1129`、`:1185-1203`）。本诊断没有命中这条
路径（9 次 hard reject 都在 0.2 s 内恢复），但“任何模型、任何语种都不出现无译文”的不变量必须覆盖
它：排版失败要有**有界重试 + 尺寸回退**，不得把空白当作该页的终态缓存。`CaptionPageExecutor` 在队列
二次提交失败时会 `running.remove(key)` 且**永不回调**（:91-110），同样属于这一类。

---

## 7. 结构性修复（设计，不是补丁）

### 7.1 要建立的不变量

> **任意时刻 t，只要回复里存在覆盖 t 的译文，屏幕就必须显示译文（或它在时间上的连续持有），
> 不得因为“边界不一致”而空白。**

允许的例外只有：该处无语音（`non_speech`）、播放器被抑制（小窗/PiP/关闭）、用户关闭字幕或选择
“只显示原文”、以及回复**真正没有覆盖**的 token 段（空洞——先用补洞请求修复，修复失败才允许标记）。
回复整体不可用（JSON 截断、供应商拒绝）时无法投影，属于“极少数特殊情况”：先持有上一页，洞过去后
才标记，同时用更小的请求单元/流式降低其概率。

### 7.2 两层投影（新增 `RebuildCueProjection`，替换“cue 精确铺满”这一硬条件）

**第一层（首选，保持现状）**：回复的每条 cue 都被事件精确铺满 → 一 cue 一页，页窗 = cue 窗。

**第二层（保底，必须存在）**：无法按 cue 投影时，改用**事件即页**：

- 页 = 模型事件；页窗 = 事件源 token 的 `start..end`（已有精确源时间，`RebuildProtocol.Event` 已携带）；
- 事件跨 cue 不构成错误；页窗可以与 cue 窗不同，但必须按源时间有序、不重叠、不留空；
- 页面文本 = 事件文本，不切分、不复制；长页仍走现有 `RebuildPageLayout` 的行/页排版；
- 第二层是**纯投影**，不改变请求、不增加网络、不丢文本。

**洞（回复真正缺的 token 段）**：

- 只对“当前可见 cue 内的洞”发一次有界补请求（`fillHoles` 已有），窗口 = 该 cue；
- 洞未被补齐时，优先**持有上一页**到洞结束；只有在洞已经过去且仍无文本时才显示 `…`；
- `…` 不再是整条 cue 的默认表现。

### 7.3 发布单元改成“页”，不是“块”

- `s.plans[i]` 的接受不再要求“块内所有 cue 都成立”；只要回复通过**内容**校验（脚本、回声、时间、
  覆盖），就先生成**能生成的页**并发布（N44 的 partial 语义可以保留，但单位下沉到页）。
- 一条 cue 的失败不能清空其它 cue；`WAITING` 的占位只覆盖“尚未有页”的那条 cue。
- 缓存与 diagnostic 的身份按“页/源范围”记录，避免一条坏事件毁掉整块缓存。

### 7.4 重试语义

- 结构错误不再触发“整块重发”：先投影已经拿到的回复；只有“内容缺失”（真正的洞）才补请求。
- 补请求的目标是**具体洞的 token 范围**（已有 `holeBlock`），截止时间 = 该洞所属 cue 的结束；
  超过截止即放弃并保留上一页/标记。
- 删除“4/4 同错仍按 1.2/5/15 s 重发整块”的路径；它既不能收敛，也会拖到块结束之后。

### 7.5 提示词与校验

- `display_cues` 继续作为**偏好**（帮助模型对齐、提高第一层命中率），但不再作为拒绝理由；
- 删除/降级 `authored_cue_boundary`、`authored_cue_coverage`、`authored_cue_hole` 作为**硬错误**，
  改为对齐评分（诊断保留，便于观察模型对齐率）；
- `SENTENCE` 保持不变：它才是翻译质量的正向约束；模型应按句子翻译，由投影层负责落到作者分页。

### 7.6 代码落点（实施清单）

| 文件 | 改动 |
|---|---|
| `translation-core/.../RebuildCueContract.java` | `validate`/`units` 拆成“内容校验”与“投影”；新增 `project(source, block, events)` 返回 `Unit` 列表（第一层 cue 页，第二层事件页） |
| `translation-core/.../RebuildSalvage.java` | 不再要求 `unitsPartial` 非空；跨 cue/部分覆盖的事件进入第二层投影 |
| `translation-core/.../RebuildProtocol.java` | authored 的严格校验只保留内容规则；`parseBestEffort` 不再因边界抛错 |
| `translation-core/.../RebuildController.java` | `render()` 用投影结果选页；`WAITING` 占位只覆盖无页的 cue；`rejected()` 的块级重试改为洞级补请求 |
| `translation-core/.../RebuildReview.java` | `structuralRetry` 移除边界类错误；对齐率/第二层命中率进诊断 |
| `translation-core/.../RebuildGuidance.java` | 文案从“必须”改为“优先”，保留 `display_cues` 数据 |
| `ui/.../CaptionOverlayV2.java` | 首帧 `preparing_layout` 时若有上一页则 hold；排版失败要有界重试 + 尺寸回退，不得把空白缓存为该页终态（§6.8）；`CaptionPageExecutor` 丢任务时可见兜底 |

### 7.7 测试与验收门槛

1. **本诊断回归**：用 `probe` 的 107 token + 3 事件 + 4 cue 窗口，断言每条 cue 的 token 都有页且非空
   （本诊断实际为 3 个事件页；若回复与 cue 对齐则应为 4 个 cue 页）；`parseBestEffort` 不再抛
   `authored_cue_boundary`。
2. **性质测试（模型无关）**：随机生成 1,000 组切分（事件边界随机落在 cue 内部/边界/跨多条）、
   1,000 组 cue 时长（0.8–6 s）与语种（日/英/中/韩/俄/阿），断言“覆盖 t ⇒ t 有页”；不允许出现
   `source_gap` 落在有语音的 token 上。
3. **时延不变量**：给定任意回复，从 `plans[i]` 发布到首帧可见 ≤ 1 个主线程帧 + 排版 1 帧；网络不在
   显示路径上。
4. **真机验收**：同一视频同一段（FzRyrjFmZSQ 44.250–58.750 s）录屏，字幕从 44.25 s 起连续显示译文；
   诊断里 `authored_cue_boundary` 计数为 0；`translated text visible` ≥ 95% 的语音时间（扣除小窗/
   用户关闭）；`…` 只出现在回复真正未覆盖且补洞失败的 cue 上。
5. **不得回退**：N41 的“不做字符比例切分”仍由第一层保证（一 cue 一页，不切文本）；第二层的页界
   = 模型事件边界（句子），同样不做字符切分。第二层只在第一层不可用时启用。
6. **粘性空白**：模拟排版失败/执行器丢任务，断言 1 s 内重试或回退到更小字号，unit 内不得出现
   无恢复的空白（§6.8）。

### 7.8 明确不采用的“修复”

- 只放宽 `authored_cue_boundary` 检查：会让 `units()` 把跨 cue 事件硬塞进某条 cue，造成文本重复或
  缺页，是补丁。
- 只增加重试次数/缩短退避：4/4 同错 + 时延 ≈ cue 时长，原理上不保证。
- 只把块缩小到 1 cue：降低命中率但仍会在单条 cue 内切句时失败，且请求数 ×4；可以作为成本优化，
  不能作为不变量。

---

## 8. 未完成与边界

- 本轮未修改产品代码、未重建 MPP、未安装/写入手机；§7 是设计，不是已实现状态。
- 四条 cue 的**内部 token 切分**是推断的（cue 16 边界由诊断的 `display group 16 (268-285)` 直接给出，
  其余三条由窗口时长 + 文本切分推断）；探针已遍历所有与已知边界一致的四 cue 切法，结论不依赖具体推断。
- `…` 空洞（3.28 s）发生在日志起点之前接受的块 1 上，其补洞请求的细节不在本诊断窗口内；本报告只
  能确认它是一条“整 cue 空洞 + 未在窗口内补齐”的实例。
- 原生字幕的绘制抑制（`suppressNativeDraw` / alpha mask）不在本诊断的可观测范围；本轮已确认译文的
  显示与 native renderer 扫描无关（overlay 挂在 `android.R.id.content`，扫描只影响原生字幕遮罩），
  因此 `NATIVE_RENDERER_SCAN_SUMMARY found=false` 不是本次“不显示”的原因。
- 模型侧语义质量、个别 cue 的译文好坏不在本轮结论内；本轮只回答“有译文却不上屏”。

---

## 9. 一分钟复现（给下一位维护者）

1. 打开 `validation/runs/20261020-n51-no-translation-root-cause/probe-output.txt`：看到
   `STRICT -> authored_cue_boundary`、`SALVAGE plan=null`、`COVERAGE 107/107`、`trials=1894 /
   accepted=28 / elsewhere=0`。
2. 把探针复制进 `translation-core/src/test/...` 运行（§6.2），确认 3 tests 全绿。
3. 在真机上选一个**人工字幕**视频，找一条 cue 边界切在句子中间的段落（本诊断是 `FzRyrjFmZSQ`
   44.250–58.750 s）；当前构建必然出现整块“翻译中…”且诊断出现
   `authored_cue_boundary; range=…; crosses display group …`——这就是本报告的复现条件。
