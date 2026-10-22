# N55 字幕框动画根因、统一方案与 S1/S2 实现（2026-10-22）

> **历史说明：** 本文件 §5.5 的原生 `SubtitleWindowView` 几何/安全线实现已被
> [`docs/N56-CAPTION-MOTION-FOLLOWUP-20261022.md`](N56-CAPTION-MOTION-FOLLOWUP-20261022.md)
> 移除（用户取消 UI 避让，并修复 margin/translation 混用导致的闪跳）；当前行为以 N56 为准。
>
> 状态：根因分析 + S1/S2 最小实现（工作树，`captionBuildId=local-1.5.5-n55`）；未签名、未连接真机、未发布。
>
> 输入：N53 工作树、用户描述的三类现象、`docs/N53-CAPTION-LAYER-AND-SIZE-20261022.md`、`docs/N54-NATIVE-CAPTION-STYLE-AND-PAGING-EVALUATION-20261022.md`、YouTube 21.20.405 baksmali dump。
>
> 证据：`validation/runs/20261022-n55-motion-root-cause/`（探针源码、JUnit XML、composition 结构、patched-dex hook 摘录、MPP 构建输出）。

## 0. 结论先行

1. **现象不是“动画曲线不对”，而是位置更新被绑在内容排版上。** `CaptionOverlay` 把视频矩形的每一次变化当成一次“新的稳定排版场景”；位置 `placeVisibleText()` 只是排版完成后的副作用。
2. **三个现象是同一套机制的不同投影：**
   - 评论区展开/收起：视频矩形连续变化 → 100 ms 采样一次 → 每个采样宽度都是一个新分页 key → 旧页被 hold、位置不更新 → 新计划落地时一次跳过去；中间计划若先完成，就会先跳一小步，形成“抽搐几下再转移”。
   - 横屏视频拖动：同上；另外 `WATCH` 在文本请求布局时返回 `false`，会取消整窗这一帧的绘制；播放器转场还会触发宿主搜索/重挂载。
   - 进度条：没有“安全区”概念，只有视频矩形的固定比例（`.80/.82/.72`）；原生字幕层 `Laohf`/`Lapdn` 的位置契约完全没被使用。
3. **有统一、优雅、可行的方案**：把“几何”和“内容”拆成两个时钟。
   - **几何通道**：每帧读原生字幕窗口（或回退视频矩形）的位置，只做 `translationX/Y`；不测量、不 `setText`、不 `setLayoutParams`。
   - **内容通道**：几何停稳 120–200 ms 后才生成新的分页 key；动画期间保留当前页，计划就绪后一次性换页。
   - **原生字幕层同时是安全区权威**：它的窗口矩形已经由 YouTube 自己的布局决定；跟随它即可获得与原生一致的位置（包括原生对控制条/进度条的处理）。若真机证明原生在该模式本身也不避让，再在 S2 补一个控制条 inset，而不是先假定原生总是正确。
4. **工作量**：核心 S1+S2 约 **5–8 个工作日**；含 S0 spike 和 S3 真机/回归共 **10–16 个工作日**（约 2–3 周）。N54 的原生渲染器迁移是可选 Stage 4，另计 2–3 周。无需改翻译、分页语义、缓存协议或资源文件。
5. **这不是“加一个动画”能修好的问题。** 若只把 100 ms 改成 16 ms，而不拆内容通道，会把重排次数提高约 6 倍，抖动更严重。
6. **本轮已按 S1/S2 实现最小版本**（见 §5.4）：每帧 transform、停稳分页、`WATCH` 恒 true、宿主保留迁移、原生字幕窗口安全线。未做真机验收与 N54 A1 原生渲染器。

## 1. 现象与当前代码路径

### 1.1 一条字幕从时间到屏幕的路径

- `CaptionOverlay.WATCH`（`ui/.../CaptionOverlayV2.java:145-155`）挂在视频宿主 `FrameLayout` 的 `ViewTreeObserver` 上。它做两件事：
  1. `advancePreparedPage()`：只在页索引变化时换页和重放位置；
  2. 距离上次布局 ≥ 100 ms 时调用 `render()`。
