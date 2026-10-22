# N53 字幕图层与字号契约修复（1.5.5 工作树）

> 状态：根因调查 + 通用修复已实施；版本仍为 **1.5.5**，工作构建
> `captionBuildId=local-1.5.5-n53`，**未发布**、未签名、未连接真机。

## 0. 输入

- 诊断：`D:\HONOR Share\Honor Share\caption-diagnostics-1.5.5-20261022-140858.txt`
  （1,418,251 B，导出 2026-10-22 14:08:58；会话 130，视频 `TXXOlOdV0Ys`，目标 `zh-Hans`，
  模型 `qwen3.7-flash`；观看 215 s，57 次 API，56 次 2xx，3 次失败）。
- 截图：`128(1).jpg`（评论列表已拖到全屏，字幕框压住置顶评论）与 `127(1).jpg`
  （评论半展开，字幕在视频底部）。
- 证据目录：`validation/runs/20261022-n53-caption-layer/`（块 105 过滤记录、字号耦合记录、
  产物哈希与说明）。
- 用户报障：
  1. 有一段没有字幕；
  2. 评论区拖上去后字幕仍显示在评论区**之上**；
  3. 全屏打开评论区时字号随画面同比缩小，希望像全屏↔详情切换那样只作微小变化，并按分句重新分页。

## 1. “有一段没有字幕”：供应商内容过滤拒绝，不是渲染闸门

### 1.1 现场

诊断里唯一一段持续缺少译文的区间是块 `b105_1637_1677`：

- 范围 `588520–603080 ms`（14.56 s），4 条人工 cue：170 `588520–591280`、
  171 `591280–595120`、172 `595120–599160`、173 `599160–603080`。
- 整块预取请求 171（`REBUILD_REQUEST ... b105_1637_1677; range=588520-603080`）
  在 14:06:42 返回 **HTTP 400 `content_filter`**：
  `REBUILD_HTTP_FAILURE request=171;block=105;reason=content_filter`，
  `REBUILD_EVENTS_REJECTED block=105;reason=content_filter`。
- N52 的页级补请求随后按当前页发出四次：

| 请求 | 源范围（cue） | 结果 |
|---|---|---|
| 175 | 1637–1644（cue 170） | 400 `content_filter` → `REBUILD_PAGE_REPAIR_FAILED` |
| 176 | 1645–1656（cue 171） | 200 → `REBUILD_PAGE_REPAIRED events=2` |
| 178 | 1657–1665（cue 172） | 400 `content_filter` → `REBUILD_PAGE_REPAIR_FAILED` |
| 179 | 1666–1677（cue 173） | 200 → `REBUILD_PAGE_REPAIRED events=1` |

三次失败全部是同一个供应商内容过滤（诊断头 `Failure breakdown: ... other 3`）。被拒绝的
两个范围按相邻块 token 可还原为 “Two years later, a private school in Hangzhou” 与
“climbed more than 4%. And same in February 2025,”（cue 173 含 “Xi Jinping” 反而通过，
说明不是简单的主题拦截，而是供应商侧不稳定/误判）。

### 1.2 屏幕上的表现

- `588.5–592.7 s`（4.2 s）：上一页 `监管机构也完全没涉及。` 被保持
  （`mode=caption_hold`，`DISPLAY_GAP gate=hold`），覆盖 cue 170 与 cue 171 的前半段；
- `592.7–594.9 s`（2.1 s）：整块已降级（`REBUILD_BLOCK_DEGRADED reason=no_plan`），显示中性
  `…`（`id=130:3:unavailable:105:588520_603080`，`text=…`）；
- `594.9 s`：cue 171 的补译到达（比 cue 起点晚 3.6 s），显示到 `599.0 s`；
- `599.0–602.5 s`（3.5 s）：cue 172 无译文，再次 `…`，同时覆盖 cue 173 的前半段；
- `602.5 s`：cue 173 的补译到达（比 cue 起点晚 3.3 s）。

因此“有一段没有字幕”的准确构成是：**两条 cue 被供应商拒绝（不可译）+ 另外两条 cue 的页级
补请求在播放到达后才发出（晚 3.3–3.6 s）**。整段 588.5–602.5 s（14 s）里没有一刻是排版失败、
闸门误伤或 `source_gap`：要么是上一页被保持，要么是中性 `…`，那些页本来就没有可用译文。

### 1.3 结论与边界

