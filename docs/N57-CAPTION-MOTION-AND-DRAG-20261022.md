# N57 横屏拖动与字幕框抖动：根因与修复（2026-10-22）

> 状态：**已实现并构建（工作树，`captionBuildId=local-1.5.5-n57`）**；未签名、未连接真机、未发布。
>
> 输入：N56 用户复测反馈（横屏长按拖动仍不正常；评论区开关、详情↔全屏拖动、拖动撤销仍有轻微字幕框/字幕抖动）、
> N53 真机诊断 `caption-diagnostics-1.5.5-20261022-140858.txt`、YouTube 21.20.405 baksmali dump。
>
> 证据：`validation/runs/20261022-n57-motion-touch/`（修复前探针、全量测试、MPP、composition）。

## 0. 结论

两个现象不是“动画曲线”或“触摸阈值”的问题，而是四个各自独立的深层机制：

1. **拖动把“内容提交时的旧视频矩形”当成手势身份。** 保存位置用的横/竖屏方向与 `getRawY` 换算来自
   `previous`（最后一次内容提交的矩形），而放置目标来自实时视频矩形。横屏全屏发生宿主迁移/重挂载后
   `previous` 为空或仍是竖屏矩形，于是保存了竖屏键、放置却读横屏键：手指在动，字幕不动或只跳一下。
2. **内容身份被绑定在瞬时几何上。** 分页 key 含精确的视频宽度；N56 的“80 ms 内第二次变化才算运动”
   启发式把 YouTube 的**慢速分步缩放**（真机日志中约 90–116 ms 一步）当成连续独立场景，每一步都重新
   分页。于是评论区动画中字幕框宽度、文本页、位置反复变化。
3. **几何有两个权威。** 每帧通道用 `currentVideoBounds()`，失败时回退到**播放器容器**；内容通道用
   `videoBounds()`（渲染 Surface）。Surface 短暂不可见时，回退矩形是 letterbox 后的播放器，不是视频；
   下一帧再回到 Surface，形成一帧跳动。
4. **播放器类型用子串判断 compact。** `WATCH_WHILE_SLIDING_MINIMIZED_MAXIMIZED` 含 `MINIMIZED`，
   被当成小窗/隐藏；详情↔全屏拖动和拖动撤销时 authority 进入 `COMPACT`，`setPlayerType()`/`denyDisplay()`
   直接隐藏字幕，随后再恢复——用户看到“抖动/闪一下”。

## 1. 拖动根因：两套几何，两个方向键

N56 的 `drag()`：

```java
if (a == null || previous.height() <= 0 || !anchorVisible() || blocked()) return false;
boolean landscape = previous.width() > previous.height();
float y = initial + (e.getRawY() - downY) / previous.height();
...
Rect b = videoGeometry.isEmpty() ? CaptionSurface.currentVideoBounds(host) : new Rect(videoGeometry);
placeVisibleText(a, host, anchor, text, b, ...);
```

`DeepSeekConfig.captionPositionY(context, landscape)` 只有两个键：`POSITION_LANDSCAPE_Y` 与
`POSITION_PORTRAIT_Y`。拖动保存的键由 `previous` 决定；`placeVisibleText()` 里的
`computeTarget()` 又按 `video.width() > video.height()` 重新决定一次。只要两者不一致，拖动就写错键：

- 横屏全屏的宿主迁移分支会 `previous.setEmpty()`；在下一帧内容提交前长按，`previous.height()==0`
  直接 `return false`，手势根本不会进入拖动。
- 如果 `previous` 保留的是竖屏详情矩形（例如刚完成上拖进入全屏、内容被 motion gate 挡住），
  `landscape=false`：手指上拖把 `POSITION_PORTRAIT_Y` 改小，而放置读 `POSITION_LANDSCAPE_Y`
  （仍是默认 0.80），字幕看起来“拖不动”。
- 反向（竖屏里残留横屏矩形）同样成立。