- `render()`（`:898-1193`）在视频矩形 `b` 变化时进入完整布局：
  - 由 `b.width()` 计算盒子宽度 `inner`（`:991-994`）；
  - 用 `inner + 字号 + 文本 + 时间 + 字体` 组成 `currentPlan()` 的 key（`:792-838`）；
  - `CaptionPageExecutor` 在后台重新分页（`presentation/.../CaptionPageExecutor.java:20-111`）；
  - 分页未就绪时，如果屏幕上还有旧页，`holdingLayout()` 最多 hold 1200 ms（`:1013-1021`、`:778-787`）；
  - 分页就绪后，`applyVisibleText()` 设置文本/宽度（`:1042-1058`），再 `placeVisibleText()` 设置 margin（`:1156`、`:1351-1371`）。
- `placeVisibleText()` 的 y 坐标只有固定比例：
  - 横屏 `0.80`、竖屏 `0.82`、Shorts `0.72`（`infrastructure/.../DeepSeekConfig.java:215-236`）；
  - 没有控制条、进度条、手势区或评论面板的 inset 输入。
- 宿主来自 `CaptionSurface.captionHost()`（`application-host/.../CaptionSurface.java:282-289`）：从视频 Surface 向上找第一个 `FrameLayout`。这个选择是启发式的，不是 YouTube 原生字幕层的父容器。

### 1.2 为什么评论区展开会“抽搐”

把评论区展开看成一段 300–500 ms 的连续几何动画：

1. 视频矩形每帧变化，但 `WATCH` 只每 100 ms 调一次 `render()`，所以几何只被采样 3–5 次，不是 60 次。
2. 每次采样得到新的 `b.width()` → 新的 `inner` → 新的 `currentPlan()` key。`CaptionPageExecutor` 只有 1 个工作线程、2 个等待槽；运行中的旧计划不能取消，新计划会替换等待队列里最旧的计划。
3. 旧页还在屏幕上，`holdingLayout()` 为 true，`render()` 在 `:1013-1021` 直接返回；**这一步之前没有调用 `placeVisibleText()`**。所以盒子的 margin 仍是动画开始时的值，字幕不跟视频走。
4. 如果某个中间宽度的计划在下一个 100 ms 采样前完成，它的回调 `currentPlan()`（`:826-836`）会通过 `key.equals(desiredLayout)` 检查并调用 `render()`；此时宽度还没变，计划被接受 → 盒子在中间位置落一次。下一个采样又重复。于是用户看到“抽搐几下”。
5. 动画结束、最终宽度的计划就绪后，`render()` 才第一次调用 `placeVisibleText()`；盒子从旧位置直接跳到最终位置。这就是“然后转移过去”。

探针（受控 Robolectric，非真机）复现了第 2、3 步和 `WATCH` 取消绘制的行为：

| 探针 | 结果 | 说明 |
|---|---|---|
| 连续 9 个视频宽度（600 → 420）各调用一次 `render()` | `desiredLayout` 产生 **10 个不同 key** | 每个几何采样都是新的分页身份 |
| 同上 5 个宽度，分页未落地时读 `anchor` margin | 5 帧全部保持 `24,240`，最终计划就绪后才变化 | 位置被内容阻塞：hold 然后一次跳 |
| 文本请求布局时直接调用 `WATCH.onPreDraw()` | 返回 `false`（`layoutRequested=true; anchorVisible=true`） | 该帧整窗绘制被取消 |

证据：`validation/runs/20261022-n55-motion-root-cause/probe-results.xml` 中的 `PROBE generations=...`、`PROBE old_margin=...`、`PROBE predraw allowed=false ...`。

### 1.3 为什么横屏拖动会“反复抽搐”

横屏详情页长按上拖进入全屏（以及反向）是更长的连续变换，叠加了三个额外因素：

1. 同样的 100 ms 采样 + 新分页 key + hold；拖动时间越长，中间计划落地的次数越多。
2. `WATCH` 的返回值：
   ```java
   return !anchorVisible() || pending == null || !pending.isLayoutRequested();
   ```
   只要字幕 `TextView` 还有布局请求，就返回 `false`。`OnPreDrawListener` 返回 `false` 会取消**整个窗口**这一帧的绘制，而不只是字幕。拖动过程中 `setText`/`setLayoutParams` 越频繁，跳帧越多。
