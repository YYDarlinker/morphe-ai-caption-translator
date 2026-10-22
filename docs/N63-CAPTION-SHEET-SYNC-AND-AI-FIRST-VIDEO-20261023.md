# N63 评论区动画同步字号、AI 开关首视频即生效（1.5.6 本地构建）

> 状态：**已实现并构建 `local-1.5.6-n63`**；未签名、未安装、未发布。
>
> 输入诊断：`D:\HONOR Share\Honor Share\caption-diagnostics-1.5.6-20261023-001408.txt`
> （1,533,659 B；`ui=1.5.6`；`Display text debug: on`）。用户复测两点见 §1。

## 1. 一句话

两点仍然是“字幕层看到的世界”和“用户看到的世界”不一致：

1. 评论区的动画是**播放器容器缩放**；`CaptionSurface.currentVideoBounds(host)` 返回宿主局部坐标，
   会把缩放抵消掉。所以档位在动画期间看不到宽度变化，只能等动画结束、局部矩形真正重建后
   才切换。修复：用**屏幕上的视频宽高**驱动一个连续的 `View` 缩放，档位只在动画端点提交一次；
   视觉字号随面板开始/结束，分页不逐帧重排。
2. AI 开启时，原生字幕窗口只在自己已经“活跃”后才被遮挡；启动初期 `ownsNativeTrack()` 和
   `isVisibleActive()` 都是 false，于是原生字幕先画出来。修复：**AI 开关是原生绘制的唯一开关**，
   开启即遮挡；并在视频 ID 到来时立即用后台已捕获的原生选择启动引擎，不再等 CC 扫描。

## 2. 评论区字号：为什么之前总在动画后

### 2.1 诊断与机制

最新诊断里，档位提交时视频局部矩形已经是终点：

```
1792685447123 | CAPTION_PRESENTATION_CLASS | class=REDUCED;video=0 207 1510 1056;...
1792685448518 | CAPTION_PRESENTATION_CLASS | class=FULL;video=175 0 2422 1264;...
1792685450175 | CAPTION_PRESENTATION_CLASS | class=REDUCED;video=0 207 1510 1056;...
```

`video` 是 `currentVideoBounds(host)` 的宿主局部矩形。YouTube 的评论区动画通过缩放
`player_overlays`/player 容器完成：屏幕上的画面在连续变小，但 `host.transformMatrixToGlobal()`
也在同步缩放，逆矩阵映射回宿主局部后宽度仍是 `2422`。只有动画结束、容器真正 layout 成新尺寸时，
局部矩形才变成 `1510`。因此 N62 的 80 ms 候选再快，也只能等这个终点帧。

### 2.2 修复：视觉层连续缩放，档位端点提交

- `CaptionSurface.visibleVideoWidth()` / `visibleVideoHeight()`：读渲染 Surface 的
  `getGlobalVisibleRect`，这是**屏幕上的真实画面**，会跟随播放器容器的缩放。
- `CaptionPresentationClass.classify(..., observedVideoWidth, ...)`：横屏比例优先用屏幕宽度；
  局部矩形只在屏幕宽度不可用时兜底。这样档位判定不再被宿主逆矩阵抵消。
- `CaptionOverlay.applyCommentScale()`：在横屏、非 Shorts、非 `SLIDING`、可见视频高度已小于
  屏幕 95% 时，计算 `progress = (fullWidth - observedWidth) / (fullWidth - reducedWidth)`，
  把当前 `TextView` 的 `scaleX/scaleY` 连续设为
  `fullGlyph + (detailGlyph - fullGlyph) * progress` 相对当前档位字号的倍数。
  - 面板打开：55.5 → 44.5 随动画连续缩小；
  - 面板关闭：44.5 → 55.5 随动画连续放大；
  - 只改渲染缩放，不改字号、不重新 measure、不重新分页、不动 YouTube 控件树。
- 档位（二进制分页/字号）只在 `progress <= .02` 或 `>= .95` 的**动画端点**提交；提交时
  `resetCommentScale()`，此时视觉字号已经等于新档字号，不会看到“动画结束后再跳一下”。
- `commentTransitionArmed` 记录“从评论面板缩小状态进入”，关闭时即使可见高度先回到 95%，
  仍继续缩放直到 FULL 端点；4:3/21:9 的全屏 letterbox 视频在静止时高度未缩小，不会被误判。
- 端点渲染的边距也使用屏幕视频宽度（`visibleVideoWidth()`），避免局部矩形晚一帧导致二次重排。

### 2.3 效果与边界

- 字号变化的起点/终点与评论区动画一致；不再等动画结束。
- 动画期间只做 `View.setScaleX/Y`，不触发分页；端点一次提交。
- 全屏↔详情的 `SLIDING` 拖动仍不缩放（上一轮要求“没有滑到底不提前变小”）。
- 缩放会让文字在动画中轻微栅格化；端点提交后恢复原生字号渲染。

## 3. AI 开关首视频即生效

### 3.1 根因

`NativeCaptionBridge.suppressNativeDraw()` 之前是：

```java
return enabled() && (CaptionActions.ownsNativeTrack() || CaptionActions.isVisibleActive());
```

AI 刚开启、引擎还没启动时两者都是 false，原生字幕窗口继续画。用户看到的是“原生字幕几秒后
才变 AI 样式”。另外 `CaptionButtonController.onVideoId()` 只在 `captionIntent == ON` 时调用
`onCaptionsShown()`；首视频时 `captionIntent` 可能还是 UNKNOWN，要等 CC 按钮扫描（最多 2.5 s），
即使后台 `NATIVE_TRACK_APPLIED` 已经在 `selections` 里。

### 3.2 修复

- `suppressNativeDraw()` 改为 `return enabled();`
  - AI 开关开：原生字幕窗口任何一帧都不画，AI overlay 是唯一字幕；
  - AI 开关关：返回 false，原生字幕正常画。
- `CaptionButtonController.onVideoId()`：只要 `DeepSeekConfig.enabled(activity)`，立即调用
  `NativeCaptionBridge.onCaptionsShown()`，不再等 `captionIntent`/CC 扫描。
- 结合 N62 的路径：`onCaptionsShown()` 优先用该视频的后台 native selection；没有则
  `refreshFromCatalog()`；再没有则等原生轨，但原生样式已被遮挡。
- 目标语言仍来自原生 Auto-translate；没有目标时引擎不能凭空翻译，但不会先显示原生样式。

### 3.3 边界

- 首视频如果既没有后台 native selection、也没有已选目标语言，AI 会短暂没有文字（原生样式
  已被遮挡），直到原生轨/目标到达；不会再出现“原生字幕几秒后切 AI”。
- AI 开关关时完全恢复原生绘制；不会因为遮挡逻辑误伤原生字幕。
- 仍无真机安装；本轮验证是诊断 + Robolectric 契约 + 打包。

## 4. 验证

- 全量 `:extensions:extension:testDebugUnitTest`：**288 tests / 54 result files**，
  0 failures / 0 errors / 0 skipped（2026-10-23 00:25）。
- 新增 `CaptionLayerOverlayTest.theReadingSizeFollowsTheSheetScaleBeforeTheClassCommits`：
  屏幕视频 1850 px 时档位仍 FULL、`scaleY` 已在 .8–.96 之间；到 1478 px 端点时提交 REDUCED、
  字号 44.5、缩放复位 1。
- `CaptionPresentationClassTest`：observed width 参与分类、旧横向帧不误判 DETAIL、
  letterbox 高度不翻转。
- 本地化 232×14；`verifyIntegrationSources` `errors=[]`。
- MPP 产物与 SHA 见 PROJECT-STATE §36。
