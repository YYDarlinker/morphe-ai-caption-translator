# N61 字幕框越界、档位抖动、Shorts 字号/行高与图层（1.5.6 本地构建）

> 状态：**已实现并构建 `local-1.5.6-n61`**；未签名、未安装、未发布。
>
> 输入诊断：`D:\HONOR Share\Honor Share\caption-diagnostics-1.6.1-20261022-230359.txt`
> （2,450,118 B，导出 2026-10-22 23:03:59；`ui=1.6.1`，`build=local-1.6.1-n60`，
> `Display text debug: on`）。用户复测的六点现象见 §1。

## 1. 一句话

六点现象由三个共享机制造成，不是六个独立 bug：

1. **呈现档位（presentation class）在转场/旋转的瞬时帧上提交**，把横向大画面尺寸带进了详情页；
2. **档位比例用当前 Surface 的宽高比反推全屏宽**，画面可见高度一变，`FULL`/`REDUCED` 就来回翻；
3. **Shorts 被当成全屏档、字幕框高度取整个事件的最大行数、字幕层仍在控件之下**。

修复把档位变成“静止视口的事实”，把框宽夹到真实显示画面，把 Shorts 固定为详情字号，把行高还给当前页，并把字幕移到控件之上。

## 2. 诊断证据

### 2.1 详情页字幕框越界（现象一）

`1792680454531` 的档位记录：

```
CAPTION_PRESENTATION_CLASS | class=DETAIL;video=231 0 2366 853;screen=1264x2736;
full_width=1264;class_frame_width=1964;glyph_height_px=44.5;reduced_width=2135
```

31 ms 后同一帧的水平放置：

```
CAPTION_HORIZONTAL_PLACEMENT | video_rect=231 0 2366 853;
caption_outer_rect=-350.0,538.0,1614.0,629.0
```

屏幕只有 1264 px 宽，锚点却是 1964 px，左边界 -350、右边界 1614。随后视频已经变成
`0 0 1264 711`，`class_frame_width_px=1964` 一直保持到本次会话结束（`1792681405302` 等
大量 `REBUILD_PRESENTED`）。这就是“详情页字幕框可能超出屏幕边缘”。

根因：从横屏转回竖屏时，`screenWidth <= screenHeight` 立即把档位判成 `DETAIL`，但此时
`video` 还是横向的 2135×853（宿主 2599×1201，仍在缩放转场中）。`classFrameWidth` 用这个
瞬时宽度算成 `2135 × .92 = 1964`，档位之后不再重算，于是详情页一直用 1964 排版/居中。
只要某页文本接近 1922 px 的 `available_width_px`，它就会真的画出屏幕。

### 2.2 横屏评论动画卡顿与档位抖动（现象二）

整份诊断 124 条 `CAPTION_PRESENTATION_CLASS` 里有 **121 次档位翻转**。最密集的一段
（`1792681385146`–`1792681403470`，约 18 s）翻了 13 次：

```
1792681387096 REDUCED video=231 0 2366 1009 full_width=2675
1792681387724 FULL    video=231 0 2366 1118 full_width=2414
1792681389582 REDUCED video=231 0 2366 1010 full_width=2672
1792681391670 FULL    video=231 0 2366 1116 full_width=2418
...
```

注意视频宽度始终是 2135 px，只有可见高度在 1009↔1118 之间变化；旧实现用
`fullVideoWidth = min(screenWidth, screenHeight × videoAspect)` 反推全屏宽，宽高比一变，
比例就在 `.798` 与 `.884` 之间跳，正好跨过 `.80/.88` 两个阈值。每次翻转都 `forceContent`
重新分页、`setText`、`measure`、`setLayoutParams`，叠加在 YouTube 自己的评论区 resize
动画上，就是“打开评论区非常卡顿”。Shorts 评论同理，只是幅度小。

### 2.3 全屏下滑时字幕提前变小（现象四）

同一组记录里，`DETAIL` 是在 `video=231 0 2366 853`、`screen=1264x2736` 时提交的：屏幕已经
转成竖屏，但画面仍是横向大画面，滑动尚未结束。旧分类只看屏幕方向，不看“视口是否静止”
和官方播放器是否在滑动，于是字号先缩到 44.5，随后画面才落到详情页。

### 2.4 Shorts 字号偏大（现象五）

Shorts 的 `SHORTS_FULL` 被 `detailSize()` 判成“非详情档”，走全屏基准：

```
text_size_px=60.717945; target_glyph_height_px=55.499996; presentation_class=SHORTS_FULL
```

但 Shorts 的画面宽度就是屏幕宽度 1264，按用户“视频显示宽度优先”的规则应与详情页同一档
（44.5）。评论打开后 `SHORTS_REDUCED` 才变 44.5，于是又出现一次“打开评论字号变化”。

### 2.5 Shorts 单行字幕却是两行高（现象三）

```
1792681202356 ... stable_text_width_px=764;stable_lines=2;lines=1;
page=1/3;text=网上有很多视频声称，
1792681206850 ... stable_lines=2;lines=1;page=3/3;text=就能拼出一个完整的苹果。
```

`stable_lines` 取的是**整个事件所有页的最大行数**（该事件有一页是两行），再把它作为所有页的
`TextView.minHeight`。单行页因此继承了双行背景高度。`applyVisibleText()` 的 `minLines`
参数没有区分“当前页行数”与“事件最大行数”。

### 2.6 图层（现象六）