3. 宿主搜索/重挂载：
   - `CaptionSurface.refresh()` 在 `geometryInvalid` 或缓存不可见时重新选播放器（`:116-155`）；
   - 任何布局变化都会让 `GEOMETRY_LAYOUT` 置 `geometryInvalid`（`:42-45`）；
   - `setPlayerType()` 还会主动 `invalidateGeometry()`（`CaptionOverlayV2.java:455-479`）；
   - `attach()` 发现宿主变化时先 `detach()`、再新建一个 `anchor`/`TextView`（`:600-701`），新 anchor 初始 `GONE`，下一帧才显示。
   N53 已经记录过这个风险：“播放器视图被整体重建时会有一次重新挂载……若真机出现可见闪烁，应改为保留旧 anchor 平移而不是重建。”（`docs/N53-CAPTION-LAYER-AND-SIZE-20261022.md:173-174`）

这三项不改变“字幕语义”，但都会让可见盒子在动画期间反复消失/重排/跳位。

### 1.4 为什么不像原生字幕一样避让进度条

当前实现没有任何“安全区”输入：

- y 是视频矩形高度的固定比例，x 是视频矩形中心；
- `CaptionSurface.videoBounds()` 返回的是最大可见 Surface/播放器矩形（`CaptionSurface.java:306-310`），可能包含黑边/letterbox，不等于实际画面内容或控制条上沿；
- 没有读取 seekbar、播放器控制层、手势区或评论面板的位置；
- 用户拖动只改这个固定比例（`CaptionOverlayV2.java:1548-1586`），不会在控制条出现时临时上移。

YouTube 原生字幕不是这套逻辑：

- `SubtitleWindowView` 是公开 `ViewGroup`，`Laohf` 是公开的字幕窗口容器；原生 `Laohf.onLayout` 用 `Lapdn` 的位置标志/百分比，把每个 `SubtitleWindowView` `layout()` 到容器内（21.20.405 dump `classes2.txt`，`Laohf.onLayout`/`Laohf.k`）。
- `Lapdn.a` 默认值是 `(34, 50, 95, true, false)`：`34 = 32|2`（底部 + 水平居中），宽度 50%，底部 95%。这不是我们用的“中心 80%/82%”。
- `Lmwq`（原生字幕控制器）会调用 `Laohc.N(int)`、`Laohc.M(Lapdu)`、`Laohc.jU(float)` 和 `Laohc.jW(List)`；`Laohf.N(I)` 写入的维度参与 `onLayout` 的 offset 计算。原生字幕的“位置/边距/字号比例”是一个独立契约。
- 现有补丁只重写 `SubtitleWindowView.draw` 来屏蔽原生绘制（`patches/.../NativeCaptionBridgePatch.kt:628-647`），**没有阻止原生 view 的测量和布局**（N54 §1）。因此原生窗口的矩形在运行时是可读的，这是最现成的几何/安全区权威。

我们的自定义框完全绕过了这个契约；所以“不避让进度条”不是某个数值没调对，而是根本没有这条数据通路。

## 2. 根因归纳

按影响排序：

### A. 几何采样是 10 Hz，而且每个采样都触发一次全量内容排版

位置更新率只有 100 ms 一次；每次更新又进入 `inner → key → 后台分页 → 文本/宽度 → margin` 的完整链路。任何连续动画都会被离散成几次“重新排版”。

### B. 位置是排版的副作用，不是独立的 transform

`placeVisibleText()` 只在 `render()` 的末尾或 `advancePreparedPage()` 换页时调用；分页 pending 时 `render()` 在 hold 分支提前返回，位置完全不更新。于是“内容没准备好”会阻塞“盒子跟随视频”，这是“抽搐后转移”的直接原因。补刀：`advancePreparedPage()` 换页时用的是 `previous`（上一次 `render()` 的矩形，`:876-882`），如果换页恰好发生在动画中，新页会被放到旧几何上，再跳一次。

### C. 每个宽度都是新的分页身份，没有“运动中”和“已停稳”的区分