- 显示层按既定契约不显示源文，只显示中性 `…`/保持上一页；这一点是正确的。
- 被拒绝的文本在本地无法凭空生成译文；换模型/供应商或对 `content_filter` 做一次有界重试
  （尤其是无上下文的最小范围重试）属于另一项请求策略变更，本轮未实施。
- 可改进方向（未做）：整块被 `content_filter` 拒绝时，提前对块内各显示组各发一次补请求，
  而不是等页面到期；可把“晚到 3.3–3.6 s”消掉，但被拒绝的两条 cue 仍然无解。

## 2. 评论区之上：字幕挂在了 Activity 内容层，而不是视频平面

### 2.1 根因

`CaptionOverlay.attach()` 把 `anchor` 追加到 `android.R.id.content`（Activity 内容
FrameLayout）的最后一个子 View，并用 `b = CaptionSurface.videoBounds(host)` 在**内容层坐标**
里定位。于是：

- 覆盖层与 YouTube 的整个界面是兄弟关系，且排在最后 → 总是画在最上面；
- 评论面板（YouTube 自己画在播放器分支之后的视图）在层级上低于这个 anchor；
- 截图里字幕框压住置顶评论，就是这个层级的直接结果。

层级事实：如果评论面板是独立窗口，它会在 Activity 窗口之上，旧实现反而会被它盖住；
截图中字幕能盖住面板，说明面板就在 Activity 视图树里，且早于我们的 anchor 绘制。

### 2.2 修复：字幕是视频平面的图层

- `CaptionSurface.renderedSurface(root)`：把“最大可见视频 Surface/TextureView”作为可复用
  的图层锚点（带缓存与布局失效）。
- `CaptionSurface.captionHost(player)`：从视频 Surface 向上找到**第一个 FrameLayout**，
  它就是字幕宿主；没有 Surface 时退回播放器本身；播放器不是 FrameLayout 时返回 `null`，
  调用方退回 Activity 内容层（仅冷启动无播放器的一帧）。
- `CaptionOverlay.attach()`：宿主改为该 FrameLayout；宿主变化时重新挂载；新增诊断
  `CAPTION_LAYER layer=video|activity;host=...;width=...;height=...;children=...`。
- `CaptionSurface.relative()`：屏幕矩形改为经宿主 `transformMatrixToGlobal` 的逆矩阵映射回
  宿主局部坐标，再与宿主局部边界求交。播放器转场对容器做缩放/平移时，字幕不会漂移。
- 效果：App 画在视频之上的任何视图（播放器控件、评论面板、其他面板）都按正常绘制顺序
  盖住字幕；播放器转场带动视频时字幕跟着走。

## 3. 评论区改变字号：字号被绑到了瞬时视频矩形

### 3.1 根因

`SubtitleStyleMetrics.renderedGlyphHeightPx(tier, screenWidth, fullScreen, videoWidth,
normalVideoWidth)` 在 `videoWidth < normalVideoWidth * 0.8` 时把字号乘以
`videoWidth / normalVideoWidth`。全屏打开评论区时视频被压到 `1478 px`（正常 `2247 px`），
比例 `0.658`，于是 `55.5 px → 36.5 px`；诊断里两条记录可直接对照：

```
video_width_px=2247;normal_video_width_px=2247;target_glyph_height_px=55.499996
video_width_px=1478;normal_video_width_px=2247;target_glyph_height_px=36.50601
```

`normalVideoWidth`/`referenceShorts`/`referenceLandscape` 这套基准只服务于这个“随画面缩小”
的分支，既不是阅读字号，也不是模式切换。

### 3.2 修复：字号只由模式和显示宽度决定

- `CaptionFontSize.modeRatio(tier, fullScreen)` 成为模式比例的唯一来源；
  `SubtitleStyleMetrics.targetGlyphHeightPx(tier, screenWidth, fullScreen)` 只做
  “显示宽度 × 模式比例”。
- 删除 `SubtitleStyleMetrics.renderedGlyphHeightPx(...)` 以及
  `CaptionOverlay` 的 `normalVideoWidth`/`referenceShorts`/`referenceLandscape` 及其重置。
- 视频矩形仍决定 `inner` 宽度、换行与分页：全屏 `55.5 px` 字号在 `1478 px` 视频上仍是
  2 行可容纳的分句页；放不下时由既有的“整分句分页”切成多页（与全屏↔详情切换同一路径）。
