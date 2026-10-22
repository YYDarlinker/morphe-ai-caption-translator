# N54 YouTube / Bilibili 原生字幕样式与分页借用评估（只读）

> 状态：**评估完成，未改产品代码、未构建、未发布、未连接真机**。
>
> 日期：2026-10-22
>
> 输入：YouTube 21.20.405 股票 APK、Bilibili `base.apk`（226,519,153 B）、当前 1.5.5 N53 工作树。
>
> 结论先行：**YouTube 的原生“渲染器/样式”可以借；YouTube 和 Bilibili 都没有可借的“语义分句/翻页器”**。
>
> 证据：`validation/runs/20261022-n54-native-caption-feasibility/`。

## 0. 三个问题的直接回答

| 问题 | 结论 | 可行性 | 建议 |
|---|---|---|---|
| 能否直接调用 YouTube 自己的字幕样式，不再自己加字幕框？ | **可以**。YouTube 的 `SubtitleWindowView` / `Laohf` / `Lapdq` / `Lapdn` 是公开可调用的类；现有补丁已经能拿到并屏蔽 `SubtitleWindowView.draw`。 | 技术上高；产品上中 | 先做“原生容器当控件”的 spike（A1），保留我们自己的分页 |
| YouTube 每页显示多少、分句翻页是本地决定的吗？能否借用？ | **一半本地、一半服务端，但没有语义分句器**。cue 文本/时间是服务端 timedtext 给的；客户端只把**时间重叠**的 cue 合并成一个窗口、用 `\n` 连接，再由原生字体/宽度换行。 | 借渲染可以；借“分页”不可以 | 不替换 `CaptionLanguagePager`；最多借原生测量/行容量 |
| B 站 AI 字幕能不能参考？ | **可参考数据模型和样式元数据，不能参考分句**。B 站把段文本、`SubtitlePosition`、`FontSizeType`、`SubtitleHeight` 等服务端化；从数据模型和播放器结构看，客户端按服务端段/cue 渲染，未发现本地分句器。 | 参考价值中；直接复用低 | 借鉴 `ai_status`/`format`/`role` 和样式元数据；分句仍用我们自己的 |

一句话：**我们花时间做的“分句 + 整句成页 + 阅读时间 + 作者 cue 契约”不是 YouTube/Bilibili 已有的东西，不能用“借用”替代；能借的是它们的外壳和样式。**

## 1. 当前实现事实（为什么会有“我们自己的字幕框”）

- `ui/.../CaptionOverlayV2.java` 自己创建 `TextView`（`CaptionText`），设置白字、阴影、圆角背景、最大两行，并把它加进视频平面的 `FrameLayout`：
  - `CaptionOverlayV2.java:658-678`（创建 `TextView`、`setShadowLayer`、`setMaxLines(2)`、`addView`）
  - `CaptionOverlayV2.java:1035-1039`（`setTextSize`、`setMaxLines(2)`）
  - `CaptionOverlayV2.java:1094-1097`（`GradientDrawable` 圆角背景）
- 字号不是原生字幕字号，而是我们自己的契约：
  - `CaptionOverlayV2.java:982-984` 调 `SubtitleStyleMetrics.targetGlyphHeightPx(...)`
  - `presentation/.../SubtitleStyleMetrics.java:22-24`：字号只由“模式 × 显示宽度”决定
  - `presentation/.../CaptionFontSize.java:3`：注释明确写着这套字号锚定的是**B 站播放器实测字号**
- YouTube 原生字幕被**只屏蔽绘制，不阻止布局**：
  - `patches/.../NativeCaptionBridgePatch.kt:28` 绑定 `SubtitleWindowView`
  - `NativeCaptionBridgePatch.kt:628-647` 重写 `SubtitleWindowView.draw(Canvas)`，AI 字幕激活时直接 `return-void`
  - `application-host/.../NativeCaptionBridge.java:281-292` 的 `suppressNativeDraw()` 决定何时屏蔽
  - 含义：原生 `SubtitleWindowView` 仍然存在、仍然被 `Laohf` 测量和布局，只是不画。**这正好是可以复用的现成对象。**