`currentPlan()` 的 key 包含精确的 `budget.width`；动画中每个采样宽度都 miss 缓存、排队新计划。`CaptionPageExecutor` 只有 2 个等待槽，运行中的计划不可取消；中间计划只要在下一个采样前完成，就会被接受并落地。结果既浪费 CPU，又制造多次中间跳位。

### D. 没有原生安全区/位置契约

固定比例 + 视频矩形，没有控制条/进度条/letterbox 输入；原生 `Laohf`/`Lapdn` 的布局结果没有被读取。这是进度条问题的根因。

### E. 次级放大器

- `WATCH` 在文本有布局请求时返回 `false`，取消整窗绘制（全局跳帧，不只字幕）。
- 宿主启发式搜索 + 重挂载会重建 `anchor`/`TextView`（N53 已标记）。
- `CaptionPlayerAuthority` 把任何包含 `MINIMIZED`/`DISMISSED` 的 PlayerType 当 compact（`:284-293`）；`WATCH_WHILE_SLIDING_MINIMIZED_MAXIMIZED` 会命中 `MINIMIZED`。`setPlayerType()` 在 compact 时 `hideView()`，恢复时等 `TRANSITIONING_TO_REGULAR` 探针（`:455-479`、`CaptionPlayerTransitionGuard`）。`WATCH_WHILE_SLIDING_MAXIMIZED_FULLSCREEN` 不命中 compact，所以这不是详情↔全屏拖动的主因；但如果某条设备/版本路径发出 `..._MINIMIZED_MAXIMIZED`，它会额外造成隐藏/恢复。需要真机 `PLAYER_TYPE` 序列确认，不能先假定。

## 3. 为什么现有回归没抓到

- `CaptionLayerOverlayTest` 只做“宽视频 → 窄视频”两次静态布局，然后 `awaitPlan(innerWidth)` 等最终计划；它验证字号和分页结果，不验证中间帧位置。
- `CaptionContinuityTest` 验证“页切换不空白”，但它也等计划就绪后再断言，不检查动画中是否每帧跟随。
- 没有测试断言：
  - 一次几何动画期间 `desiredLayout` 不增加；
  - 分页 pending 时 `placeVisibleText()` 仍每帧更新 transform；
  - `WATCH` 永远返回 `true`；
  - 宿主变化不重建 anchor；
  - 盒子底边不超过原生安全线。
- 现有测试全绿并不矛盾：它们测的是“最终状态正确”，而用户看到的是“过渡状态”。

## 4. 统一方案：几何通道 + 内容通道 + 原生几何权威

### 4.1 三条不变量

- **I1：几何变化永远不使内容失效；内容未就绪永远不阻塞几何。**
- **I2：只有一个几何权威（原生字幕窗口，回退视频矩形）和一个内容权威（分页计划）。**
- **I3：除非内容 key 真的变化，否则屏幕上的变化只能是一次纯 transform（平移/缩放），不能是重排。**

这三条同时覆盖评论区、拖动、进度条三个现象，不需要为每个现象写特判。

### 4.2 几何通道（每帧，常数时间）

新增一个 `CaptionGeometry`/`CaptionPlacement` 小对象：

- 每帧（`Choreographer` 或 `OnPreDraw`，不再 100 ms 节流）读取：
  - 原生 `SubtitleWindowView` 的当前矩形（若可见/有最后一次布局）；
  - 其父 `Laohf`/`FrameLayout` 的矩形；
  - 回退：`CaptionSurface.videoBounds(host)`。
- 计算：
  - `centerX`：优先原生窗口中心，回退视频中心；
  - `safeBottom`：优先原生窗口底边，回退视频底边减去控制条安全 inset；
  - 用户拖动的位置作为**相对安全线的偏移**，并 clamp 在 `[顶部安全线, safeBottom]`。
- 应用：
  - 只改 `anchor.setTranslationX/Y`（或 `offsetTopAndBottom`）；
  - 绝不 `setLayoutParams`、`setText`、`measure`、`bringToFront`；
  - 参数值没变化就不调用，避免无谓 invalidate。
- 宿主：
  - 优先把 anchor 挂到原生 `Laohf` 的父 `FrameLayout`（与原生字幕同一坐标系、同一 z 序：视频之上、控制条/评论之下）；
  - 原生层不存在时回退当前 `captionHost()`；
  - 宿主变化时**保留旧 anchor 和文本**，只做一次无动画的重新挂载，不重建 `TextView`、不置 `GONE`。

