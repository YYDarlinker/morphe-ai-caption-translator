# N58 字幕框抽搐、拖动失效、原生评论动画卡顿与评论区字号：根因与统一方案

> 状态：**根因与架构方案已实施为 `local-1.5.5-n58`**（见
> [`docs/N58-REPAIR-20261022.md`](N58-REPAIR-20261022.md)）；未签名、未安装、未发布。
>
> 输入：用户 2026-10-22 复测反馈（N57 构建仍抽搐、仍不能拖动、横屏评论动画卡顿、评论打开字号不变）、
> `D:\HONOR Share\Honor Share\YouTube_Morphe_Debug_Logs_2026-10-22 (2).txt`、
> `caption-diagnostics-1.5.5-20261022-140858.txt`、当前 N57 工作树、YouTube 21.20.405 baksmali dump；
> **2026-10-22 20:11 追加诊断** `caption-diagnostics-1.5.5-20261022-201150.txt`（3,289,373 B）与
> **20:12 录屏** `SVID_20261022_201253_1.mp4`（15.29 s，60.03 fps，2736×1264），见 §2.7。
>
> 代码基线：`dev/1.5.5-deepseek` 工作树（N53–N57 未提交改动），`captionBuildId=local-1.5.5-n57`。

## 0. 结论

四个现象不是四个独立 bug，而是同一个架构错误：

> **字幕被实现成“视频 Surface 祖先链里一个会重排的 TextView”，而不是“播放器 overlay 根层里一个稳定的字幕图层”。**

由此派生：

1. **宿主不稳定、且位于 YouTube 手势/控件层之下**：播放器转场/评论区动画会重挂载它；触摸先被
   YouTube 消费，`TextView` 收不到 `ACTION_DOWN`。这是“拖动完全无效”的首要原因，也是转场结束抽搐的
   来源之一。
2. **内容身份绑定实时视频几何**：视频窄到当前页放不下时，分页在 `SETTLE_MS` 之后才发生；也就是
   “位置变化结束”那一刻才换文本/换宽度/换高度。这是“结束时抽搐”的直接原因。
3. **可见的框就是 TextView 自己**：每次翻页/重排都 `setLayoutParams`+`measure`，而 `getWidth()` 要等
   下一帧 layout。诊断里同一事件的可见框宽从 217 px 跳到 1004 px，且 `laid_out_box_width_px` 恒落后
   一帧（1124→908 时仍报 1124）——一帧旧框、一帧新框，就是闪烁。
4. **字幕在播放器视图树内部，并且每帧自失效**：`CaptionText.onDraw()` 每帧
   `postInvalidateOnAnimation()`，每次绘制又跑两遍 `advancePreparedPage()`（读播放时钟、算页索引）；
   `WATCH` 每帧读两次 `getGlobalVisibleRect`，每 100 ms 还可能进 `render()`/扫树。这些工作与 YouTube
   评论区的原生 resize 动画抢同一主线程/帧预算，所以原生动画掉帧。
5. **字号按屏幕方向而不是有效播放器视口分档**：`fullScreen = widthPixels > heightPixels`，横屏打开
   评论区仍是 `fullScreen=true`，字号保持 55.5 px；用户要的是详情页那一档 44.5 px。

**统一方案存在且可行**：稳定视觉层（`player_overlays` index 0）+ 独立触摸代理层
（`player_overlays_custom_views_container`，只消费字幕矩形内的 DOWN）+ 视口呈现档位
（FULL/REDUCED/DETAIL/SHORTS）+ 几何/内容双时钟 + 稳定框。它不是再调一次 `SETTLE_MS` 或拖动阈值，
而是把“图层、触摸、字号、分页宽度、动画时钟”收敛到同一个模型。

## 1. 上传日志的判定

`YouTube_Morphe_Debug_Logs_2026-10-22 (2).txt` 是 **Morphe 的调试日志导出**，不是本项目的字幕诊断：

- 2,670 行中 2,552 行是 `LithoFilterPatch: Searching ID...`；其余是 `EngagementPanel`、
  `SpoofVideoStreamsPatch`、`PreferredCaptionLanguagePatch`、`ShortsPlayerState` 等官方补丁日志。
- 没有时间戳，没有 `CAPTION_*`、`REBUILD_*`、`DeepSeek`、`CaptionOverlay`、`PlayerType` 记录。
- 只能确认现场确实发生了 `EngagementPanel open/closed: shorts-comments-panel`，以及
  `ShortsPlayerState open changed to: false`；看不到字幕宿主、触摸命中、帧耗时。

