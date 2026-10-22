# N62 评论区字号切换、AI 开关即刻生效与短字幕框宽（1.5.6 本地构建）

> 状态：**已实现并构建 `local-1.5.6-n62`**；未签名、未安装、未发布。
>
> 输入诊断：
> `D:\HONOR Share\Honor Share\caption-diagnostics-1.5.6-20261022-234413.txt`（657,955 B，
> `Mode: automatic translation`）与
> `D:\HONOR Share\Honor Share\caption-diagnostics-1.5.6-20261022-234714.txt`（781,130 B，
> `Mode: original captions (no translation API)`）。用户复测三点见 §1。

## 1. 一句话

三个现象分别是三个独立机制：

1. N61 为了“不在滑动中提前缩字”，把档位切换推迟到视口完全静止（`SETTLE_MS=120` +
   `CLASS_SETTLE_MS=220`），于是评论区开关的动画结束后字号才变；现在参考宽已经稳定，
   只需要 80 ms 的候选确认，动画中段即可提交一次。
2. AI 开关的“开”只改了设置，真正的引擎启动仍被 `CaptionChoice.userOff`、严格的原生
   对象校验和一个永不重试的 `DEFERRED` 卡住；字幕目录明明已经在内存里，也没有用它启动。
3. 字幕 TextView 的宽度是事件内最大行宽（稳定框），背景又画在整个 TextView 上，所以短页
   永远显示最宽页的底色；改为在 `onDraw()` 里按**当前页实际行宽**画背景。

## 2. 评论区字号：为什么慢，怎么快

### 2.1 根因

N61 的 `classTransitionAllowed()` 要求 `!moving(now)`，而 `moving()` 是“最后一次几何变化后
120 ms 内”。评论区动画每一步都在改视频矩形，所以档位候选只能在动画完全停稳后开始计时；
再加 `CLASS_SETTLE_MS=220`，用户看到的是“评论区打开/关闭完成后才变字号”。

### 2.2 修复

- 参考宽已改为固定 16:9（N61），同一视频宽度不再因 letterbox 高度抖动而翻档；因此档位
  不再需要等视口静止。
- `CLASS_SETTLE_MS` 从 220 降到 **80 ms**；`classTransitionAllowed()` 只排除
  `COMPACT`/`TRANSITIONING_TO_REGULAR` 和官方 `SLIDING` 播放器类型（全屏↔详情拖动）。
- 阈值收紧为进入 `.80`、退出 `.82`（原来 `.80/.88`），关闭评论区时更早回到全屏档。
- `FULL` 的 `classFrameWidth` 固定取 16:9 参考宽，不再从收起动画的第一帧捕获（否则关闭后
  会永久偏窄）；实际渲染仍用 `min(classFrameWidth, videoWidth × 模式边距, screenWidth)`，
  中间帧不会越界。
- 一次档位切换仍只提交一次分页；视觉层在 `player_overlays_custom_views_container` 内，
  重排不触碰 YouTube 控件树。测试中的 `openingTheSheetSwitchesToDetailSize...` 仍在
  200 ms 后看到一次新计划。

### 2.3 边界

- 官方播放器报告 `SLIDING` 的“全屏↔详情”拖动仍然不会提前换档，这是上一轮的要求。
- 关闭评论区时，字号在动画中段切到全屏，框宽可能在视频完全展开后有一次正常的
  `canReuseCommittedPage` 宽度修正；这不再是“等动画结束才变字号”。

## 3. AI 开关：为什么有时只“显示开启”

### 3.1 诊断证据

第一份诊断（自动翻译）连续四次开关：

```
1792683774078 | ENGINE_MODE_SAVED | enabled=true;native=DEFERRED;session=false;choice_known=true
1792683777017 | ENGINE_MODE_SAVED | enabled=false;native=CAPTIONS_OFF;session=false;choice_known=true
1792683779483 | ENGINE_MODE_SAVED | enabled=true;native=DEFERRED;session=false;choice_known=true
1792683797146 | ENGINE_MODE_SAVED | enabled=true;native=DEFERRED;session=false;choice_known=true
```

当前视频 `g22Z__ShvVY` 只有一条后台 `NATIVE_TRACK_APPLIED translated=true`，没有前台
applied 事件；开关返回 `DEFERRED` 后没有任何重试。下一视频 `IqcS1d3eXYc` 切换后才开始
source fetch，用户看到的是“开关显示开，但没有字幕”。

第二份诊断（原文模式）里：

```
1792683973693 | ENGINE_MODE_SAVED | enabled=true;native=APPLIED;session=true;choice_known=true
1792684005738 | ENGINE_NATIVE_ACTIVATED | target=zh-Hans;translated=false
```

即引擎确实启动了，但当前原生选择是 `zh-Hans/manual` **源轨**（`translated=false`），
所以只显示原文；如果用户期望 AI 翻译，这看起来也像“没开”。

### 3.2 根因

1. `CaptionQuickToggle.setEngine(true)` 只保存 `DeepSeekConfig.enabled`，没有清
   `CaptionChoice.userOff()`/`explicitOff()`。此前 CC 按钮或原生菜单的一次 Off 会把
   `refreshNativeTrack()` 的第一行直接变成 `CAPTIONS_OFF`/`DEFERRED`。
