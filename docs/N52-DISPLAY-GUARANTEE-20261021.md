# N52 — “不显示译文字幕”的一般机制与显示保证（2026-10-21 1.5.5 诊断）

> 输入：`D:\HONOR Share\Honor Share\caption-diagnostics-1.5.5-20261021-201213.txt`
> （7,806,332 B，SHA-256 `FE33D6645265F409FE0B4FC9BF23B81DF664368D8EBE0315E7770B5D2B50C7FA`，
> 导出于 2026-10-21 20:12:13）。
>
> 状态：根因调查 + **通用修复已实施**（1.5.5，`captionBuildId=release-1.5.5`）。
> 证据与审计脚本：`validation/runs/20261021-n52-display-guarantee/`。

---

## 0. 一句话结论

N51 修好的是**一道**闸门（人工 cue 边界）；这份诊断证明“不显示译文”是一个**闸门架构**问题：

> 字幕框显示什么，长期是由“当前块的计划”推导出来的——块请求、解析、发布、显示单元、排版、页窗、
> 原生遮罩，每一层都能独立地把字幕变成空白或占位。只要“块”仍是显示单位，换一个模型、语种、
> 句子或视频，同样的现象就会从**另一道闸门**冒出来。

因此通用解法不是继续修某一道闸门，而是把显示改成**时间线驱动 + 单一兜底梯度**：

> **任意一个“源字幕有语音”的瞬间，字幕框都必须有内容；**
> 有译文页 → 显示；没有 → 保持上一页（有界）；再没有 → 中性占位；
> 同时对“当前这一页”发一个**页大小**的补请求，拿到即显示。

本轮已把这条不变量写进渲染路径，并补齐“页级补请求”和“空回复不再判整块失败”。

---

## 1. 输入与版本证据

| 项目 | 值 |
|---|---|
| 版本 | `ui=1.5.5; engine=1.5.5; build=local-1.5.5`（1.5.5 N51 构建） |
| 记录 | 17,327 条带时间戳记录；1,079 条 `REBUILD_PRESENTED`；14 个会话；16 次视频切换 |
| API | 177 attempts / 171 2xx / 7 cancelled；结构接受 161 / 拒绝 10 |
| 观看 | 102 s（当前视频）；历史覆盖约 7.8 h |
| 人工/自动 | 189 次 `REBUILD_UNIT_MODE authored=true`（N51 投影在跑），36 次 `authored=false` |

**N51 已生效的证据**：`REBUILD_CUE_PROJECTION` 189 条，且出现混合层级
（如 `groups=4;cue_pages=2;event_pages=1;events=3`）——跨 cue 的句子已按事件页发布，
不再整块拒绝。所以本轮的问题不是 N51 的回归，而是**其他的闸门**。

---

## 2. 审计方法（可重复）

脚本：`validation/runs/20261021-n52-display-guarantee/`（`overview.py`、`sessions.py`、
`playback_audit.py`、`timeline.py`）。

1. 取所有 `REBUILD_PRESENTED`，按 `id=<session>:...` 分会话。
2. **只统计真实播放**：相邻两条记录满足 `0.4 ≤ Δposition/Δwall ≤ 1.6` 才计入；暂停/缓冲/后台
   不计（否则会把“人离开 7 小时”算成无字幕）。
3. 把状态分为：译文字幕、占位“翻译中…”、`…` 标记、空白（按 `mode/reason`）、保持上一页。
4. 对空白再区分：该时刻源字幕**有没有 cue**（`windowAt`）——没有 cue 是**合法静默**，
   有 cue 才是“该显示却没显示”。

结果（播放中的秒数）：

| session | 播放 | 译文 | 空白 | 占位 | `…` |
|---:|---:|---:|---:|---:|---:|
| 19 | 60.5 | 52.1 | 0.0 | **8.4** | 0.0 |
| 106 | 100.0 | 62.4 | **33.2** | 4.4 | 0.0 |
| 200 | 61.7 | 52.6 | **9.0** | 0.0 | 0.0 |
| 94 | 33.0 | 30.8 | 0.2 | 2.1 | 0.0 |
| 46 | 18.0 | 15.1 | 1.5 | 1.4 | 0.0 |
| 9 | 12.9 | 11.5 | 1.2 | 0.2 | 0.0 |