因此这份文件**不能定位**本轮问题。要实锤需要以下任一组：

1. 当前 N57 构建 App 内“字幕诊断”导出的 `caption-diagnostics-*.txt`（含 `CAPTION_LAYER`、
   `CAPTION_HORIZONTAL_PLACEMENT`、`REBUILD_PRESENTED`、`PLAYER_TRANSITION_*`）；
2. 5–10 秒 60/120 fps 录屏，逐帧看抽搐发生在“动画最后一帧”还是“翻页那一帧”；
3. `adb shell dumpsys gfxinfo <package> framestats` 或 Perfetto trace，确认评论动画掉帧的主线程
   耗时归属。

在没有这些数据的情况下，下面的根因来自代码路径 + 已有 N53 诊断 + YouTube 21.20.405 视图层级，
其中触摸路径标注为“高置信推断”，其余为代码事实。

## 2. 四个现象的根因

### 2.1 抽搐：内容重排发生在动画结束之后

N57 的 `render()` 在几何变化时先看 `moving()`：

```java
if (!forceContent && moving(motionNow)) {
    previous.set(b);
    followVisibleText(a, anchor, b);   // 只移动
    return;
}
```

`moving()` 是“最后一次几何变化后 120 ms 内”。所以只要视频还在动，内容不重排；这是对的。问题在
停稳后的分支：

```java
if (!forceContent
        && canReuseCommittedPage(inner, padding, preferred, measuredSpec, ownedCaption)) {
    placeVisibleText(...);             // 放得下：只移动
    return;
}
planBudget = new CaptionPresentationBudget(inner, ...);
pendingPages = currentPlan(planBudget, measuredSpec);   // 放不下：异步重排
```

`canReuseCommittedPage()` 要求 `page.line1Px/line2Px <= inner && contentWidth <= inner + padding`。
评论区打开时视频宽度 `2247 → 1478`（N53 诊断实测），全屏页的行宽约 1900 px，必然 `> inner`；
横屏详情↔全屏、Shorts 评论区同理。于是：

1. 动画期间：旧页保持，只 translation；
2. 动画停稳 120 ms 后：`render()` 发现放不下，发起新分页；
3. 新分页就绪：一次性 `setText` + 新宽度 + 新高度 + 重新居中。

用户看到的“位置变化结束时的抽搐”就是第 3 步。N57 文档 §8 自己写了“停稳后仍会重排一次”，
这不是边界，而是现象本身。

### 2.2 抽搐：可见框是 TextView，宽度逐页变化且布局落后一帧

字幕的可见背景在 `TextView` 上（`text.setBackground(bg)`），`TextView` 的宽高由
`applyVisibleText()` 每次重设：

```java
params.width = boxWidthPx;
text.setLayoutParams(params);
text.measure(...);
```

`setLayoutParams` 只登记下一次 layout；`getWidth()` 在当前帧仍是旧值。诊断里同一事件：

| 时间 | desired_box_width | measured_box_width | laid_out_box_width |
|---|---|---|---|
| 1792649327686 | 1124 | 1124 | **764** |
| 1792649329266 | 908 | 908 | **1124** |

即“新宽度已经写进 params/measure，屏幕上还是上一帧的宽度”。随后同一事件不同页的可见框宽在
`217 px` 与 `1004 px` 之间跳（`CAPTION_HORIZONTAL_PLACEMENT` 记录）。所以除了几何结束重排，
**每次翻页本身就是一次 4–5 倍的框宽变化**，而且总有一帧旧框。

同时，`placeVisibleText()` 把 `text.getMeasuredHeight()` 当作 anchor 高度；1 行↔2 行切换时，
`computeTarget()` 用 `top = video.top + video.height()*y - height/2`，垂直中心先按新高度算、
anchor 的 LayoutParams 却要下一帧才生效，于是垂直方向也会先偏半行再回正。

结论：**只要“框”是跟随文本测量的 View，且尺寸在几何/翻页路径上变化，就一定有一帧错位。**
N56 修掉了 margin/translation 混用，但仍是 LayoutParams/translation 混用；N57 只是让重排变少，
没有让框稳定。

### 2.3 抽搐：宿主在播放器转场时被重挂载

`CaptionSurface.captionHost()` 的宿主选择：

```java
for (View view = renderedSurface(player); view != null; view = parent(view)) {
    if (view instanceof FrameLayout) return (FrameLayout) view;
    if (view == player) break;
}
```