2. `refreshNativeTrack()` 的 `canActivateSnapshot()` 在原生 manager/track 正在重建时
   返回 false；但内存里已有该视频的签名 selection descriptor，函数仍直接 `DEFERRED`，
   不尝试用 descriptor 启动。
3. `onCaptionsShown()` 要求 `currentSelection()!=null`，否则直接返回；没有“无原生
   translated track 时用源目录 + 当前 native target 启动”的路径。
4. `DEFERRED` 没有重试：只有下一次原生事件或换视频才会再尝试。
5. 原生选择是源轨（`translated=false`）时，AI 按契约显示源字幕，不会凭空翻译；需要用户
   在原生 Auto-translate 里选目标语言。诊断里的 `translated=false` 就是这种情况。

### 3.3 修复

- `setEngine(true)` 先 `CaptionChoice.toggle(true)`：清掉旧的 CC-Off 锁存，但不创建新语言
  选择；关闭 AI 时保留原选择，下一次开启可以直接采用。
- `refreshNativeTrack()`：`canActivateSnapshot()` 失败时，如果该视频的 signed descriptor
  仍对应当前视频且是 timed-text URL，记录 `NATIVE_REFRESH_DESCRIPTOR_FALLBACK` 并立即启动，
  不再等 manager/track 对象重建。
- 新增 `NativeCaptionBridge.refreshFromCatalog()`：没有可提交的原生 translated track 时，
  用当前 `CaptionChoice.targetLanguage()`（仍然来自原生选择，不是新的语言记忆）和
  `SourceCatalog` 里的源轨启动；目标语言已有源轨时显示源字幕，否则走翻译。
- `onCaptionsShown()` 在 `currentSelection()==null` 或 `refreshNativeTrack()==DEFERRED` 时
  也调用 `refreshFromCatalog()`。
- `CaptionQuickToggle` 在 `DEFERRED` 后安排 120 / 450 / 1200 ms 三次重试
  （`ENGINE_ACTIVATION_RETRY`）；成功即停止，关闭 AI 即取消。
- 以上都写诊断，下一次真机日志可以直接看到 `NATIVE_REFRESH_DESCRIPTOR_FALLBACK`、
  `ENGINE_CATALOG_ACTIVATED`、`ENGINE_ACTIVATION_RETRY`。

### 3.4 边界

- 如果用户从未在 YouTube 原生 Auto-translate 里选过目标语言，`CaptionChoice.targetLanguage()`
  为空；任何实现都不能凭空生成目标语言，此时仍会等待原生选择。开关本身会被保存，但不会
  伪造翻译。
- 原生选择是源轨（`translated=false`）时，AI 选择原文显示是正确的；要翻译必须在原生菜单
  选择 Auto-translate 目标。
- 没有连接真机，重试/descriptor/catalog 路径是代码与诊断契约验证，不是设备录屏。

## 4. 短字幕框宽：为什么总是最宽

### 4.1 根因

`render()` 把事件所有页的最大行宽写入 `stableTextWidth`，`applyVisibleText()` 用它作为
TextView 宽度；`text.setBackground(captionBackground)` 又让背景铺满这个 TextView。因此
一个 3 页事件里只要有一页很长，短页也会显示最宽页的整块底色。诊断里
`stable_text_width_px=428` 远大于 `measured_width_px` 的短页就是同一形态。

### 4.2 修复

- TextView 仍保持 `stableTextWidth` 的稳定布局宽度（翻页不重新 measure/布局，不回到
  “一帧旧框”）。
- 背景不再挂在 TextView 上；`CaptionText.onDraw()` 按当前页
  `Layout.getLineWidth()` 的最大值 + 左右 padding 画圆角背景，水平居中。短页的背景就
  只有文字那么宽，长页才会变宽。
- `text.setBackground(null)`；背景颜色/透明度/圆角仍由 `captionBackground` 统一设置。
- 诊断 `drawable_width` 会变为 0（背景不再是 View background），但
  `measured_width_px`/`text` 仍能核对实际文字宽度。

### 4.3 边界

- 视图的布局宽度仍是事件最大行宽（为了不抖动），触摸/拖动命中区也按这个宽度；可见背景
  已按文字收缩。如果以后希望命中区也贴合文字，需要另做触摸矩形，不在本轮。
- 背景按每行 `getLineWidth()` 取最大值，包含行内标点，不包含 TextView 尾部空白。

## 5. 验证

- 全量 `:extensions:extension:testDebugUnitTest`：**287 tests / 54 result files**，
  0 failures / 0 errors / 0 skipped（2026-10-23 00:00）。
- 目标回归：
  - `CaptionPresentationClassTest`：`EXIT_REDUCED=.82` 的迟滞、旋转旧横向帧不判 DETAIL、
    letterbox 高度不翻转、Shorts 恒为详情字号。
  - `CaptionLayerOverlayTest`：单行页不继承双行高、评论区一次新计划、窄视频按
    `video × .92` 重投影。
  - `QuickCaptionToggleTest`：AI-on 清除旧 CC-Off 锁存；`NativeOnlyCaptionTest` 保持
    原生选择契约。
- 本地化 232 keys × 14 locales；`verifyIntegrationSources` `errors=[]`。
- MPP：`build/n39/gradle/patches/libs/patches-1.5.6.mpp`，见 PROJECT-STATE §35 的最终
  字节/SHA-256；`captionBuildId=local-1.5.6-n62`；未签名、未安装、未发布。