N58 把视觉层放在 `inset_overlay_view_layout` 的 index 0（控件内容层），所以字幕在
`youtube_controls_overlay` 之下；N56–N58 的“字幕在播放器 UI 之下”是上一轮的决定。用户现在
明确要求反过来：控件不能盖住字幕。N58 另加的 `player_overlays_custom_views_container`
全屏透明触摸代理本来就在控件之上；把视觉层也放到这一层即可，代理不再需要。

## 3. 修复

### 3.1 档位只认静止视口，且比例用固定 16:9 参考宽

- `CaptionPresentationClass.referenceVideoWidth()`：横屏参考宽 =
  `min(screenWidth, round(screenHeight × 16/9))`；不再用当前 Surface 的可见高宽比。
  同一 2135 px 宽的画面，无论可见高度是 1009 还是 1118，比例都是 `.95`，稳定在 `FULL`。
- `CaptionOverlay.updatePresentationClass()`：档位变化要同时满足
  - 几何已静止（`SETTLE_MS = 120` 的静默窗）；
  - 官方播放器类型不含 `SLIDING`（详情↔全屏拖动、全屏下滑都属于滑动转场）；
  - 新档位候选持续 `CLASS_SETTLE_MS = 220`；
  
  才提交一次。滑动中、评论区动画中不再改字号/重分页；用户没滑到底就不会提前缩小。
- 竖屏时如果画面仍比屏幕宽（旋转转场的旧横向 Surface），保留上一档，不再直接判
  `DETAIL`；`layoutVideoWidth(DETAIL/SHORTS)` 也被屏幕宽度夹住。

### 3.2 框宽永远不超过实际画面和屏幕

- 渲染时有效框宽 = `min(classFrameWidth, video.width(), screenWidth)`；`placeVisibleText()`
  与 `computeTarget()` 再做一次夹取，锚点不会出现负 left。
- `canReuseCommittedPage()` 增加 `layoutBudget.width == inner`：视频真的变窄时在静止后重排
  一次，而不是继续用旧的宽页。
- `REDUCED` 记住的宽度被夹在 `0.80 × 参考宽` 以内，不会把 2135 px 这种“伪 reduced”宽度
  带进下一次评论区；进入 `FULL` 时清空记忆。

### 3.3 Shorts 固定详情字号

- Shorts 的 `classify()` 始终返回 `SHORTS_FULL`；`detailSize(SHORTS_FULL)` 为 true，
  字号 44.5。评论打开只改变视频矩形和位置，不改变字号、框宽或分页计划。
- `debugDetail()` 的 `size_mode` 改用同一个 `detailSize()`，不再出现
  “`SHORTS_FULL` + size_mode=detail 但字形 55.5”的自相矛盾诊断。

### 3.4 行高属于当前页

- `applyVisibleText()` 的 `minHeight` 改由**当前页的 `linePlan.line2Px`** 决定
  （第二行有内容才两行），不再使用事件级的 `stableTextLines`。
- `stableTextWidth` 仍取事件内最大行宽，翻页时框宽保持稳定；只有高度随当前页 1/2 行变化。
- `render()` / `advancePreparedPage()` 在显示每页前把 `stableTextLines` 设为该页行数。

### 3.5 字幕移到控件之上

- `CaptionSurface.stableCaptionHost()` 优先 `player_overlays_custom_views_container`
  （Shorts 为 `reel_player_overlay_root`），不再优先 `inset_overlay_view_layout`；
  自定义容器是 `player_overlays` 中位于 `youtube_controls_overlay` 之后的层，评论面板仍是
  播放器之后的兄弟，所以评论仍能盖住字幕。
- 视觉锚点追加到该容器末尾（`topmostOverlayHost`），字幕自己的 `TextView` 直接接收
  `ACTION_DOWN`；`ensureTouchProxy()` 在视觉层与触摸层相同时直接移除全屏代理，少一个
  覆盖整屏的 View。
- 保留代理作为“只能用 `inset_overlay_view_layout` 的旧版宿主”的 fallback。

## 4. 验证

- 目标契约：`CaptionPresentationClassTest`（新增旋转/letterbox 高度不翻转）、
  `CaptionLayerOverlayTest`（新增单行页不继承双行高）、`CaptionStableHostTest`（最上层宿主）、
  `CaptionTouchProxyTest`、`CaptionMotionContractTest`。
- 全部 `:extensions:extension:testDebugUnitTest`：**287 tests / 54 result files**，
  0 failures / 0 errors / 0 skipped（2026-10-22 23:32）。
- `:translation-core:testDebugUnitTest` 未改动。
- MPP：`build/n39/gradle/patches/libs/patches-1.5.6.mpp`（见 PROJECT-STATE §34 的最终字节与
  SHA-256）；`captionBuildId=local-1.5.6-n61`；未签名、未安装、未发布。

## 5. 边界

- 档位仍以 16:9 为全屏参考；21:9/4:3 视频的“全屏参考宽”不是逐视频标定。比例在
  `.80–.88` 之间时保持上一档，避免来回跳。
- 详情页视频窄于屏幕（竖屏视频/画中画）时，有效框宽随视频宽度缩小；分页会按新宽度重排，
  这是“视频显示宽度优先”的直接结果。
- 评论全屏时字幕仍被面板盖住；字幕在控件之上意味着它可能压住进度条/时间文字，这是用户
  选择的取舍，可用拖动把字幕移开。
- 未连接真机；以上是诊断 + Robolectric 契约 + 打包验证，真机 after 诊断待用户复验。