即“从视频 Surface 向上第一个 FrameLayout”。这个 FrameLayout 在视频平面内部；播放器全屏/详情
切换、上滑/下拉、评论区 resize 时，YouTube 会重建或重挂载这段视图树。`attach()` 随后：

- 从旧宿主 `removeView(anchor)`，加到新宿主；
- `geometryKnown=false`、`videoGeometry.setEmpty()`、`previous.setEmpty()`、`lastLayout=0`；
- 下一帧重新读几何、重新 `placeVisibleText()`。

即使 anchor/文本被保留（N56 已做），宿主坐标系、父布局、裁剪、translation 基准都在变；转场结束
那一帧很容易出现“先旧后新”的一次跳。N53 文档 §6 已标记：“播放器视图被整体重建时会有一次重新
挂载……若真机出现可见闪烁，应改为保留旧 anchor 平移”。N55/N56 只做了“保留 anchor”，没有解决
“宿主本身在转场中变化”。

### 2.4 拖动失效：触摸根本到不了 TextView

N53 把字幕从 `android.R.id.content`（Activity 内容层最后一个子 View，最上层）移到了视频平面
内部。这个位置在 YouTube 的播放器控件/手势层**之下**：

- `player_overlays`（`YouTubePlayerOverlaysLayout`）→ `youtube_controls_overlay` →
  `inset_overlay_view_layout` → `controls_layout` 是完整的全屏控件/手势分支；
- `CaptionSurface.captionHost()` 选中的是 Surface 上方第一个 FrameLayout，通常是 `PlayerView`
  内部的 FrameLayout，位于上述控件分支之下；
- Android 的触摸分发从上层兄弟开始。DOWN 先到 YouTube 的全屏控件/手势 View；只要它消费或拦截，
  下面的字幕 `TextView` 永远收不到 `ACTION_DOWN`。

N55–N57 修的是“DOWN 之后”的逻辑：实时 `dragBounds`、横/竖屏键、`getRawY` 换算、350 ms 定时长按、
`requestDisallowInterceptTouchEvent(true)`。这些全部以“DOWN 已经到达 TextView”为前提。
`requestDisallowInterceptTouchEvent` 只能阻止**祖先**在子 View 收到 DOWN 之后拦截，不能把 DOWN
送到一个被上层兄弟盖住的子 View。

测试也没有覆盖真实分发：`CaptionMotionContractTest.draggingStillMovesTheCaption()` 和
`aLandscapeDragUsesTheLiveOrientationAndLiveRectangle()` 都是反射直接调用 `CaptionOverlay.drag()`，
不经过 `ViewGroup.dispatchTouchEvent`。所以测试全绿，真机拖不动。N57 文档最后一句也承认：
“若真机上仍出现‘长按无反应’，下一步应在拖动入口增加只读 `CAPTION_TOUCH` 诊断”。

高置信推断：**拖动失效是 N53 图层迁移的直接回归，不是 N55–N57 的坐标算法问题。** 需要真机
`CAPTION_TOUCH`（DOWN/MOVE/UP/CANCEL + 命中矩形 + 父链）实锤；但从层级和“以前能拖、N53 后不能”
的时间关系看，这是唯一能解释“所有拖动补丁都无效”的机制。

### 2.5 原生评论动画卡顿：字幕在播放器树内，每帧自失效 + 周期性全量 render

三个叠加源：

1. **每帧自失效**：`CaptionText.onDraw()` 末尾
   `postInvalidateOnAnimation()`。只要字幕在屏上，TextView 每帧重绘；每次 `onDraw` 又调用两次
   `guard.beforeDraw()` → `advancePreparedPage()` → 读播放时钟、算页索引。这是持续的 60/120 Hz
   主线程负载，正好叠加在评论动画上。
2. **每帧几何 + 100 ms 全量 render**：`WATCH` 每帧 `geometryFrame()`，内部
   `getGlobalVisibleRect` 两次、`setTranslation`；每 100 ms 还可能进 `render()`，而 `render()` 在
   `moving()` 判断之前就执行了 `displaySpec.apply(text)`、`CaptionSurface.currentVideoBounds()`，
   每 500 ms 还可能 `CaptionSurface.refresh()`。`refresh()` 在退化情况下会遍历最多 900 个
   Surface 候选（Shorts 1,800 个）；一次这样的树扫描就足以掉一帧。
3. **内容提交触发布局**：`setLayoutParams`/`measure`/`setText` 会让播放器容器在评论动画正在
   resize 时再跑一次 layout；`anchor.bringToFront()` 改子 View 顺序也会触发布局；
   `elevation=12dp` + 每次 `new GradientDrawable()` 增加 GPU/栅格化成本。