次要但真实的机制：`drag()` 没有在 `ACTION_DOWN` 调用
`ViewParent.requestDisallowInterceptTouchEvent(true)`。YouTube 的播放器/外层手势可以在长按后拦截
`ACTION_MOVE` 并给 TextView 发 `ACTION_CANCEL`，拖动提前终止。

### 修复

- `ACTION_DOWN` 读取**实时**视频矩形（`currentVideoBounds`，缺失时才用最后一次 `videoGeometry`），
  存入 `dragBounds`；方向 `dragLandscape` 从同一个矩形得到。
- 保存位置、`getRawY` 换算、`computeTarget` 全部使用 `dragBounds` 与 `dragLandscape`；方向键与
  放置方向不可能再分叉。
- `ACTION_DOWN` 立即 `requestDisallowInterceptTouchEvent(true)`；`UP/CANCEL/hide/denyDisplay` 统一
  `stopDrag()` 解除，不再让父级把手势从中间截走。
- 长按本身用 350 ms 定时器 `ARM_DRAG` 触发（含触觉反馈），不再依赖“第一次 MOVE 恰好晚于 350 ms”。
- `previous` 不再参与拖动；它只用于内容去重。

## 2. 抖动根因 A：内容身份绑定瞬时几何

`currentPlan()` 的 key 包含 `budget.width`（视频宽 × 0.92 − padding）。N56 试图用 `moving()` 挡住动画，
但判据是：

```java
if (geometryKnown && now - geometryChangedAt <= MOTION_GAP_MS) geometryMotion = true;
```

真机诊断（N53，评论区开关）里连续记录的视频矩形变化间隔约 **90–116 ms**，大于
`MOTION_GAP_MS=80`，因此每一步都被当成新稳定场景；`render()` 重新计算 `inner`，新 key、新后台分页，
分页就绪后 `applyVisibleText()` 改变 `TextView` 宽度，anchor 再居中。用户看到的就是“字幕框一缩一缩、
文本页跳”。

同一份诊断的 `REBUILD_PRESENTATION`/`REBUILD_PRESENTED` 在约 1.6 s 内出现
`available_width_px=722 → 1082 → 866`，可见框 `764 → 1124 → 908`，即多次内容提交，而不是一次。

### 修复：内容事实与几何事实分离

新增 `contentWidth`（可见 TextView 的实测宽度）和 `canReuseCommittedPage()`：

```java
page.linePlan.line1Px <= inner
    && page.linePlan.line2Px <= inner
    && contentWidth <= inner + padding
```

只要已提交页面的行宽和可见框仍放得进新视频矩形，`render()` 就**不**创建新 `planBudget`、不调用
`currentPlan()`、不 `applyVisibleText()`；只调用 `placeVisibleText()` 移动 anchor。`anchor` 仍是
全宽（用于居中/触摸外框），`TextView` 保持原宽度与换行，所以视觉上字幕框和文本都不变。

只有在以下情况才重新分页：

- 新事件/新文本（`pendingPages` 已清空）；
- 字号/语言/模式变化（`preferred` 或 `measurementKey` 变化）；
- 当前页的行宽或可见框超过新的 `inner`（视频真的窄到放不下）。

这条规则把“评论区打开/关闭”“横屏详情↔全屏拖动”从“每次几何变化都重排”变成“几何只移动”。
若视频窄到装不下当前页，仍会在停稳后**一次**重排——这是语义上必要的重排，不是抖动。

## 3. 抖动根因 B：第一个几何观察被当成运动

`moving()` 改成“最后一次变化后 120 ms 内都算运动”后，`noteGeometry()` 第一次看到矩形时也把
`geometryChangedAt` 设为当前时间，于是**首次渲染**在 120 ms 内被 motion gate 挡住。测试中表现为
首帧字幕 4 s 不出现；真机上首条字幕会晚约 120 ms，或与分页回调错位。修复：第一次观察直接视为已停稳
（`geometryChangedAt = now - SETTLE_MS`），只有**后续变化**才开启窗口。

## 4. 抖动根因 C：两套几何权威

