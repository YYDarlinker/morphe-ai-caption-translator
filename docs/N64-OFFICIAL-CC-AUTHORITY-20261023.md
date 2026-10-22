# N64 官方 CC 显示权威：无 CC 不显示 AI 译文字幕

> 状态：**已实现并纳入 1.5.6 本地发布构建**；未签名、未安装。
>
> 输入：用户 2026-10-23 复测：播放过一个自动翻译视频后，切到没有默认译文字幕且没有
> Auto-translate 的视频，Morphe 官方策略是不打开 CC 译文，但本补丁仍显示目标译文字幕。

## 1. 结论

这不是翻译或显示层的问题，而是**可见性权威被两条旁路绕过**：

1. `CaptionChoice` 的目标语言/语言在换视频时保留；`refreshFromCatalog()` 用它 + 新视频的
   源字幕目录启动 AI，即使官方 CC 是关的。
2. `CaptionButtonController.onVideoId()` 只要 AI 开关开就调用 `onCaptionsShown()`；原生轨
   的 `applySelection()` 也会在后台/prefetch selection 到达时直接启动，不等官方 CC 状态。

因此本补丁把“AI 开关开”误当成“字幕显示”，而不是“官方显示时用 AI 样式”。

## 2. 修复

- **换视频清空选择**：`CaptionButtonController.onVideoId()` 在视频变化时把
  `captionIntent` 重置为 `UNKNOWN`，并 `CaptionChoice.reset()`；上一视频的目标语言不能
  成为下一视频的显示依据。
- **官方 CC 是显示门禁**：
  - `NativeCaptionBridge.refreshNativeTrack()` 在 `CaptionButtonController.captionsKnownOn()`
    为 false 时返回 `DEFERRED`，不启动。
  - `refreshFromCatalog()` 同样要求官方 CC 已确认打开。
  - `applySelection()` 在后台/自动轨到达时只保存 selection，不启动可见 AI；等官方 CC
    扫描到 ON 后由 `onCaptionsShown()` 启动。
- **AI 开关只决定绘制方式**：`suppressNativeDraw()` 为 `enabled()`；AI 开时原生字幕窗口不画，
  AI 关时原生正常画。字幕是否出现由官方 CC 决定；CC 关时两边都不显示。
- `CaptionButtonController.onVideoId()` 不再因为 AI 开关开就立即启动；改为等待 220 ms 的
  CC 状态扫描，扫描到 ON 才启动。原生样式在等待期间也不会先行绘制。

## 3. 验收契约

- 官方 CC off + 任何后台/默认 selection：`activations`/`sources` 都为空。
- 官方 CC on + native translated selection：AI 启动，使用该 native target。
- 换视频：`CaptionChoice` 清空，上一个视频的 target 不泄漏到下一个视频。
- AI off：`suppressNativeDraw()` false，原生字幕恢复。

新增 `NativeOnlyCaptionTest.aNativeTrackWhileTheCcControlIsOffDoesNotStartVisibleAiCaptions`
覆盖第一条；`aNativeTranslationBecomesTheCurrentSessionTarget` 覆盖第二条。

## 4. 边界

- 官方 CC 状态来自 `CaptionButtonController` 对真实 CC 控件的扫描/精确控制器状态；扫描前
  可能出现短暂空白，但不会先显示原生样式或错误的目标译文字幕。
- AI 目标语言仍只来自原生 Auto-translate；没有目标时不会伪造翻译。
- 未连接真机；真机 after 诊断待复验。