字幕在视频平面内，评论动画又在 resize 视频平面，两者共享同一套 layout/draw 路径；任何一次字幕
重排都会和 YouTube 的动画 layout 相互打断。这就是“YouTube 原生打开/关闭评论区动画卡顿”的来源，
不是 YouTube 自己的动画坏了。

### 2.6 评论区字号不变：分档键是屏幕方向，不是有效视口

`render()`：

```java
boolean fullScreen = !shorts && metrics.widthPixels > metrics.heightPixels;
float targetGlyphHeight =
        SubtitleStyleMetrics.targetGlyphHeightPx(cfg.captionSizeTier, screenWidth, fullScreen);
```

`SubtitleStyleMetrics.targetGlyphHeightPx()` 只做 `screenWidth * CaptionFontSize.modeRatio(tier, fullScreen)`。
横屏打开评论区时屏幕仍是横屏，`fullScreen=true`，字号仍是 `55.5 px`；N53 又刻意删除了
“视频宽度 < 0.8×正常宽度时按比例缩小”的分支，所以评论区打开不会变小。

用户要的是“评论区打开 = 视频详情页那一档”。正确的分档键不是屏幕方向，而是**有效播放器视口**：

- 视口接近全屏（视频高度 ≥ 屏幕短边的 ~90%）→ 全屏字号；
- 视口被评论区/面板压缩（横屏评论、Shorts 评论、详情页）→ 详情页字号；
- 带 0.86/0.92 迟滞，避免在阈值处来回跳。

Shorts 目前 `fullScreen=false`，走的是详情比例；但它的 `screenWidth` 与详情页相同（竖屏 1264），
所以字号本身是 44.5 px。用户仍感觉“没变小”，说明 Shorts 评论打开时视频视口缩小、字号却不变，
相对缩小后的视频显得更大。按视口分档并让 `SHORTS_REDUCED` 使用详情字号，可以统一横屏/Shorts/
详情三条路径。

### 2.7 新证据（2026-10-22 20:11 诊断 + 20:12 录屏）：抽搐被现场记录

输入：

- `caption-diagnostics-1.5.5-20261022-201150.txt`（3,289,373 B；覆盖 19:52–20:11:43；408 条
  `REBUILD_DISPLAY_RESULT`；`Display text debug: on`；当前构建逻辑）；
- `SVID_20261022_201253_1.mp4`（15.29 s，60.03 fps，2736×1264）。

日志结束于 20:11:43，录屏始于 20:12:53，**不是同一时段**；但两者走的是同一套 `CaptionOverlay`
路径。日志给出机制，录屏给出肉眼现象。以下结论都能在日志里逐条复现，不是从录屏猜的。

#### A. “一帧旧框”是常态，不是边缘情况

408 条可见/不可见结果里，**174 条（42.6%）** 的 `laid_out_box_width_px != desired_box_width_px`。
这不是偶发，而是每次内容提交的默认结果。连续三条就是一次翻页：

```
1792669935814  desired=284  laid=332   text=我不知道。
1792669936752  desired=284  laid=284   text=我不知道。          (hold)
1792669936770  desired=529  laid=284   text=你们知道Neal在哪吗？
1792669937629  desired=529  laid=529   text=你们知道Neal在哪吗？ (hold)
1792669937647  desired=337  laid=529   text=你们知道Neal 在哪吗？
1792669938142  desired=555  laid=337   text=>> 嗯，我想他在楼上。
```

每次 `setText`/`setLayoutParams` 后，`desired/measured` 已经是新值，`laid_out` 还是上一页；
可见的 `TextView` 背景因此先画旧宽度、下一帧再画新宽度。翻页越密，闪烁越多。

#### B. 宿主在转场中反复换，最快 35 ms 换两次

`caption_host_px` 在 408 条结果里出现 17 种尺寸，且转场中连续变化：

```
1792664094024  host=2599x1201  full_screen 55.5  desired=704  laid=644
1792664094157  host=2599x1201  detail      44.5  desired=572  laid=704   (+133 ms)
1792664094192  host=1264x711   detail      44.5  desired=572  laid=572   (+35 ms)
```

```
1792669628421  host=1264x711   detail      44.5  desired=284  laid=284
1792669628715  host=1579x888   full_screen 55.5  desired=344  laid=284   (+294 ms)
1792669628972  host=2736x1264  full_screen 55.5  desired=344  laid=344   (+257 ms)
```