> 说明：session 106 的 33.2 s“空白”里绝大部分是**源字幕本身没有 cue** 的片段
> （例如 position 5898 起 14.45 s、22407 起 5.16 s、29537 起 3.96 s、35513 起 3.65 s；
> 该视频是 Shorts，整条轨道只有 25 个 token，块与块之间本来就没有字幕）。
> session 200 的 9.0 s 是视频开头 0–7 s 没有源 cue + 首帧排版。这些**不是**缺陷。

真正的“有语音却没译文”集中在 §3 的 G1–G5。

---

## 3. 本诊断里仍在的闸门（逐条证据）

### G1 空译文 → 整块失败/放弃

- 10 次 `REBUILD_EVENTS_REJECTED reason=empty_translation`，全部在 session 106：
  block 1（request 109/111/114/115/117）与 block 5（request 118–122），各 5 次；
  随后 `REBUILD_BLOCK_ABANDONED session=106;block=5;slow_retries=3`。
- 机制：严格解析对“有语音但 text 为空”的事件抛 `empty_translation`；salvage 丢弃该事件后，
  若整块没有可用事件（或覆盖率 < 1/3）就返回 null，于是整块按结构失败重试，最后放弃；
  该块覆盖的 2–3 条 cue 在重试期间只有占位，放弃后只有 `…`。
- 这不是某个模型的特殊错误：任何模型都可能对一小段（音乐、语气词、说话人标记、
  被截断的短块）返回空文本；而当前实现把“这一小段没有译文”放大成“整个块没有译文”。

### G2 salvage 空洞 → `…`

- session 200：`REBUILD_PARTIAL_ACCEPTED block=2;events=3;holes=2;covered=124/153`，
  其中 hole `357-368` 在 56.58–57.56 s 显示为 `…`（1.92 s）。
- 机制：部分计划对未覆盖的 token 段显示中性 `…`；补洞只对“最大洞”发一次，
  且只在接受时触发，不保证在当前页到达前补齐。

### G3 块未就绪 → 占位（快速拖动时尤其明显）

- session 19：`placeholder` 两段 **3.70 s + 3.59 s**，窗口正是 block 21/29 的 cue；
  同一时间用户在连续 seek（每 ~1 s 一次 `REBUILD_SEEK`），focus 请求 31/32/33/34 的
  往返 2.2–3.0 s；request 31 还被下一次 seek `cancelled`。
- 机制：页的显示完全等整块回复；一次请求的往返（2–3 s）≈ 一条 cue 的寿命，
  拖动后必然出现“页已到、译文未到”的占位。

### G4 没有 unit → 空白

- 同一份计划内，位置落在所有 unit 之外（估值时间缝、页窗切换）时 `render` 走 `source_gap`
  并 `hide`；即使下一帧就有页，也会先清屏。
- 本诊断里 `source_gap` 的多数实例是“源字幕没有 cue”（合法静默），但机制本身不区分
  “静默”与“计划没覆盖到”，所以任何估计误差、边界 rounding、块间空档都会清屏。

### G5 overlay 自身缝隙

- `overflow_status/preparing_layout` 0.2 s、`outside_owned_window` 0.1 s、
  `owner_invalid` 0.15 s；单次很小，但会与 G3/G4 叠加，把一次断流变成多次闪烁。

### G6 原生字幕遮罩（本诊断无法证实，仍需真机复核）

- `NATIVE_RENDERER_SCAN_SUMMARY` 632 次、`VIEW_NOT_FOUND` 418 次、`VIEW_MASKED` 10 次、
  `MASK_FALLBACK` 13 次（“Source track not ready…falling back to invisible ownership track”）。
- 译文是否被 YouTube 原生字幕盖住/替换，取决于 patch 里 `SubtitleWindowView.draw` 的
  `suppressNativeDraw()` 是否覆盖实际渲染类；诊断只记录遮罩辅助路径，不能证明屏幕像素。
  本轮把它列为**独立的待验证项**，不混入上文的“计划/页”根因。