- 分页是我们自己的复杂逻辑，不是原生字幕的：
  - `translation-core/.../RebuildPageLayout.java:1-120`：页面是“显示单元”内的表现层分页，先整分句打包，再做两行例外、引子/残尾、阅读时间
  - `translation-core/.../CaptionLanguagePager.java:25-39`：先尝试整单元一页；不行才在完整分句上切；再不行才 measured fallback
  - `Authored` 人工 cue 还有独立契约（N41/N42），页边界必须服从 cue

因此现在的“字幕框”是**表现层自己画的 TextView**，不是 YouTube 的 `SubtitleWindowView`。

## 2. YouTube 原生渲染链：确实可以“直接调用”

在 YouTube 21.20.405 的 baksmali dump 中确认了以下公开结构：

### 2.1 `SubtitleWindowView` 是公开 `ViewGroup`，且接受“窗口对象”

- 类：`com.google.android.libraries.youtube.player.subtitles.ui.SubtitleWindowView`
- 继承：`android.view.ViewGroup`，`public`
- 关键字段：多个 `List`、`Typeface`、`Lapdq p`（当前窗口）
- 关键公开方法：
  - `g(Lapdq)V`：设置当前窗口，清空行列表，重新 `requestLayout()`
  - `e(F)V`：字号
  - `a(I)` / `b(I)` / `c(I)` / `d(I)`：样式整数（颜色/描边/内边距相关，具体语义由 `Laohf.l()` 决定）
  - `f(Typeface)`：字体
  - `setBackgroundColor(int)`
- `Lapdq` 是公开 final 类，字段全 public final：
  - `a:I`（窗口 id）
  - `b:J`（时间）
  - `c:Lapdn;`（窗口设置：位置、宽高百分比、背景/颜色等）
  - `d:Ljava/lang/CharSequence;`（显示文本）
  - `e:Ljava/lang/CharSequence;`（fullText）
  - `f:Z`
- 构造器公开：`Lapdq(IJLjava/lang/CharSequence;Ljava/lang/CharSequence;Lapdn;)V`
- `Lapdn` 也是公开 final，带 `public static final Lapdn a` 默认值和公开构造器 `(IIIZZ)V`。

**结论：补丁可以构造 `Lapdq`，调用 `SubtitleWindowView.g(...)`，让 YouTube 自己画。**

### 2.2 `Laohf` 是公开的原生窗口容器，`jW(List)` 就是渲染入口

- 类：`com.google.android.libraries.youtube.player.subtitles.ui` 包内的 `Laohf`（混淆名），`public`，继承 `Lapej`（`ViewGroup`）
- 公开方法：
  - `jW(Ljava/util/List;)V`：接收“当前可见窗口列表”，按 id 复用/创建 `SubtitleWindowView`，调用 `g(Lapdq)`，隐藏消失的窗口
  - `M(Lapdu;)V`：设置样式包（字号、颜色、背景、字体等）
  - `jT/jU/jV/J/N`：位移、缩放、布局方向等
  - `j()`：清空
- 私有 `l(SubtitleWindowView)` 把 `Lapdu` 的字段逐个应用到 view：`e(字号)`、`c(...)`、`setBackgroundColor(...)`、`d(...)`、`f(Typeface)`、`a(...)`、`setPadding(...)`、`b(...)`。

这说明原生容器已经封装了“样式 → view”的全部逻辑。**如果我们在自己的视频平面里实例化一个 `Laohf`，把捕获到的 `Lapdu` 交给 `M(...)`，再对每一页调用 `jW(singletonList(lapdq))`，就能得到和 YouTube 原生字幕一致的字体、字号、颜色、背景、位置、内边距和动画。**

### 2.3 当前补丁的接入点

- `NativeCaptionBridgePatch.kt` 已经能按结构找到 `SubtitleWindowView` 并重写 `draw`，说明：
  - 混淆类/方法可稳定定位；
  - 失败会 `PatchException`，不会静默装错；
  - 现有 `verifyHooks()` 会检查调用次数。
- 新增 `Lapdq`/`Lapdn`/`Laohf` 绑定属于同一类工作，但会多几个版本敏感点（见 §6）。

## 3. YouTube 的“每页显示多少”到底是什么

### 3.1 数据来源：服务端 timedtext

