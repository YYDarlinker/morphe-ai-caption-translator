# N56 字幕框动画回归修复（N55 后续，2026-10-22）

> **历史说明：** 本文件描述的 N56 行为已被
> [`docs/N57-CAPTION-MOTION-AND-DRAG-20261022.md`](N57-CAPTION-MOTION-AND-DRAG-20261022.md) 取代：
> N57 把内容与几何分离（放得下不重排）、拖动改用实时矩形/方向键、修正滑动 PlayerType 与单一
> Surface 几何权威。N56 保留为历史证据，不再代表当前行为。
>
> 状态：**已实现并构建（工作树，`captionBuildId=local-1.5.5-n56`）**；未签名、未连接真机、未发布。
>
> 输入：用户截图 `Screenshot_20261022_172745_app_morphe_android_youtube_MainActivity.jpg`
> （SHA-256 `8690E035A0BAB667756A02F377AB2FD1F3332290B8558443826B76B6925BFEF2`，2736×1264）；
> N55 实现；`docs/N55-CAPTION-MOTION-ROOT-CAUSE-20261022.md`。
>
> 证据：`validation/runs/20261022-n55-motion-root-cause/`（N56 构建输出、composition、探针）。

## 0. 结论

N55 的三个回归都不是分页算法问题，而是**位置提交方式**和**多余的避让层**：

1. **闪烁跳变**：N55 的 `placeAt()` 先把 anchor 的 margin 改成新位置、同时把 translation 清零。Android 在下一帧布局前仍保留旧的 `getLeft()/getTop()`，于是这一帧画在“旧 left + 零 translation”，下一帧布局跳到新 margin —— 一帧旧位置、一帧新位置的闪烁。内容每次重新分页（评论区动画结束、拖动后、页切换）都会触发。
2. **原生评论区动画变卡 / 拖动失效**：N55 每帧读取原生 `SubtitleWindowView` 的矩形并做矩阵映射/安全线 clamp；`native.bottom - boxHeight` 会把字幕顶到安全线，默认位置已经在底部时向下拖动完全没有位移。原生 view 的逐帧 `getGlobalVisibleRect` + 矩阵反转也增加了主线程成本。
3. **拖动失效的第二条路径**：N55 的拖动调用完整 `render()`，会进入 motion gate/内容重排；现在改为直接放置，不触达分页。

本轮按用户决定：

- **删除播放器 UI 避让**（不再读取原生字幕窗口、不再 clamp 安全线）；
- **保留字幕在播放器 UI 之下**（宿主/z 序不变，UI 显示时仍能看到字幕）；
- **恢复上下拖动**；
- 用一条 primitive 统一位置：anchor 永远在宿主 padding 原点，所有位置都是绝对 `translationX/Y`，内容宽度变化只改 LayoutParams 尺寸，不再混用 margin 和 translation。

## 1. 根因

### 1.1 margin / translation 混用造成一帧闪跳

N55 `CaptionHorizontalPlacement.placeAt()`：

```java
// 旧实现
if (outer.getTranslationX() != 0f) outer.setTranslationX(0f);
int margin = left - host.getPaddingLeft() + host.getScrollX();
p.leftMargin = margin; p.topMargin = top;
outer.setLayoutParams(p);
```

`setLayoutParams()` 只登记下一次布局；当前帧的 `getLeft()/getTop()` 仍是旧值，而 translation 已被清零。因此：

- 帧 N：旧 left/top + translation=0 → 画在旧位置；
- 帧 N+1：布局应用新 margin → 画在新位置。

这就是评论区动画结束、长按拖动过程中、页切换时看到的“闪一下再跳”。N55 的每帧 `nudge()` 在动画中掩盖了它；一旦内容提交（动画结束/拖动/换页），就会暴露。

### 1.2 安全线 clamp 让拖动“卡死”

N55 `computeTarget()`：

```java
int safeBottom = min(video.bottom, native.bottom);
top = max(video.top, min(top, safeBottom - height));
```

默认字幕已经接近视频底部时，`safeBottom - height` 就是当前 top；继续向下拖动被 clamp 成同一个值。用户看到的是“拖不动”。原生窗口在 cue 间隙/Shorts 不可用时，回退的 `video.bottom - 24dp` 也会改变默认位置。

### 1.3 逐帧原生几何让评论区动画变卡

N55 `geometryFrame()` 每帧：

- `CaptionSurface.nativeCaptionBounds(host)` → 原生 `SubtitleWindowView.getGlobalVisibleRect`；
- `CaptionSurface.currentVideoBounds(host)` → 视频 Surface 的 global rect；
- `CaptionSurface.relative()` → `Matrix`/`RectF` 分配、反转、mapRect；
- `CaptionSurface.isShorts()`、`DeepSeekConfig.captionPositionY()`。

在 YouTube 自己的评论区动画上叠加这些主线程工作，帧预算被挤占。N55 同时把 `CaptionHorizontalPlacement.placeAt` 的 `isMarginRelative` 条件放在每帧路径上，也可能反复 `setLayoutParams`。

### 1.4 拖动路径过重

N55 拖动每移动一次执行 `dirty=true; render()`：可能进入 motion gate、重新分页、重置内容，和“只改 y”这个需求不匹配。

## 2. 本轮修改

### 2.1 唯一位置 primitive：绝对 translation

`ui/.../CaptionHorizontalPlacement.java` 新增：

```java
static void position(FrameLayout host, FrameLayout outer,
                     int left, int top, int width, int height)
```