这样评论区展开/收起、拖动、控制条出现时，盒子每帧跟着原生窗口走；位置不再依赖分页是否完成。

### 4.3 内容通道（settled，异步）

- 维护一个 `contentKey`，只包含**稳定**维度：
  - 模式（Shorts / 详情 / 全屏 / 评论区展开态）；
  - 字号 tier 和 target spec；
  - **settled** 的内层宽度（带 4–8 dp 滞回/桶化，而不是精确像素）；
  - 当前页索引/文本。
- 运动检测：
  - 若几何在上一次采样与本次之间变化超过 1 px，或距离上次变化 < 150 ms，视为“运动中”；
  - 运动中不生成新 `contentKey`、不请求新分页；保留当前页文本和盒子尺寸，只走几何通道。
- 停稳后：
  - 用 settled 宽度请求一次分页（缓存命中则直接用）；
  - 计划就绪后一次性换文本/宽度；换页时盒子位置由几何通道保持，不空白、不闪；
  - 若当前页在新宽度下仍能排进两行，优先保留当前页，只重排后续页（“页 fit 保留”），进一步减少无谓翻页。
- 现有 `holdingLayout()`/`END_HOLD_MS` 保留为兜底，但不再是位置更新的前置条件。

### 4.4 原生安全区/进度条

- 读取原生 `SubtitleWindowView` 的底边作为 `safeBottom`；原生窗口由 YouTube 自己布局，控制条出现/消失时它怎么动，我们就怎么动。
- 原生窗口不可见（cue 间隙、Shorts 分支、原生层未建立）时：
  - 用最后一次已知矩形；
  - 仍无则回退视频矩形减控制条 inset（可从播放器 overlay 的 padding/seekbar 可见矩形读取，或使用一个保守的 dp 常量）。
- 用户拖动仍保存为相对安全线的比例，不再保存“视频高度的绝对比例”；这样控制条出现时不会把用户位置解释成另一个绝对点。
- Shorts 单独一条安全线（底部 UI/手势区），不与横屏共用 `.72` 常量。

### 4.5 转场与权限

- `WATCH` 永远返回 `true`；只跳过字幕自身的工作，不取消整窗绘制。
- `WATCH_WHILE_SLIDING_MAXIMIZED_FULLSCREEN` 等非 compact 的 sliding 类型不撤销显示；只触发一次几何重读。
- 只有 `COMPACT`/`CLOSED`/owner 失效才隐藏；`TRANSITIONING_TO_REGULAR` 的探针只决定“新几何是否可信”，不决定“是否空白”。几何未可信时保留上一帧 transform，而不是 `GONE`。
- `setPlayerType()` 不再 `invalidateGeometry()` 后立刻全量重排；只标记几何 dirty，由几何通道下一帧读取。

### 4.6 数据流

```
vsync / pre-draw
  ├─ geometry: native window rect (or video rect) ──> anchor translation      (每帧)
  └─ content clock:
        moving?  ── yes ──> keep current page, no plan
                    no  ──> settled width bucket ──> page plan (async)
                                          └─ ready ──> swap text once
```

### 4.7 为什么它能同时解决三个现象

| 现象 | 现机制 | 新机制 |
|---|---|---|
| 评论区展开抽搐后转移 | 10 Hz + 新 key + hold + 最后跳 | 每帧 transform；只在停稳后换一次页 |
| 横屏拖动反复抽搐 | 10 Hz + 新 key + 跳帧 + 重挂载 | 每帧 transform；`WATCH` 不取消；宿主不重建 |
| 不避让进度条 | 固定比例 | 跟随原生字幕窗口底边/安全区 |

## 5. 可行性、工作量与风险

### 5.1 可行性：高

现有代码已经有可用的接缝：