宿主尺寸还包括 `1264x2430`（竖屏播放页的高容器，不是视频平面）、`1453x817`、`1471x829`、
`1529x859`、`1357x763`、`1431x806`、`1258x658`、`1262x691` 等。这些不是视频 Surface 的矩形，
而是随转场伸缩/重建的父容器。更关键的是：**同一台设备上，一个竖屏视频的宿主是 `1264x2430`
（视频本身只有 711 高），另一个竖屏视频却是 `1264x711`**——说明 `captionHost()` 在普通播放时
也会因为视频/页面结构不同而选到不同层级，不需要等转场。`1264x2430` 这种“宿主比视频高 3 倍”
的坐标系一旦在换视频/转场时变成 `1264x711`，字幕的 `translationY` 基准就整体换了一套。`CaptionOverlay.attach()` 每次发现宿主变化都会：

1. `removeView(anchor)`，再加到新宿主；
2. `geometryKnown=false`、`videoGeometry.setEmpty()`、`previous.setEmpty()`、`lastLayout=0`；
3. 下一帧重新读几何、重新 `placeVisibleText()`。

这就是“位置变化结束时”的抽搐来源：视频动画的最后一两步，恰好也是宿主切换的时点；字幕先在新宿主
按旧几何放一次，下一帧再按新几何放一次。N55/N56 的“保留 anchor/文本”只能避免重建 TextView，
不能避免宿主坐标系变化。

#### C. 字号/分档在转场中间翻转，导致中途重排

上面第一组数据同时是第 4 个问题的实锤：`size_mode` 从 `full_screen` 翻成 `detail` 时，
宿主仍是 `2599x1201`（接近全屏），字号已经从 55.5 变成 44.5，`desired` 从 704 变成 572。
也就是说，**字号在视频还没缩到详情页大小之前就切了**，引发一次中途重排；35 ms 后宿主才变成
`1264x711`，又引发一次重挂载。用户看到的是“先缩一下字号/框，再跳一下位置”。

反向也一样：`detail 44.5` → `full_screen 55.5` 发生在宿主 `1264x711 → 1579x888` 之间，
视频还没到 `2736x1264` 全屏，字号已经先放大；随后宿主再换一次。分档键是屏幕方向
（`widthPixels > heightPixels`），不是有效播放器视口，所以它会在转场中提前翻转。

#### D. 录屏里能看到什么

按 60 fps 逐帧看 `SVID_20261022_201253_1.mp4`：

- **6.2–6.8 s 打开评论区**：视频从全宽突然缩到左侧（面板同时出现），字幕框从画面底部中央
  移到缩小后视频的左下；移动是 **2–4 次离散提交**，不是每帧连续的 translation。6.28 s 附近
  视频边缘已经到新位置，字幕框还落后 1–2 帧，然后才跟上。
- **7.3 s 翻页**：文本从 `我想你应该会更好地理解 到底是怎么回事` 换成
  `我们先来看一下整个事的来龙去脉`，框宽随文本变化一次；这是内容变化，但和上面的几何抽动
  叠在一起，看起来就是“抽搐”。
- **7.6–8.0 s 关闭评论区/视频展开**：字幕框跟着视频底边向下走，途中压到进度条/时间文字，
  到 8.0 s 左右才停稳；同样能看到几次阶梯式的位置变化。
- **11.8–12.2 s 全屏→详情/换视频**：宿主和字号同时变，字幕框在画面底部先缩小再换位，
  与日志里 `detail/full_screen` 的翻转模式一致。

录屏不是“字幕完全不动”，而是“大多数帧在动、但每次停下来的最后 1–2 帧会多跳一次/多缩一次”。
这正是 `laid_out` 落后一帧 + 宿主重挂载 + 档位提前翻转的合成结果，不是动画曲线问题。

#### E. 这组数据排除了什么

- 不是“`SETTLE_MS` 太小/太大”：42.6% 的宽度落后发生在没有几何动画的普通翻页上。
- 不是“拖动阈值/坐标换算”：抽搐发生在没有触摸的评论区开关和翻页上。
- 不是“YouTube 原生动画本身坏”：同一段录屏里，视频/面板动画是连续的；字幕框的阶梯是字幕
  自己的 2–4 次提交。
- 不是“字号缩放曲线”：N53 已删除视频宽度缩放，但 `size_mode` 仍会在转场中翻转。

因此，第 2 节的四个机制从“高置信推断”升级为“现场数据证实”。统一方案（稳定宿主 + 触摸代理 +
视口档位 + 双时钟 + 稳定框）不是另起炉灶，而是针对这四类已证实的事件。