N56：

```java
// geometryFrame
Rect video = CaptionSurface.currentVideoBounds(host);
// currentVideoBounds 内部：cachedSurface 不可见时 return relative(player, host);
```

```java
// render
Rect b = CaptionSurface.videoBounds(host); // renderedSurface 优先，否则 player
```

`currentVideoBounds()` 在 Surface 短暂不可见时回退到**播放器容器**；`videoBounds()` 优先真实
Surface。两者在播放器重建/转场的一帧里可能不同（播放器含 letterbox，Surface 只含画面），
`geometryFrame()` 与 `render()` 交替读两个矩形，字幕上下跳 1 帧。

### 修复：单一 Surface 权威

- `currentVideoBounds()` 只读缓存的渲染 Surface；不可见就返回 `null`，调用方保持上一帧。
- 新增 `refreshedVideoBounds()`：只发现**真实 Surface** 的矩形，找不到返回 `null`；**永不**用播放器
  容器代替视频。
- `render()` 在缓存不可用时用 `refreshedVideoBounds()` 发现新 Surface；仍为空则保留已验证页面。

## 5. 抖动根因 D：滑动类型被误判为 compact

`CaptionPlayerAuthority.isCompactType()` 旧实现：

```java
type.contains("MINIMAL") || type.contains("MINIMIZED") || type.contains("PICTURE_IN_PICTURE")
```

官方 enum 同时有：

- `WATCH_WHILE_MINIMIZED`（真的小窗，应 compact）
- `WATCH_WHILE_SLIDING_MINIMIZED_MAXIMIZED`（**常规播放器**在小窗→最大化之间滑动）
- `WATCH_WHILE_SLIDING_MAXIMIZED_FULLSCREEN`（常规播放器在最大化→全屏之间滑动）
- `WATCH_WHILE_SLIDING_MINIMIZED_DISMISSED`（滑向消失，应 compact）

真机 N53 诊断明确记录：

```
1792649332309 | PLAYER_TRANSITION_STABLE | type=WATCH_WHILE_SLIDING_MINIMIZED_MAXIMIZED;state=COMPACT
```

即详情↔全屏拖动/撤销时 authority 进入 `COMPACT`；`setPlayerType()` 立即 `hideView()`，
`denyDisplay()` 还会撤销已绘制字幕。随后类型回到 `WATCH_WHILE_MAXIMIZED`/`WATCH_WHILE_FULLSCREEN`
再恢复，形成隐藏/重现。

### 修复：按精确枚举分类

```java
NONE, HIDDEN, WATCH_WHILE_MINIMIZED, WATCH_WHILE_PICTURE_IN_PICTURE,
WATCH_WHILE_SLIDING_MINIMIZED_DISMISSED, INLINE_MINIMAL -> compact
其他（含所有 ..._SLIDING_..._MAXIMIZED/FULLSCREEN） -> 非 compact
```

`WATCH_WHILE_SLIDING_MINIMIZED_MAXIMIZED` 从 `REGULAR` 进入时保持 `REGULAR`，不再隐藏；从
`COMPACT` 进入时仍走 `TRANSITIONING_TO_REGULAR`，由几何稳定探针恢复，符合“先否定、后恢复”的
安全顺序。

## 6. 实现摘要

| 文件 | 变更 |
|---|---|
| `ui/.../CaptionOverlayV2.java` | `contentWidth` + `canReuseCommittedPage()`；几何只移动、放得下不重排；首次观察视为已停稳；`moving()` 改为 120 ms 静默窗；拖动用实时 `dragBounds/dragLandscape`，DOWN 抢占手势，350 ms 定时长按；`render()` 不再用播放器容器替代视频；`stopDrag()` 统一清理。 |
| `application-host/.../CaptionSurface.java` | `currentVideoBounds()` 只读缓存 Surface、不可见返回 null；新增 `refreshedVideoBounds()` 只发现真实 Surface，绝不回退播放器容器。 |
| `application-host/.../CaptionPlayerAuthority.java` | `isCompactType()` 改为精确枚举；滑动类型不再 compact。 |
| `extensions/.../CaptionMotionContractTest.java` | 新增“放得下的几何不重排/框宽不变”“横屏拖动用实时矩形与实时方向键”。 |
| `extensions/.../CaptionPlayerTypeContractTest.java` | 新增滑动类型/精确 compact 契约。 |
| `extensions/.../CaptionLayerContractTest.java` | 新增“Surface 短暂隐藏不替代播放器容器”。 |
| `extensions/.../CaptionLayerOverlayTest.java` | 评论区打开改为**不强制 refreshStyle**：放得下时不重排；只有缩到 700 px 才重新分页。 |