YouTube 的 WebVTT cue 文本、起止时间、行内换行、单词时间戳都来自服务端 timedtext（自动字幕是 ASR 产物，人工字幕是作者/审核产物）。自动字幕常见的是服务端已经切好的“两行滚动窗口”；客户端不重新做“这句话该怎么断句”。

### 3.2 客户端做的两件事：时间重叠合并 + 原生换行

在 21.20.405 中确认了 WebVTT 的本地处理链：

1. `Lankn.i(...)` 是解析器工厂：
   - 格式码 `386`/`387` → `Lapco`（该工厂走 WebVTT 解析）
   - 其它 → `Lapcn`（`LiveFmt3Parser`，直播）
2. `Lapco.b(...)` 解析 `WEBVTT`：
   - 把 cue 读成 `Lebj`（start/end/text）；
   - 对每个 cue 开一个窗口，窗口结束时间取该 cue 的结束时间；
   - 向后扫描**时间上重叠**的 cue，把文本用 `"\n"` 拼起来，并扩展窗口结束时间；
   - 生成 `Lapdq`（文本 + 时间 + `Lapdn` 设置）和 `Lapcu` 时间线对象；
   - 最终 `Lapcp` 收集这些窗口。
3. `SubtitleWindowView` 在原生字体/宽度下把窗口文本排成行。

所以“每页显示多少”由三层决定：

| 层 | 谁决定 | 内容 |
|---|---|---|
| cue 边界/文本 | YouTube 服务端 | ASR/人工字幕的原始分段、换行、单词时间 |
| 窗口合并 | 客户端 `Lapco` | 只按**时间重叠**把多个 cue 合到一个窗口，用 `\n` 连接 |
| 行布局 | 客户端 `SubtitleWindowView` | 原生字号、宽度、行高、位置 |

**没有“语义分句器”**：没有标点权重、没有“引子不落页尾”、没有“整句成页”、没有阅读时间预算、没有作者 cue 契约。它不知道中文逗号后面是不是同一句，也不知道 `当然，` 应该跟下一句。

### 3.3 因此“借用分页”会借到什么

如果直接把 `Lapco` 的窗口合并搬到我们的译文上：

- 它只能按**时间重叠**合并。我们的译文事件边界与源 cue 边界本来就不一致（N41/N42 已经证明），会重新出现“半句一页”“引子留在页尾”“一页停 10 秒”等 N53/N44 修过的问题。
- 它不会按目标语言标点切分；日语/中文/英语的标点、引号、省略号都不在它的逻辑里。
- 它没有阅读时间/页密度；长窗口会整段停留，短窗口会闪。
- 它没有“作者 cue 是固定页边界”的概念；人工字幕会退化。
- “直接把源 cue 当页边界”也不是新方案：这正是 N41/N42 对人工 cue 做过的事；对自动字幕，模型事件跨 cue 会触发整块拒绝/空白（N51 的根因之一）。源 cue 的时间边界可以借，但**页的文本边界**不能直接借。

**结论：可以借 `SubtitleWindowView` 的“壳”和“样式”，不能借它的“分页策略”。** 我们的 `CaptionLanguagePager` 应该保留，最多把“行容量谓词”改成用原生 `Lapdy`/`SubtitleWindowView` 的测量结果来校准。

## 4. 可选方案与代价

### A1（推荐先做）：把 YouTube 原生容器当控件用，保留我们的分页

做法：

1. 在补丁里新增绑定：
   - `newNativeCaptionLayer(Context)` → `new Laohf(context)`
   - `setNativeCaptionStyle(layer, Lapdu)` → `layer.M(lapdu)`
   - `showNativeCaption(layer, Lapdq)` → `layer.jW(singletonList(window))`
   - `newCaptionWindow(id, time, text, fullText, Lapdn)` → `new Lapdq(...)`
2. 在 `CaptionOverlayV2.attach()` 的视频平面宿主里加入这个 `Laohf`（MATCH_PARENT），不再创建 `CaptionText` 的 `GradientDrawable` 背景；原生层自己画。
3. 保留现有 `CaptionPageLayout` / `CaptionLanguagePager`：
   - 每页文本 + 我们算好的行计划（必要时插入 `\n`）；
   - 每页一个 `Lapdq`；
   - `Lapdn` 从原生 `jW` 调用中捕获最近一次源窗口的设置（位置/背景/宽高百分比）；`Lapdu` 从 `Laohf.M` 调用中捕获，并在 `M` 再次调用时同步到我们的 layer（字号/位置变化）。