---

## 4. 一般根因

**单位错位（unit mismatch）**：

| 环节 | 单位 |
|---|---|
| 请求 / 重试 / 放弃 / 缓存 | 一个块（自动轨最多 160 token / 30 s；人工轨最多 4 cue / 15 s） |
| 观众看到的 | 一页（2–5 s） |
| 失败的影响面 | 整个块（最多 ~30 s 的页） |
| 空洞/空译/无 unit | 单页，但显示层没有“保留上一页/立即补这一页”的路径 |

显示函数长期是 `text = f(plan, unit, layout, window)`；缺少的是
`text = f(source time, available translations)` 这一层。N51 只是把 `f` 里“cue 必须精确对齐”
这一项去掉了；G1–G5 每一项仍然可以独立让 `text` 为空。

**因此正确的修复层次不是“再修一个条件”，而是引入一条与内容无关的保证：**

> 只要 `speechAt(t)` 为真，渲染结果就不能是空；没有页时，按“保持上一页 → 占位 →
> 页级补请求”的固定梯度处理。

---

## 5. 通用修复（已实施）

### 5.1 时间线保证（`RebuildController.render()`）

在每个渲染帧，若当前文本为空或是 `…`（或块仍在等待）且 `speechAt(t)`：

1. 若有“本代已显示过的上一页”且离它的页尾 < `DISPLAY_HOLD_MS`（4 s）→ **保持上一页**
   （页窗按 500 ms 量子向后延伸，保证 overlay 不会因页窗结束而隐藏）；
2. 否则显示中性占位“翻译中…”，窗口 = 当前源 cue；
3. 同时调用 `requestPageRepair(...)`（见 5.2）；
4. 记录 `DISPLAY_GAP`（`gate=hold|placeholder|marker|repair`，约 1 s 限流）。

这是一条**与文本、模型、语种、切分无关**的分支：它只看“时间线上有没有语音、当前有没有页”。

### 5.2 页级补请求（`requestPageRepair` / `repairPage`）

- 触发：上述保证分支；范围 = **当前这一页**（人工轨 = 一个 cue group；自动轨 = `windowAt(t)` 的源窗口）；
- 去重：`generation + block + from-to`，每块最多 4 次；`pageRepairs` 在 seek 时清空；
- 让位：块请求仍在途（`s.jobs[i] != null`）、已有流式前缀、会话已 terminal 时不发；
- 宽限：块没有计划时先等 `PAGE_REPAIR_GRACE_MS=1200`，让块回复优先；块已失败/有空洞则立即发；
- 合并：请求返回后 `RebuildReview.mergeInto` 并入该块计划；若块完全没有计划，先发布一个
  **全空洞 degraded 计划**（`RebuildProtocol.degraded`），再合并；
- 结果立即 `render`，并记录 `REBUILD_PAGE_REPAIRED` / `REBUILD_PAGE_REPAIR_FAILED` /
  `REBUILD_PAGE_REPAIR_EMPTY` / `REBUILD_BLOCK_DEGRADED`。

这样，即使“整块回复不可用”，观众所在的**这一页**仍然会单独被请求、单独上屏。

### 5.3 空/不可用回复不再判整块失败

- `RebuildSalvage`：当回复结构合法但没有任何可用事件时，返回**全空洞 partial 计划**
  （`events=[]; holes=[block.from..block.to]`），不再返回 null；
- 删除非人工轨“覆盖 < 1/3 就返回 null”的整块失败规则；
- 效果：不会再把“一小段空译”放大成 5 次整块重试 + `BLOCK_ABANDONED`；
  该块直接进入页级补请求路径；`…`/保持上一页只覆盖真正没补上的页。

### 5.4 与 N51 的关系

显示顺序不变：**N51 投影优先**（cue 页 / 事件页）；只有投影也产不出当前页时，才进入 5.1/5.2 的
保证层。两层不会重复：投影的页由计划事件构成，保证层只在“没有页”时介入。

---

## 6. 为什么这是通用的（不是针对某一句）