- `WATCH` 已经是每帧回调，只是被 100 ms 节流和 `render()` 的全量排版占用了；拆成“几何每帧、内容按需”是局部重构。
- `placeVisibleText()` 已经是唯一的放置入口；把 margin 改成 transform 不需要改分页。
- `CaptionPageExecutor` 已经是异步、可缓存、可合并的；只需把 key 换成 settled 宽度，并在运动时停止请求。
- `CaptionSurface` 已经能给出宿主内坐标（含矩阵映射）；原生窗口矩形可以用同一套 `relative()` 映射。
- 原生 `SubtitleWindowView`/`Laohf` 已经被补丁定位，且只屏蔽 draw、不阻止 layout（N54）；新增一个只读 hook 即可捕获实例。

### 5.2 工作量

| 阶段 | 内容 | 估计 |
|---|---|---|
| S0 探针/spike | 捕获原生 `SubtitleWindowView`，验证其矩形在评论/拖动/控制条下的行为 | 2–3 天 |
| S1 几何/内容拆分 | 每帧 transform；settled key；运动 hold；`WATCH` 恒 true；宿主保留 | 3–5 天 |
| S2 安全区 | 原生窗口底边；回退 inset；Shorts 安全线；用户偏移重定义 | 2–3 天 |
| S3 回归/真机 | Robolectric 帧序列 + 现有 246+272；真机录屏矩阵 | 3–5 天 |
| **合计** | S0–S3；S1+S2 可并行一部分 | **10–16 个工作日（2–3 周）** |
| 可选 S4 | N54 A1 原生 `Laohf` 渲染器（视觉完全原生） | 另 2–3 周 |

### 5.3 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| 原生 `SubtitleWindowView` 在 cue 间隙不可见 | 安全线可能过期 | 用最后一次矩形；回退视频矩形；只在控制条可见时要求精确 |
| 原生层在 Shorts/直播/PiP 走不同路径 | 捕获不到 | 捕获失败即回退，不影响显示；Shorts 单独安全线 |
| 原生 view 被 YouTube 重建 | 弱引用失效 | 每次 attach 重新捕获；失败回退；不阻塞显示 |
| 原生窗口宽度/文本与译文不同 | 不能直接复用宽度 | 只复用中心/底边，不复用宽度；分页仍用我们自己的测量 |
| 评论区面板覆盖字幕 | 原生字幕层也在播放器分支内，评论应盖住它 | 与 N53 的 z 序契约一致；真机复核 |
| 运动期间页面宽度与视频宽度不一致 | 短暂溢出/偏宽 | 用 transform/scale 或 clip 到视频矩形；停稳后立即换页；不空白 |
| 原生 hook 版本漂移 | 补丁失败 | 结构定位 + fail closed；回退现有视频矩形路径 |

### 5.4 不改变什么

- 不改 `RebuildController`、`RebuildPageLayout`、`CaptionLanguagePager` 的翻译/分页语义；
- 不改缓存协议、请求协议、诊断字段含义；
- 不改原生字幕的 draw 屏蔽；
- 不改用户设置键；拖动位置仍持久化，只是解释为“相对安全线”。

### 5.5 本轮实现（S1/S2 最小版本）

工作树构建标识 `captionBuildId=local-1.5.5-n55`。

| 文件 | 实现 |
|---|---|
| `ui/.../CaptionOverlayV2.java` | 新增几何时钟 `geometryFrame()`/`noteGeometry()`/`moving()`；`SETTLE_MS=120`、`MOTION_GAP_MS=80`；`WATCH` 每帧只做 transform 并恒返回 `true`；`contentNow` 让 `show`/`refreshStyle`/`retryLayout` 显式提交内容；宿主变化时迁移旧 anchor/文本而不是重建；短暂无 Surface 时保留已验证页面。 |
| `ui/.../CaptionHorizontalPlacement.java` | 新增 `placeAt()`/`nudge()`；内容提交才写 LayoutParams，逐帧只改 `translationX/Y`。 |
| `application-host/.../CaptionSurface.java` | 原生字幕 view 弱引用；`currentVideoBounds()` 使用缓存 view、不扫树；`nativeCaptionBounds()` 映射到宿主坐标。 |
| `application-host/.../NativeCaptionBridge.java` | 新增 `public static void onNativeCaptionWindow(Object view)`，只存弱引用并记录一次 `CAPTION_NATIVE_GEOMETRY_CONNECTED`。 |
| `patches/.../NativeCaptionBridgePatch.kt` | 按结构定位 `SubtitleWindowView` 的窗口 setter（参数为 app/library 类型、写入同类型字段、调用 `requestLayout`），在入口插入只读 hook；找不到就跳过，不阻止安装。 |
| `docs/core-provenance.json` | 登记新 ABI `onNativeCaptionWindow(Object)`。 |