4. 原生 `draw` 仍然保持屏蔽（源字幕不画），只让我们自己的 `Laohf` 画译文。

优点：

- 视觉上就是 YouTube 原生字幕：字体、字号、颜色、背景、描边、位置、内边距、动画、系统/视频设置一致。
- 分页、翻译、缓存、作者 cue 契约全部不动；风险集中在渲染层。
- 不需要和原生时间线抢 `jW`；我们自己的 `Laohf` 是独立控件，原生源字幕继续被 `draw` 屏蔽。

代价/风险：

- 需要捕获 `Lapdu`（样式）和 `Lapdn`（窗口位置/背景）。`Lapdu` 是原生 `Laohf.M(...)` 的入参，`Lapdn` 是 `jW` 列表里每个 `Lapdq.c`；都需要新增只读 hook 或 stub。
- 原生 `SubtitleWindowView` 的测量和我们的 `CaptionRenderSpec.fits` 可能不一致：我们按两行算的页，原生可能排成三行或溢出。需要：
  - 用原生 `Lapdy` 的 `TextPaint` 校准 `CaptionRenderSpec`；或
  - 页面文本显式带 `\n` 行计划，并按原生行高留安全余量。
- `Laohf` 的生命周期/转场/评论区层级要重新验证；它现在是“我们加的 view”，仍要服从 N53 的视频平面宿主。
- 版本漂移：21.16/21.20/21.23/21.40 的 `Laohf`/`Lapdu` 结构可能变；绑定要 fail closed。
- Shorts/直播/多窗口字幕：`Laohf` 支持多窗口，但我们的单窗口页在 Shorts 下是否合适要真机验证。

工作量：

- Spike：**3–5 天**（含 21.20.405 一个版本的 Robolectric/静态绑定 + 真机手测）。
- 生产化：**2–3 周**（多版本、多模式、转场、评论区、回归测试）。

验收门槛：

- 全屏/详情/Shorts/评论区展开：字体、位置、背景与原生一致；
- 无源文闪现、无双画、无三行溢出；
- 自动/人工/翻译轨切换不崩；
- 21.20.405、21.23.492、21.40.161 至少三版可装；
- 现有 246 + 272 测试不回退。

### A2：让 YouTube 原生时间线直接画译文（不推荐先做）

做法：hook `Laohf.jW(List)`，把传入的源 `Lapdq` 替换成我们的译文 `Lapdq`；移除 `draw` 屏蔽，让原生 view 直接画。

优点：完全不新增字幕 view；层级/动画/位置最原生。

缺点/风险：

- 原生 `jW` 的调用时机由源 cue 时间线决定；我们的页边界与 cue 边界不一致，会出现：
  - 页比 cue 长 → cue 结束时文字消失；
  - 页比 cue 短 → 同一页在多个 cue 里重复；
  - 一个 cue 跨两页 → 只能显示其中一页。
- 译文异步到达：源 `jW` 先到、译文后到；如果先放行源文本会“源文闪现”，如果空白会闪烁/缺字幕。要额外做“拦住源列表 + 译文到达后重放”的状态机。
- 需要接管原生列表的生命周期、可见性、清空、seek、转场；比 A1 高一个数量级的回归面。
- 仍然要解决“原生测量 vs 我们的页容量”的问题。

工作量：**3–5 周**，高风险。除非 A1 证明“独立 `Laohf` 不可行”，否则不建议。

### A3：只借样式参数，不借 view

做法：从 `Lapdu`/`Lapdn` 捕获字号、颜色、背景透明度、描边、内边距、位置，应用到现有 `CaptionText`。

优点：改动最小（**2–4 天**），保留现有分页和层级；视觉比现在更接近 YouTube。

缺点：仍然是我们的 `TextView`/`GradientDrawable`，不是原生渲染；字体度量、行高、位置仍可能和原生有差异。

### B：直接借 YouTube/Bilibili 的分页

**不推荐。** 理由见 §3.3 和 §5.3：两者都没有语义分句器；B 站的短页是服务端 ASR 分段的结果，不是客户端策略。借过来会丢掉 N44/N53 的修复。