1. **触发条件**只看源时间线（`speechAt`），不看文本内容、模型、语种、cue/事件切分。
2. **修复手段**是页大小的请求（cue group 或 source window），与块大小无关；
   块可以是 3 token 的 Shorts 块，也可以是 160 token / 30 s 的自动轨块。
3. **兜底**是“保持上一页”或“占位”，不依赖任何模型输出；即使 provider 抖动，
   屏幕也不会变成空白。
4. **测试**用“空回复”“HTTP 500”“随机源”构造，而不是某个句子：
   `N52DisplayRepairTest`（真实引擎 + MockWebServer）验证空回复与整块 500 都会在页级补请求后
   上屏；`LanguageConformanceTest.anUntranslatedEchoIsStillDropped` 改为“不回显、全空洞”，
   语言无关。

---

## 7. 验证与产物

| 项目 | 结果 |
|---|---|
| `:translation-core:testDebugUnitTest` | **246 tests**，0 failures / 0 errors（最终树全量运行；构建前后的同类全量亦均通过） |
| `:extensions:extension:testDebugUnitTest` | **264 tests**，0 failures / 0 errors |
| `tools/check_localization.py` | 243 keys × 14 locales 通过 |
| `tools/verify_integration.py` | `errors=[]`、pinned prompt/protocol 不变、core class 79、内嵌 MPE == 独立 MPE |
| MPP（1.5.5，`release-1.5.5`） | `build/n39/gradle/patches/libs/patches-1.5.5.mpp`，**595,638 B**，SHA-256 `A4B04C537499F6D86DF99350297BBC2FBEB5DD99C835F140D9C2DFA314771E4E` |
| 已发布 1.5.5 资产 | 原样保留：`validation/runs/20261021-n52-display-guarantee/patches-1.5.5-release.mpp`（593,495 B，SHA-256 `CB4D3135B9B483DAB1B098ACDEF720F612FD788BEA44B8E4C9229C14A35ACCFB`） |

版本仍为 **1.5.5**（按你的要求）；`captionBuildId` 使用 `release-1.5.5`，
诊断里可凭 `build=` 区分本次构建与已发布资产。

---

## 8. 边界（“极少数特殊情况”）

- 供应商完全不可用、或模型对同一页持续返回空/错误：页级补请求上限 4 次后，
  保持上一页（≤4 s）→ 占位/`…`；不会空白，但也不会凭空生成译文。
- 源轨道本身没有 cue 的片段（音乐、静默、Shorts 稀疏轨道）不显示字幕——这是正确语义。
- 原生字幕遮罩（G6）仍需真机录屏确认；本轮没有改 patch 的 draw hook。
- 页级补请求只在“未覆盖的语音页”发生；正常预取路径不增加请求。
- 未连接/写入手机；after 诊断待你复验。

---

## 9. 复现与验收

1. 安装 `patches-1.5.5.mpp`（`build=release-1.5.5`）。
2. 打开自动字幕视频，播放/快速拖动，制造“块未就绪”的场景；预期：字幕框始终有内容
   （上一页或“翻译中…”），不会空白；页级补请求到达后显示该页译文。
3. 导出诊断，预期新增记录：
   - `DISPLAY_GAP ...;gate=hold|placeholder|marker|repair`（每约 1 s 一条）；
   - `REBUILD_PAGE_REPAIRED session=..;block=..;range=from-to;events=N`；
   - 不再出现成串的 `REBUILD_EVENTS_REJECTED reason=empty_translation` +
     `REBUILD_BLOCK_ABANDONED`。
4. 验收标准：播放中的“有源 cue 且无任何字幕内容”的秒数为 0；`…` 只出现在补请求失败的页；
   空白只出现在源轨道没有 cue 的静默段。

---

## 10. 证据文件

- `time-budget.txt`（本诊断全量状态预算）、`playback_audit.txt`（播放感知审计）、
  `sessions.txt`（分会话）、`timeline.txt`（session 200）、`session106.txt`、`session19.txt`
- 审计脚本：`overview.py`、`sessions.py`、`playback_audit.py`、`timeline.py`
- 已发布资产副本：`patches-1.5.5-release.mpp`