安全线：`min(video.bottom, native.bottom) - boxHeight`；无原生窗口时 `video.bottom - 24dp`；用户拖动位置仍按视频高度比例计算，再被安全线 clamp。

**已验证：**

- `:translation-core:testDebugUnitTest` **246 tests**、`:extensions:extension:testDebugUnitTest` **277 tests**，0 failures/0 errors/0 skipped。
- `CaptionMotionContractTest`（4）：连续几何不产生新分页/不换文本、只 translation；停稳后只提交一次新计划；原生安全线；`WATCH` 恒 true；短暂无 Surface 不闪空。
- `CaptionSurfaceNativeGeometryTest`（1）：原生窗口矩形映射到宿主坐标。
- 真实 21.20.405 APK + 官方 1.47.0 默认补丁 + 本 MPP 的 structure-only composition：`STRUCTURE_PASS`；hooks 中 `onNativeCaptionWindow=1`；patched dex 显示 `SubtitleWindowView.g:(Lapdq;)V` 第 0 条指令调用本 hook（`validation/runs/20261022-n55-motion-root-cause/composition-dex-hook.txt`）。
- MPP：`build/n39/gradle/patches/libs/patches-1.5.5.mpp` **599,398 B**，SHA-256 `E2F020989C02DF907A26CA7D4F2716E04D711DC1FA1CAA3ABE6C798763D6A129`；内嵌 MPE 含 `local-1.5.5-n55`、`onNativeCaptionWindow`、`geometryFrame`。

**尚未做：** 真机安装/录屏/after 诊断；原生窗口在 cue 间隙/Shorts/PiP 的实测；运动期间旧页宽度在视频大幅变窄时可能短暂偏宽；N54 A1 原生渲染器。

## 6. 验证计划

### 6.1 Robolectric 帧序列回归（本轮新增）

- `CaptionMotionContractTest`（4，已通过）：
  1. 连续 8 个视频宽度：`desiredLayout` 不增加、文本不换、anchor 只做 translation；
  2. 停稳后：产生且只产生一个新计划；
  3. 原生安全线：盒子顶边不超过 `native.bottom - height`；
  4. `WATCH` 在文本请求布局时仍返回 `true`；
  5. 短暂无 Surface：anchor 和文本保持可见，不闪空。
- `CaptionSurfaceNativeGeometryTest`（1，已通过）：原生字幕窗口矩形经宿主矩阵映射到宿主坐标。
- 现有 `:translation-core:testDebugUnitTest`（246）与 `:extensions:extension:testDebugUnitTest`
  （277）全绿。
- **尚未自动化**：运动中换页、宿主切换后的 anchor 身份、控制条出现/消失的逐帧位置；这些需要真机矩阵或后续补充测试。

### 6.2 真机矩阵

- 全屏横屏：展开/收起评论；控制条显示/隐藏；seekbar 拖动；长按上拖进全屏与反向下拖；
- 详情页竖屏 ↔ 全屏 ↔ 详情页；
- Shorts：上滑切换、评论展开、底部 UI；
- PiP/迷你播放器、旋转、切视频、切语言、AI 开关；
- 录屏逐帧核对：字幕框中心/底边是否连续；与原生字幕同帧对比控制条间距。

### 6.3 诊断（建议新增）

- `CAPTION_MOTION`：geometry source（native/video/fallback）、native rect、video rect、content key、plan generation、anchor translation、moving/settled 状态；
- `CAPTION_HOST`：宿主类名/尺寸/切换次数；
- `PLAYER_TYPE` 序列：确认拖动时是否出现 `WATCH_WHILE_SLIDING_MINIMIZED_MAXIMIZED`；
- `CAPTION_FRAME_CANCEL`：WATCH 返回 false 的次数与原因（若临时保留）。

## 7. 明确不建议的做法