## 3. 为什么前三轮没有解决

| 轮次 | 做了什么 | 为什么没触及根因 |
|---|---|---|
| N53 | 宿主从 Activity 内容层移到视频平面；删除视频宽度缩放 | 修了“字幕压评论”，但把触摸层移到了 YouTube 手势层之下；字号仍按方向分档 |
| N55 | 几何/内容双时钟；每帧 translation；原生安全线 | 宿主仍在视频平面内；每帧读原生窗口/矩阵；安全线 clamp 让拖动无效 |
| N56 | 绝对 translation；删除安全线；拖动不 render | 只修了 DOWN 之后的路径；宿主/触摸/每帧自失效未变；停稳后仍重排 |
| N57 | 放得下不重排；实时拖动矩形；精确 PlayerType | 放不下时仍在停稳后重排；触摸仍到不了；每帧自失效/宿主内层未变 |

共同盲点：**一直在“DOWN 之后怎么算”和“停稳后怎么重排”上修补，没有改变“字幕在哪一层、触摸从哪
一层来、内容何时重排、框由谁决定尺寸”这四个前提。**

## 4. 统一方案

### 4.1 一句话

> 把字幕做成**播放器 overlay 根层的稳定视觉图层**，再配一个**只消费字幕矩形内 DOWN 的透明触摸代理层**；
> 用**有效播放器视口档位**同时决定字号、分页宽度和框尺寸；动画期间只做 translation，档位切换在
> 动画中段提交一次，停稳后不再重排。

### 4.2 稳定视觉层：`player_overlays` index 0

- 宿主改为 `R.id.player_overlays`（`YouTubePlayerOverlaysLayout`）的**第 0 个子 View**；
  找不到时退回 `R.id.inset_overlay_view_layout` 第 0 个；再退回
  `player_overlays_custom_views_container`。
- 该层在视频之上、播放器控件之下、评论面板之下：
  - `youtube_controls_overlay` 是 `player_overlays` 的后加子 View，画在 index 0 之上；
  - `engagement_panel_wrapper` 是 `watch_player` 之后的兄弟，画在整个 player 之上，评论仍能盖住字幕。
- 这是全屏稳定容器，评论动画 resize 的是视频平面，不是它；字幕的 translation 不会触发布局视频平面。
- 不再从 Surface 向上找 FrameLayout，不再在转场中换宿主；播放器整体 reparent 时它跟着走。

风险：`player_overlays` 的 LayoutParams 是 YouTube 自定义 `Lapel`，添加子 View 时要让它走
`generateLayoutParams()`；`o()` 会 `bringChildToFront(c)`，但只移动 custom views container，
index 0 的我们不受影响。需要真机 `CAPTION_LAYER` 确认宿主类名/尺寸/子 View 数。

### 4.3 独立触摸代理层：`player_overlays_custom_views_container`

为了不把字幕画到控件上面（N56 的决定是“字幕在播放器 UI 之下”），不要靠视觉层自己收触摸。新增一个
全屏透明代理 View，挂到 `player_overlays_custom_views_container`（YouTube 自己
`bringChildToFront` 的层，位于控件之上、评论之下）：

- `ACTION_DOWN`：把屏幕坐标换算到字幕矩形；不在矩形内 → `return false`，事件继续传给 YouTube
  控件/播放器；在矩形内 → `return true`，开始 350 ms 长按/拖动。
- `ACTION_MOVE/UP/CANCEL`：只更新视觉层的 `translationY`（和保存的位置比例），不 `render()`、
  不重排、不读播放时钟。
- 评论面板在整个 player 之上，所以评论打开时代理不会抢评论的触摸；代理只在字幕矩形内消费，
  控件仍可在矩形外正常点击。
- 代理只画透明，不改变 z 序；这是“视觉层/交互层分离”，同时满足“字幕在 UI 之下”和“拖动一定收到
  DOWN”。

如果不想加代理，备选是把视觉层直接放到 `custom_views_container`（必定能收触摸），代价是字幕会画在
播放器控件之上。按 N56 的决定，推荐代理方案。

### 4.4 呈现档位：FULL / REDUCED / DETAIL / SHORTS

新增一个纯函数 `PresentationClass.of(video, screen, shorts)`，由**有效视口**决定，而不是屏幕方向：