- anchor 的 margin 永远为 0，位置 = 宿主 padding 原点 + `translationX/Y`；
- 只有 width/height 变化时才新建 `FrameLayout.LayoutParams`（不设置 relative margin，不每帧 `setLayoutParams`）；
- 位置变化只改 translation，不再把 margin 和 translation 组合在一起。

`CaptionOverlay.placeVisibleText()` 和每帧 `followVisibleText()` 都调用它：

- 内容提交：更新 `boxWidth/boxHeight` + 目标 left/top；
- 每帧几何：只更新目标 left/top；
- 拖动：直接调用 `placeVisibleText()`，不 `render()`、不重新分页。

### 2.2 删除原生字幕几何/安全线

- 删除 `CaptionSurface.nativeCaptionView`、`nativeCaptionBounds()`、`nativeCaptionWindow()`；
- 删除 `NativeCaptionBridge.onNativeCaptionWindow()` 及其诊断；
- 删除 `NativeCaptionBridgePatch.hookNativeCaptionWindow()`；
- `docs/core-provenance.json` 移除新 ABI；
- `computeTarget()` 只按视频矩形和用户 y 比例计算，`top` 仅 clamp 在视频上下边界内，不再读取原生窗口；
- 字幕仍在视频平面的宿主中，播放器 UI 画在它上面；UI 显示时字幕可见，但不会主动避让。

### 2.3 拖动恢复

`CaptionOverlay.drag()` 的 ACTION_MOVE：

```java
DeepSeekConfig.saveCaptionPosition(...);
Rect b = videoGeometry.isEmpty()
        ? CaptionSurface.currentVideoBounds(host)
        : new Rect(videoGeometry);
placeVisibleText(a, host, anchor, text, b, boxWidth);
```

- 不再 `dirty=true; render()`，不触发分页；
- 直接按新 y 计算目标并 `position()`；
- 向下拖到视频底边时仍受“盒子不能超出视频”的边界限制（与旧版一致），向上拖不受影响。

### 2.4 降低逐帧几何成本

`CaptionSurface.relative()`：

- 复用 `Rect`/`int[]`/`Matrix`/`RectF`，不再每帧分配；
- 宿主未缩放/裁剪时走“屏幕矩形减宿主原点”的快速路径，跳过矩阵反转；
- 只有 scaled/clipped 宿主才走矩阵映射；
- `HOST_TO_SCREEN.reset()` 后再 `transformMatrixToGlobal()`（N55 复用静态 Matrix 时漏了 reset，导致矩形翻倍；本轮已修）；
- `currentVideoBounds()` 仍然只读缓存 view，不扫树。

## 3. 为什么能解决三个现象

| 现象 | N55 | N56 |
|---|---|---|
| 评论区开/关动画结束后闪跳 | margin 改位 + translation 清零，下一帧布局 | 绝对 translation；内容宽度变化不再移动 anchor 原点 |
| 评论区原生动画卡 | 每帧原生 view 矩形 + 矩阵 + 安全线 | 无原生几何；仅视频矩形 + 快速路径；无安全线 clamp |
| 拖动失效 | 安全线 clamp + 完整 render | 删除 clamp；拖动只改 y/translation |
| 进度条避让 | 有（但 clamp 副作用） | **按用户决定删除**；字幕仍在播放器 UI 之下 |

## 4. 验证

- `:translation-core:testDebugUnitTest`：**246 tests**，0 failures / 0 errors / 0 skipped。
- `:extensions:extension:testDebugUnitTest`：**276 tests**，0 failures / 0 errors / 0 skipped。
  - `CaptionMotionContractTest`（4）：连续几何只 translation、不换页；停稳后只提交一次；`WATCH` 恒 true；上下拖动改变位置；短暂无 Surface 不闪空。
  - `N37R2PlacementContractTest`（8）：中心对齐、RTL/方向、相同位置不重复布局、场景矩阵。
  - `N37R2VideoRectTest` / `VideoViewportR7Test` / `CaptionLayerContractTest`：裁剪、Surface 交换、缩放播放器、宿主层级。
- 真实 21.20.405 APK + 官方 1.47.0 默认补丁 + 本 MPP 的 structure-only composition：
  `STRUCTURE_PASS`；hooks 中不再有 `onNativeCaptionWindow`，其余 hook 数量不变。
  证据 `validation/runs/20261022-n55-motion-root-cause/n56-composition/`。
- MPP：`build/n39/gradle/patches/libs/patches-1.5.5.mpp` **598,347 B**，
  SHA-256 `C374D49ED6F5A90FCD3AA1553B038CF7608123F04157EE40655FC2538D065FCB`；
  同一字节副本 `validation/runs/20261022-n55-motion-root-cause/patches-1.5.5-n56.mpp`；
  `captionBuildId=local-1.5.5-n56`；未签名、未安装、未发布。

## 5. 边界

- 未连接真机；评论区动画/拖动/闪烁仍需用户安装后录屏确认。
- 内容在停稳后仍会重新分页一次（N53 的“按分句重新分页”语义保留）；本轮保证**位置**不因 margin/translation 混用而闪跳，但如果新分页导致文本本身变化，那是内容更新，不是位置跳变。
- 向下拖动仍受“盒子不能超出视频”的边界限制；默认位置靠近底部时继续向下没有空间，向上拖动正常。
- 未实现播放器 UI 避让（用户明确取消）；字幕位于播放器 UI 之下。
- N55 的 `docs/N55-CAPTION-MOTION-ROOT-CAUSE-20261022.md` 保留为历史；其原生安全线实现已由本文件取代。