## 5. Bilibili AI 字幕对照

### 5.1 数据模型：服务端给段、样式、位置

在 `base.apk` 的 `classes3.dex` 中确认（`com.bapis.bilibili.subtitle`）：

- `SubtitleItem`（轨道元数据）：
  - `id`, `id_str`, `lan`, `lan_doc`, `lan_doc_brief`
  - `subtitle_url`（实际字幕文件 URL）
  - `ai_status`, `ai_type`
  - `type`, `format`, `role`
  - `subtitle_height`
  - `author`
- `VideoSubtitle`（`SubtitleViewReply.subtitle`）：
  - `lan`, `lan_doc`
  - `subtitles`（`SubtitleItem` 列表）
  - `subtitle_position`（`SubtitlePosition`）
  - `font_size_type`（`FontSizeType`）
- `SubtitlePosition`：`x_min`, `x_max`, `y_min`, `y_max`, `purpose`
- `SubtitleHeight`：`value`
- `FontSizeType`：`FontSizeDefault`, `FontSizeOGV`
- `SubtitleFormat`：`JSON`, `ASS`
- 另有 `SubtitleViewChange`（`videoId`, `workId`）和 `GetSubtitleView` RPC。

这些字段说明：**B 站把“用哪个字幕轨、什么格式、什么字号档、什么位置范围”都服务端化/元数据化**；从现有模型看，客户端取 `subtitle_url` 指向的内容后按服务端段/cue 渲染。

### 5.2 渲染侧：media3/ExoPlayer + 自绘/Compose 包装

- `base.apk` 内含 `androidx.media3.exoplayer`、`androidx.media3.extractor.text.SubtitleDecoderException`、`CuesWithTimingSubtitle` 等类。
- `classes18.dex` 里有 `PlayerView` 风格的 `getCaptionViewFromDisplayState` / `isCaptionView` / `updateCaptionViewsVisibility`，说明存在标准 media3 caption view 路径。
- 同时有 `playerbizcommonv2.widget.subtitle.SubtitleAndAiWidget`、`SubtitleAndAiComposeKt` 等 Compose 包装，负责字幕/AI 的选择面板和状态。
- 本次只读检查**没有发现** B 站客户端里的本地分句器/语义翻页器；`SubtitleState` 的 `onGetSubtitleView` / `onUpdateSubtitleList` 是状态/RPC 层，不是句子切分。
- `format` 同时支持 `JSON` 与 `ASS`：前者按 `from/to/content` 段渲染，后者把整份 ASS 交给渲染器；两种载体的段粒度都在文件/服务端，不是客户端按标点重切。

**置信度说明**：B 站渲染器的精确类没有完全定位（可能被 R8 混淆到短名，或在自绘/Compose 层），所以“按 cue 渲染、无本地分句”是**中高置信**，不是 100% 证明。能确定的是：服务端模型已经提供了段、格式、位置、字号档；没有看到需要本地重新分句的契约。

### 5.3 能参考什么、不能参考什么

可以参考：

- 轨道元数据：`ai_status` / `ai_type` / `format` / `role` / `lan` / `lan_doc` —— 用于区分 AI、CC、人工、翻译轨，比我们现在只看 `vss`/`tlang` 更清楚。
- 样式元数据：`font_size_type`、`subtitle_position`、`subtitle_height` —— 说明“样式/位置”可以由服务端给默认值，再由用户设置覆盖。
- 字号档：`CaptionFontSize.java:3` 已经注明我们的字号锚定 B 站播放器实测值；这部分已经在参考。
- 单段一 cue 的简单链路：如果未来要做“服务端预分段 + 本地只排版”，B 站是现成范例。

不能参考：

- 句子切分/翻页：B 站的短段是 ASR 服务端产物；本次检查未发现它的客户端有“把长句按语义切页”的算法。我们的译文长度和源段长度不一致，不能直接套用服务端段边界。
- 阅读时间/页密度：B 站模型没有这类字段；它依赖服务端段长和用户字号。
- 作者 cue 契约：B 站没有我们 N41/N42 这种人工 cue 硬边界。

## 6. 建议

### 6.1 产品决策