| 档位 | 判定（带迟滞） | 字号 | 分页/框宽 |
|---|---|---|---|
| `FULL` | 非 Shorts，视频宽 ≥ 0.88×全屏视频宽 | 全屏档 55.5 px | 全屏视口宽 ×0.92 |
| `REDUCED` | 非 Shorts，视频宽 ≤ 0.80×全屏视频宽（进入）/ ≥0.88（退出） | **详情档 44.5 px** | 缩小后视口宽 ×0.92 |
| `DETAIL` | 竖屏详情页/非全屏播放器 | 详情档 44.5 px | 详情视口宽 ×0.92 |
| `SHORTS_FULL` | Shorts，视频高 ≥0.90×屏幕高 | 详情档 | Shorts 视口宽 ×0.78 |
| `SHORTS_REDUCED` | Shorts，视频高 ≤0.80×屏幕高 | 详情档（可再小一档） | 缩小后视口宽 ×0.78 |

- 档位切换只在**跨阈值**时发生，进入/退出用不同阈值（0.80/0.88），避免评论区动画中反复切换。
- 切换时提交一次新分页/新字号/新框宽；**不等停稳**。阈值在动画中段被跨过，分页在动画结束前已经
  就绪，停稳时不再有内容变化。
- `REDUCED` 使用详情档**绝对字号**（44.5 px 这一档），满足“评论区打开按详情页大小”的要求；
  不再用 `screenWidth × detailRatio`（横屏会得到 96 px）。
- 分页宽度用档位的**稳定目标宽度**（首次进入 REDUCED 用当前视口宽/记忆的 reduced 宽度；
  后续用上次停稳的 reduced 宽度），不再用动画中的瞬时宽度。

### 4.5 双时钟

- **几何时钟**：只在视频矩形变化时运行。读一次 Surface 矩形（相对 overlay 根），只改
  `translationX/Y`；不 measure、不 setText、不 setLayoutParams、不 invalidate 文本、不读播放时钟。
- **内容时钟**：只在以下事件运行：新事件/新文本、档位切换、页边界到达、字号/语言/样式变化。
  页边界用 `postOnAnimationDelayed(page.end - now)` 精确调度一次，**删除** `onDraw()` 里的
  `postInvalidateOnAnimation()` 和逐帧 `advancePreparedPage()`。
- 动画期间不调用 `CaptionSurface.refresh()`、不做树扫描、不 `bringToFront`、不 `displaySpec.apply`。
- `render()` 拆成 `renderGeometry()` 和 `renderContent()`；`WATCH` 只调前者，后者只由内容事件驱动。

### 4.6 稳定框

- 框的宽高在**内容/档位提交时**确定；动画/翻页路径不再改 LayoutParams。
- 方案 A（推荐，最稳）：框宽 = 当前事件所有页的最大行宽（或档位宽度），框高预留 2 行；
  页切换只换文字，框不动。短页会有少量额外背景，但不会再抽。
- 方案 B（贴合文字）：框宽 = 当前页文字宽，但用同步 `layout()` 或自绘背景，保证背景与文字在
  同一帧生效；翻页时仍会有一次宽度变化，但不再有一帧旧框。
- 无论 A/B，anchor 的宽高在动画期间保持不变；`placeVisibleText()` 只改 translation。
- 删除 `elevation`、缓存 `GradientDrawable`、避免每次 `new`。

### 4.7 触摸路径的验收

新增 `CAPTION_TOUCH` 诊断（DOWN/MOVE/UP/CANCEL、raw/本地坐标、命中矩形、父链类名、
`requestDisallowInterceptTouchEvent` 调用点）。真机上必须看到：

1. DOWN 命中代理，而不是被 `youtube_controls_overlay` 消费；
2. MOVE 的 rawY 变化能改变 `translationY`；
3. UP/CANCEL 后位置持久化，下一次打开视频仍生效；
4. 评论打开时，评论面板区域的 DOWN 不被代理消费。

如果代理仍收不到 DOWN，说明 `custom_views_container` 之上还有消费层；此时退回“视觉层直接放
custom_views_container”的备选，并在真机上确认控件点击是否可接受。

## 5. 实施顺序

| 阶段 | 内容 | 估计 |
|---|---|---|
| S0 | `CAPTION_TOUCH` + `CAPTION_LAYER` + `CAPTION_MOTION` 只读诊断；真机确认宿主/触摸/帧耗时 | 0.5–1 天 |
| S1 | 视觉层迁到 `player_overlays` index 0；移除 Surface 祖先宿主/转场重挂载 | 1–2 天 |
| S2 | 触摸代理层 + 拖动只 translation；删除 `requestDisallowInterceptTouchEvent` 依赖 | 1–2 天 |
| S3 | 呈现档位 + 详情字号 + 档位切换时提交分页；删除停稳后重排 | 1–2 天 |
| S4 | 双时钟/删除每帧自失效/树扫描/布局抖动；稳定框 | 1–2 天 |
| S5 | Robolectric 真实 dispatch/帧序列 + composition + MPP + 真机复验 | 2–3 天 |

