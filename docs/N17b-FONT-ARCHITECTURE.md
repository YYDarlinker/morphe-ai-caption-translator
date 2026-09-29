# N17b 字号架构：现状、改动与验证

## T1 现状与根因（改动前）

- 字号设置原为 8–15 的整数相对值，默认 13；`DeepSeekConfig.java:26–28,45–65,121–123` 负责默认、读取及裁剪，`DeepSeekSliderPreference.java:95–103,135–160` 提供滑块，`CaptionTranslationCatalog.java:27,176` 明示“随画面比例缩放”。用户真机显示的 23.18sp 是**渲染值**，并非设置上限 15sp 本身。
- 核心公式原是 `max(12, configured × videoWidthDp / 360)`（原 `SubtitleStyleMetrics.java:5`）。`CaptionSurface.java:127–155` 选出当前 SurfaceView/TextureView 可见矩形；`CaptionOverlayV2.java:373–396` 以该宽度计算字号，再把同一个字号传给 `LayoutBudget`、分页测量及 TextView。布局可用宽度仍由视频宽度决定：Shorts ×0.78、普通视频 ×0.92。真机记录同源字幕在全屏约 21.4sp、竖屏详情约 13.3sp、Shorts 约 14.8sp、开评论区约 12.04sp；第二轮手调后全屏 23.18sp。
- 评论区没有专门的开关回调。旧效果来自评论区压窄视频矩形后，上述公式被动按矩形宽度缩小；`CaptionOverlayV2.java:102–110,369–383` 通过预绘制和视频矩形变化刷新。普通形态的宽度变化也会触发同样缩放。
- `DeepSeekCaptionPatch.kt:40–50` 的 player-type hook 经 `CaptionPlayerTransitionGuard.java:52–93` 只报告播放器形态。CC 按钮 hook（`DeepSeekCaptionPatch.kt:83–97`）只报告字幕选择；均不报告进度条/控制层可见性。字幕 Y 位置原由视频矩形及用户保存的相对位置计算（`CaptionOverlayV2.java:470–486`）。

## T2 固定字号与分页

候选默认 **22.6sp**、用户范围 **18.0–27.0sp**，与 2736×1264 本机 2.66px/sp 标定相符；**这三个数值均待用户确认**。配置以 0.1sp 存新键 `caption_text_size_tenths`。旧键 `caption_text_size` 是不同单位，升级后不会被误读为绝对字号：未保存新键者使用 22.6sp 候选默认值。滑块、设置说明和预览同步改为屏幕字号。

普通 Shorts、详情页、全屏渲染同一 sp。现有代码没有可靠的评论开关，所以只在**同一播放器形态、同一屏幕方向**下，实际视频矩形相对已观测的正常宽度收窄超过 20% 时，沿用随视频宽度缩放（12sp 下限）；矩形恢复即恢复固定字号。播放器形态或屏幕方向变化会重建正常宽度基准。它覆盖“先播放再打开评论区”的已观测路径，但不能证明收窄原因仅为评论区；若进入页面时评论区已经打开，也没有正常宽度可作基准。这两个边界须在真机检查，当前不能宣称评论区例外已被精确识别。

分页硬门槛、事件时间窗、语义切口和 `fitsOne/fitsTwo` 未变。`CaptionOverlayV2.java` 仍以当前有效字号经 Android `StaticLayout` 实测后注入 `RebuildPageLayout.plan`；固定字号只改变测量输入。`RebuildPageLayout.java:182–187` 的 12–18 字、中心 15 字仍是软偏好。在 1121px 代理宽度上，新字高约 60.12px，单行理论容量约 18.6 汉字格；离线回放中中位页长仍为 16 字、软带命中率 41.8%→42.8%，故未重标定这组常数。

| 同一 N15r 捕获语料，N17a 分页代码 | 旧 46.71px 代理 | 22.6sp×2.66 = 60.12px 代理 |
|---|---:|---:|
| 已接受事件 / 页 | 540 / 791 | 540 / 814 |
| 页长中位 / P90 | 16 / 27 字 | 16 / 27 字 |
| 页时长中位 / P90 | 3.484 / 6.269s | 3.411 / 6.171s |
| 单行页 | 86.0% | 66.2% |
| 12–18 字页 | 41.8% | 42.8% |
| 原文回退事件 / 硬约束违规 | 0 / 0 | 0 / 0 |

21 个事件页数变化。导出入口为 `RebuildN15PagingReplayTest` 的 `MORPHE_N15_LAYOUT_EXPORT`，新代理另设 `MORPHE_N15_FONT_PX=60.116`；汇总脚本与数据见 `scoreboard/n17b_compare.py`、`scoreboard/results/n17b-font-comparison.json`。两次回放均用 1121px/SDK28 StaticLayout 代理，**不是本机真机截图或各形态实测**；历史 N15r 结果未覆盖，冻结计分板未改。live API 0 次 / 0 tok。

JDK 21 执行 `gradlew test --offline`：Java 376/376；Python `unittest discover -s scoreboard`：27/27；`scoreboard/run.ps1`：冻结计分板维持 4 通过 / 4 既有失败 / 4 未验证。

## T3 控制层避让：信号缺口，按停止线未实施

仓库没有经验证的 YouTube 21.07.247 进度条/控制层可见回调或资源 ID；当前 player-type 和 CC 回调均不能代表控制层显隐。未接真机，无法核对候选 View 的显隐事件。因而未以点击、播放器形态或 CC 按钮推断，也未写上抬动画。要完成该项，需先在 Shorts、横屏详情、横屏全屏记录同一控制层的可靠 show/hide 信号及其底部遮挡边界，再用系统默认短动画调整字幕 Y 并验证隐藏后回落。

## 真机验证点

1. 同一片源/同一用户设置，在 Shorts、详情页、全屏量字幕 sp/字高；比较 22.6sp 候选值及 18–27sp 滑块两端。
2. 三种形态分别打开、关闭评论区，核对视频矩形收窄与字幕同比缩放、恢复；重点检查“进入时评论区已打开”及形态切换是否误缩放。
3. 逐形态观察控制层显示/隐藏，记录可靠的可见事件和进度条占用区域；本卡未实现自动上抬。
4. 大字号下检查 A06/A10、两处 N17a 孤字页及一般长句的两行/CPS/时间窗；离线代理通过不代替真机观感签字。