- 诊断改为输出 `caption_layer`、`caption_host_px`，不再输出已删除的
  `normal_video_width_px`。

### 3.3 结果

- 全屏打开/关闭评论区：字号保持 `55.5 px`（`size_tier=2`），只在真正进入详情页时变为
  `44.5 px`——即全屏↔详情之间既有的那一档“微小变化”。
- 视频变窄只改变每行可容纳的字数与页数，不改变字号；`page` 仍由整分句打包。

## 4. 代码变更

| 文件 | 变更 |
|---|---|
| `application-host/.../CaptionSurface.java` | `renderedSurface()`；`captionHost(View)`/`captionHost()`；`relative()` 改为矩阵映射；`renderedBounds()` 复用 Surface 选择 |
| `ui/.../CaptionOverlayV2.java` | 宿主改为视频平面 FrameLayout 并支持宿主切换；删除 `normalVideoWidth` 三件套；字号改用 `targetGlyphHeightPx`；新增 `CAPTION_LAYER` 与 `caption_layer/caption_host_px` 诊断 |
| `presentation/.../SubtitleStyleMetrics.java` | 删除 `renderedGlyphHeightPx`；`targetGlyphHeightPx` 写死“模式 × 显示宽度”契约 |
| `presentation/.../CaptionFontSize.java` | 新增 `modeRatio()`，模式比例唯一来源 |
| `ui-preview/.../N39UiPreviewActivity.java` | 预览改用 `targetGlyphHeightPx` |
| `extensions/.../CaptionGlyphMetricsTest.java` | 删除随视频缩放的断言，改为“视频宽度不参与字号” |

新增回归：

- `CaptionLayerContractTest`（5）：视频平面是宿主、视频直接在播放器内、无 Surface 的播放器、
  非 FrameLayout 播放器、缩放播放器下视频矩形仍是宿主局部坐标。
- `CaptionLayerOverlayTest`（3）：字幕在播放器分支内且评论面板晚于播放器绘制；
  打开面板只改盒子宽度不改字形高度；更窄的视频把同一文本重新投影为更多整分句页。

## 5. 验证

- `:translation-core:testDebugUnitTest`：**246 tests**，0 failures / 0 errors / 0 skipped。
- `:extensions:extension:testDebugUnitTest`：**272 tests**（50 个结果文件），0 failures /
  0 errors / 0 skipped（新增 8 个）。
- 本地化：243 keys × 14 locales 通过（`dev.cmd` 内置检查）。
- `tools/verify_integration.py`：`errors=[]`；`compiled_core_class_count=79`；
  pinned prompt/protocol 字面量未变；内嵌 MPE 与独立 MPE 相同。
- 产物：MPP `build/n39/gradle/patches/libs/patches-1.5.5.mpp` **596,221 B**，SHA-256
  `DD51262F3AD6A05D23EC202F4F7D95070B61D588B7DFE88C59BB76F925689AFF`；
  内嵌 MPE `07E938144156040452D38664CAE27807367D99D4F99FDA8A22328F371207B50`；
  UI 预览 APK 3,860,909 B，SHA-256
  `4A16BF98E661B99331DC34F62D5FAEF715C4315EE3A1D38049A556F36FA90294`，不含翻译核心。
- 诊断身份：`ui=1.5.5; engine=1.5.5; build=local-1.5.5-n53`（发布版仍为
  `release-1.5.5`，本轮未发布/未替换 GitHub 资产）。

## 6. 未完成与边界

- 未连接或写入手机；本轮只做 Robolectric + 打包验证，真机安装与 after 诊断待复验。
- 评论面板的确切视图层级未从 APK 读取，结论来自截图与 z 序事实（面板在 Activity 内、
  晚于播放器绘制）；`CAPTION_LAYER` 诊断会在真机首次挂载时给出实际宿主类名、尺寸与
  子 View 数，可直接核对。
- 供应商 `content_filter` 拒绝的文本仍无译文；本轮只交付根因与证据，不改变请求策略。
- 播放器视图被整体重建时会有一次重新挂载（重新排版，通常在隐藏/转场帧内）；若真机出现
  可见闪烁，应改为“保留旧 anchor 平移”而不是重建。
- 字号是否应随**显示宽度**做连续缩放仍是一个设计选择；本轮按用户要求固定为
  “全屏/详情两档 + 微小差异”。