不改翻译、分页语义、请求协议、缓存；只改 `ui`/`application-host`/`presentation` 的图层、几何、
字号档位和渲染调度。

## 6. 测试需要补的三个盲点

1. **真实触摸分发**：构造 `watch_page -> watch_player -> player_overlays(controls above) -> caption`
   的层级，用 `dispatchTouchEvent` 发 DOWN/MOVE，断言代理收到 DOWN、视觉层只 translation；
   现有测试是反射调 `drag()`，不算数。
2. **帧序列**：连续 8–12 个几何宽度（含评论区 2247→1478）逐帧断言 `desiredLayout` 不变、
   `TextView` 宽高不变、只有 `translation` 变；档位跨阈值时只允许一次新计划，且发生在运动结束前。
3. **字号契约**：横屏 2736×1264 评论区打开 → 44.5 px；Shorts 评论打开 → 详情档；详情页 → 44.5 px；
   全屏无评论 → 55.5 px；阈值迟滞不抖动。

## 7. 边界与未决

- 已有真机诊断/录屏证实宿主尺寸和宽度落后（§2.7），但 `player_overlays`/`custom_views_container`
  的实际可见性、`Lapel` 参数、Shorts 的 overlay 根仍需新构建的 `CAPTION_LAYER` 确认；
  `CAPTION_TOUCH` 尚未接入，拖动路径仍是高置信推断。
- `REDUCED` 首次打开时若没有历史 reduced 宽度，可能比最终视频略宽/略窄；用“上次停稳宽度”记忆，
  首次允许一次校准，之后稳定。
- 稳定框方案 A 会让短页背景比文字宽；这是视觉取舍，需要用户确认是否接受，或改用方案 B 的
  自绘背景。
- 字幕仍在播放器 UI 之下；如果用户希望控件出现时字幕自动避让，需要另做 UI 避让，本轮不包含。
- 评论全屏时字幕应被面板盖住；视觉层和代理都在 player 内，评论面板在其上，z 序正确。

## 8. 代码索引

- 宿主选择：`ui/.../CaptionOverlayV2.java:679-773`；`application-host/.../CaptionSurface.java:309-319`。
- 几何每帧：`CaptionOverlayV2.java:173-188, 997-1011`。
- 停稳后重排：`CaptionOverlayV2.java:1159-1217`；`canReuseCommittedPage` `:1046-1070`。
- 可见框尺寸：`applyVisibleText` `:1498-1572`；`CaptionHorizontalPlacement.position` `:75-98`。
- 每帧自失效/绘制：`CaptionText.onDraw` `:781-808`。
- 拖动：`:1790-1852`；测试反射调用 `CaptionMotionContractTest.java:142-163, 193-230`。
- 字号：`SubtitleStyleMetrics.java:22-24`；`CaptionFontSize.java:39-41`；
  `CaptionOverlayV2.java:1156, 1187-1195`。
- YouTube 层级：`player_overlays.xml`、`youtube_controls_overlay.xml`、
  `next_gen_watch_layout_no_player_fragment_container.xml`；
  `YouTubePlayerOverlaysLayout.o()` 末尾 `bringChildToFront(c)`（baksmali `classes.txt:1d4112`）。
- 诊断证据：`caption-diagnostics-1.5.5-20261022-140858.txt` 的 1792649327686/1792649329266
  `laid_out_box_width_px` 落后记录；`CAPTION_HORIZONTAL_PLACEMENT` 的 217/1004 框宽记录。
- 2026-10-22 20:11 诊断：`caption-diagnostics-1.5.5-20261022-201150.txt`；408 条 display 结果中
  174 条 `laid_out != desired`；宿主序列见 §2.7.B；字号/档位翻转见 §2.7.C。
- 2026-10-22 20:12 录屏：`SVID_20261022_201253_1.mp4`（15.29 s / 60.03 fps / 2736×1264）；
  证据帧在 `validation/runs/20261022-n58-overlay-evidence/frames/`
  （`open_crops.jpg`、`settle_crops.jpg`、`twitch_crops.jpg`、`step_grid.jpg`）。
- 解析后的 408 条表：`.tmp/display_rows.csv`（可由诊断重新生成，不入库）。