1. **只把 100 ms 改成 16 ms**：全量 `render()` 仍在，重排次数 ×6，CPU/掉帧更糟。
2. **给 margin 加 `ValueAnimator`**：目标仍由每次重排决定，动画追着一个不断变化的目标，仍会抽；而且每帧 `setLayoutParams` 触发布局。
3. **只加大 hold/只隐藏旧页**：把“跳”换成“空白”，不解决跟随。
4. **在每次 PlayerType 变化时隐藏/恢复**：拖动会多次通知，隐藏/恢复本身就是抽动。
5. **转场时重建 anchor/TextView**：N53 已经指出可见闪烁风险。
6. **直接借 `Lapco` 的分页**：N54 已证明它是按时间重叠合并，会退回半句/引子落尾/长页停留；只借几何/图层，不借语义分页。

## 8. 待真机确认的开放问题

1. 详情页长按上拖时实际 `PlayerType` 序列；是否出现 `WATCH_WHILE_SLIDING_MINIMIZED_MAXIMIZED`（会命中当前 compact 判定）。
2. 原生 `SubtitleWindowView` 在上述场景中是否始终可见/有有效矩形；评论展开、Shorts、PiP 各自的表现。
3. 实际进度条重叠发生在哪个模式/哪个视频矩形：全屏 letterbox、评论区展开、Shorts，还是用户把字幕拖低。
4. 宿主在评论展开/转场中是否真的变化；`CAPTION_LAYER`/`CAPTION_HOST` 能在真机直接回答。
5. 原生窗口底边是否就是用户期望的“进度条上方”；若原生本身在某些模式不避让，需要在 S2 补一个控制条 inset，而不是假定原生总是正确。
6. Shorts 展开评论时 `CaptionSurface.isShorts()` 是否短暂翻成 false（会切换 `.72` 位置和字号档）；需要在 `CAPTION_MOTION` 里记录 `shorts/fullScreen` 模式变化，区分“几何动画”与“模式翻转”。

## 9. 证据

- 探针源码：`validation/runs/20261022-n55-motion-root-cause/TmpMotionProbeTest.java`
- 探针 JUnit 结果：`validation/runs/20261022-n55-motion-root-cause/probe-results.xml`
- 探针完整输出：`validation/runs/20261022-n55-motion-root-cause/probe-run.txt`
- 实现回归结果：`extensions/extension/build/test-results/testDebugUnitTest/`（277 tests）、`build/n39/gradle/translation-core/test-results/testDebugUnitTest/`（246 tests）。
- Composition 结构：`validation/runs/20261022-n55-motion-root-cause/composition/structure.txt`（`onNativeCaptionWindow=1`）。
- Patched-dex hook：`validation/runs/20261022-n55-motion-root-cause/composition-dex-hook.txt`。
- MPP 构建输出：`validation/runs/20261022-n55-motion-root-cause/mpp-build-output.txt`；产物 `build/n39/gradle/patches/libs/patches-1.5.5.mpp` 599,398 B / SHA-256 `E2F020989C02DF907A26CA7D4F2716E04D711DC1FA1CAA3ABE6C798763D6A129`；同一字节的副本在 `validation/runs/20261022-n55-motion-root-cause/patches-1.5.5-n55.mpp`。
- 原生字幕布局：YouTube 21.20.405 baksmali dump `classes2.txt` 的 `Laohf.onLayout`/`Laohf.k`/`Laohf.N`/`Lapdn`；`classes.txt` 的 `Lmwq.n`（`Laohc.N/M/jU/jW` 调用）。
- 现有实现：`ui/.../CaptionOverlayV2.java:145-155, 792-838, 898-1193, 1351-1371`；`application-host/.../CaptionSurface.java:42-45, 116-155, 282-310`；`presentation/.../CaptionPageExecutor.java:20-111`；`infrastructure/.../DeepSeekConfig.java:215-236`；`patches/.../NativeCaptionBridgePatch.kt:628-647`。
- 相关前序：`docs/N53-CAPTION-LAYER-AND-SIZE-20261022.md:173-174`、`docs/N54-NATIVE-CAPTION-STYLE-AND-PAGING-EVALUATION-20261022.md:33-37, 49-90`。