**不替换现有分页。** `CaptionLanguagePager` + `RebuildPageLayout` 是 N44/N53 的核心修复，YouTube/Bilibili 都没有等价物；借过来会退化。

**优先做 A1 的 spike**：用原生 `Laohf`/`SubtitleWindowView` 当“字幕控件”，保留我们的分页和翻译。它同时满足“用 YouTube 自己的样式”和“不重写分页”。

### 6.2 Spike 步骤（3–5 天，只在一个分支/临时补丁里）

1. 在 21.20.405 上新增只读 hook：
   - `Laohf.M(Lapdu)` 捕获 `Lapdu`；
   - `Laohf.jW(List)` 捕获最近一个 `Lapdq.c`（`Lapdn`），原样放行；
   - 记录类/方法签名，失败即 `PatchException`。
2. 新增 stub：
   - `newNativeCaptionLayer(Context)`；
   - `setNativeCaptionStyle(layer, Lapdu)`；
   - `showNativeCaption(layer, Lapdq)`；
   - `newCaptionWindow(...)`。
3. 在 `CaptionOverlayV2` 里加一个实验开关（默认关）：
   - 开启时，用原生 layer 替代 `CaptionText` 的背景/文字绘制；
   - 关闭时，完全走当前 N53 路径。
4. 用真实视频（自动 + 人工 + 翻译轨）对比：
   - 字号/位置/背景是否与原生一致；
   - 页面是否溢出/三行/闪烁；
   - 全屏/详情/Shorts/评论区/转场；
   - seek、暂停、切视频、AI 开关。
5. 记录 `CAPTION_NATIVE_LAYER` 诊断：layer 创建/样式捕获/窗口显示/页面序号/失败原因。

### 6.3 如果 A1 通过

- 生产化 2–3 周：多版本绑定、样式回退、页面测量校准、Robolectric 回归、真机矩阵。
- 保留 A3 作为 fallback：捕获不到 `Lapdu`/`Lapdn` 时，回退到当前自定义 `TextView`（但用捕获到的颜色/透明度/字号微调）。

### 6.4 如果 A1 不通过

- 不做 A2（成本/风险不成比例）。
- 做 A3：只借样式参数，保持现有框；或维持现状。
- 记录失败原因（例如 `Laohf` 离开原生父容器后布局不稳定、`Lapdu` 捕获不到、原生测量不可用），作为后续版本重试的依据。

## 7. 未决与边界

1. **实际 timedtext 格式**：本次确认了 WebVTT 的 `Lapco` 路径；如果目标视频实际走 JSON3/SRV3，原生窗口链可能不同。需要在真实诊断里抓一次 `fmt=`/响应头，确认 `Lapco` 是否就是目标路径。
2. **`Lapdu` 捕获时机**：`M(Lapdu)` 可能只在原生字幕初始化时调用；如果 AI 字幕在原生字幕从未显示时启动，可能捕获不到。需要 fallback 默认样式。
3. **原生测量 vs 我们的页容量**：A1 的最大集成风险；必须用原生 `TextPaint` 校准，不能只用 `CaptionRenderSpec` 的估算。
4. **多窗口/双字幕**：`jW(List)` 本身支持多个窗口（多窗口字幕/多语言场景）；我们的单页模型是否需要多窗口，未评估。
5. **Shorts/直播**：`Lapcn` 是直播 fmt3；Shorts 的字幕窗口可能走不同路径；本次未做真机。
6. **B 站渲染器精确位置**：本次只读检查没有完全定位；结论是“服务端分段 + 元数据样式”的中高置信判断，不是逐行反编译证明。
7. **版本漂移**：所有原生绑定都必须在 21.16/21.20/21.23/21.40 上 fail closed；不能为了一个版本牺牲安装安全。
8. **未连接真机**：本评估没有安装、没有录屏、没有 after 诊断；所有“视觉一致”都是静态结构判断。

## 8. 证据与复现

### 8.1 输入

| 输入 | 路径 | SHA-256 |
|---|---|---|
| YouTube 21.20.405 股票 APK | `E:\Projects\morphe-caption-upstream-integrated-claude\com.google.android.youtube_21.20.405-...apk` | `4B3CA9DE0EB6857D43FA8766A724A2EBA6405DFDBCDFB20EEF6CB8950E805E2C` |
| Bilibili `base.apk` | `D:\HONOR Share\Honor Share\base.apk` | `91C41995590C6193CC710EEC8E3838F867E37EC415B81F33E1C0E0A63E106301` |