## 7. 验证

### 7.1 修复前探针（受控 Robolectric，不是真机）

| 探针 | 修复前结果 |
|---|---|
| 连续几何（仍放得下） | `desiredLayout` 从 `…|540|…` 变为 `…|466|…`，即每个宽度都新分页 |
| 横屏拖动 + 旧竖屏 `previous` | 横屏键保持 `0.5`（没有移动），说明写到了竖屏键 |
| 隐藏 Surface | 旧 `currentVideoBounds()` 在缓存 Surface 不可用时返回非 null（回退 player）；真实 `CaptionSurface` 的对照契约见 `CaptionLayerContractTest`（修复后应为 null） |
| `WATCH_WHILE_SLIDING_MINIMIZED_MAXIMIZED` | `state=COMPACT`（应为 `REGULAR`） |

证据：`validation/runs/20261022-n57-motion-touch/probe/probe-before.txt`。

### 7.2 修复后

- `:translation-core:testDebugUnitTest`：**246 tests**，0 failures / 0 errors / 0 skipped。
- `:extensions:extension:testDebugUnitTest`：**281 tests**，0 failures / 0 errors / 0 skipped。
  - `CaptionMotionContractTest`（6）：原有 4 + 放得下不重排 + 横屏拖动实时几何/方向键。
  - `CaptionPlayerTypeContractTest`（2）：滑动类型保持显示权限；只有精确非播放状态 compact。
  - `CaptionLayerContractTest`（6）：含隐藏 Surface 不替代播放器容器。
  - `CaptionLayerOverlayTest`（3）：评论区打开（2247→1478）不重排、不改变可见框；缩到 700 才重新分页。
- 真实 21.20.405 APK + 官方 1.47.0 默认补丁 + 本 MPP：`STRUCTURE_PASS`；
  `hooks` 仍为 `onPlayerType=1, suppressNativeDraw=1, …`，未新增 ABI。
  证据：`validation/runs/20261022-n57-motion-touch/n57-composition-final2/`。

### 7.3 产物

- MPP：`validation/runs/20261022-n57-motion-touch/patches-1.5.5-n57.mpp`
  （与 `build/n39/gradle/patches/libs/patches-1.5.5.mpp` 同一字节）
- 大小：**599,357 B**
- SHA-256：`D6414AC051F1376589BB927F7FF463E7C716EABC7696171C589438C3453EE1B7`
- `captionBuildId=local-1.5.5-n57`；未签名、未安装、未发布。

## 8. 边界

- 未连接真机；以上是 Robolectric 契约 + 真实 APK composition，不是录屏验收。
- 如果某页的可见宽度真的大于缩小后的视频（例如长句页 2067 px，评论区打开后视频 1478 px），
  停稳后仍会重排一次；这是必要的布局变化，不是本轮的瞬时抖动。短页/常见页不会被重排。
- 向下拖动仍受“盒子不能超出视频”的边界限制；向上拖动正常。
- 未实现播放器 UI 避让（用户明确取消）；字幕仍在播放器 UI 之下。
- 若真机上仍出现“长按无反应”，下一步应在拖动入口增加只读 `CAPTION_TOUCH` 诊断（DOWN/MOVE/CANCEL
  + 父链），确认触摸是否到达 TextView；本轮已消除代码内可知的方向键/几何错配与父级拦截。