### 8.2 工具与产物

- YouTube：`E:\Projects\morphe-caption-upstream-integrated-claude\work\dumps\21.20.405\classes*.txt`（baksmali 文本 dump；约 1.08 GB）。
- Bilibili：`work/toolchain/android-sdk/build-tools/36.0.0/dexdump.exe` 对 `classes3.dex`、`classes22.dex`、`classes26.dex` 反汇编；字符串表用 Python 解析 MUTF-8。
- 证据摘录：`validation/runs/20261022-n54-native-caption-feasibility/`
  - `youtube-renderer-excerpt.txt`
  - `bilibili-model-excerpt.txt`
  - `README.md`

### 8.3 源文件对照

| 主题 | 文件 | 关键行 |
|---|---|---|
| 自定义字幕框 | `ui/src/main/java/app/yydarlinker/deepseekcaptions/CaptionOverlayV2.java` | 658-678, 1035-1039, 1094-1097 |
| 原生绘制屏蔽 | `patches/src/main/kotlin/app/yydarlinker/patches/deepseekcaptions/NativeCaptionBridgePatch.kt` | 28, 249-255, 628-647 |
| 屏蔽决策 | `application-host/src/main/java/app/yydarlinker/deepseekcaptions/NativeCaptionBridge.java` | 281-292 |
| 分页入口 | `translation-core/src/main/java/app/yydarlinker/deepseekcaptions/RebuildPageLayout.java` | 1-120 |
| 分页策略 | `translation-core/src/main/java/app/yydarlinker/deepseekcaptions/CaptionLanguagePager.java` | 25-39, 54-115 |
| 字号锚点 | `presentation/src/main/java/app/yydarlinker/deepseekcaptions/CaptionFontSize.java` | 3 |
| 字号契约 | `presentation/src/main/java/app/yydarlinker/deepseekcaptions/SubtitleStyleMetrics.java` | 22-24 |

### 8.4 YouTube 关键结构（dump 位置）

| 结构 | dump 位置 |
|---|---|
| `SubtitleWindowView` 类/字段 | `classes2.txt:2119770` 附近 |
| `SubtitleWindowView.g(Lapdq)` | `classes2.txt:2120687` 附近（`514d84`） |
| `Laohf.jW(List)` | `classes2.txt` 中 `4d9ab0` 附近 |
| `Lapco` WebVTT 解析/窗口合并 | `classes6.txt:184645` 起，核心在 `1e4544-1e4858` |
| `Lankn.i(...)` 解析器工厂 | `classes2.txt:1794856-1794865`（`4b0310`） |
| `Lapdq` 字段/构造器 | `classes2.txt` 的 `Lapdq` 类（`apdq.<init>`） |

### 8.5 Bilibili 关键结构（dump 位置）

| 结构 | dump 位置 |
|---|---|
| `SubtitleItem` | `classes3.dex`（`com/bapis/bilibili/subtitle/SubtitleItem`）；摘录见证据目录 `bilibili-model-excerpt.txt` |
| `VideoSubtitle` | `classes3.dex`（`com/bapis/bilibili/subtitle/VideoSubtitle`）；同上 |
| `SubtitlePosition` | `classes3.dex`（`com/bapis/bilibili/subtitle/SubtitlePosition`）；同上 |
| `FontSizeType` / `SubtitleFormat` | `classes3.dex`；同上 |
| `SubtitleState` 嵌套 lambda | `classes22.dex`（`kntr/common/danmaku/state/SubtitleState$...`） |
| `SubtitleViewChange` / `GetSubtitleView` | `classes26.dex`（`tv/danmaku/biliplayerv2/.../chronosrpc/...`） |
| media3 `CuesWithTimingSubtitle` | `classes.dex` 字符串 #4793 |

## 9. 本评估明确不做

- 不改产品代码、不重建 MPP、不发布、不替换 GitHub 资产；
- 不连接/写入手机；
- 不把 YouTube/Bilibili 的分页算法直接移植；
- 不把“原生样式可用”当成“原生分页可借用”。
