# N45 — 官方自动字幕策略与持久语言记忆（2026-10-20）

## 0. 一句话

**官方 Auto captions 设置决定字幕是否自动出现；本补丁只决定“出现时用哪种语言”，并把显式选择持久化到 App 重启之后。AI 开且已有记忆时由 AI 翻译；AI 关时选择同一语言的原生译轨；首次无记忆时先推断一个会话目标，AI 开关一开就显示目标译文，而不是等用户进菜单选语言。**

## 1. 输入与现场

- 诊断：`D:\HONOR Share\Honor Share\caption-diagnostics-1.5.4-20261020-105645.txt`
  - 5,832 B；SHA-256 `0A877EBEE9B49629E9E681CAFEFDD68D62B715499921BB9B4F3C75BFAE14DA1B`
  - 16 条时间记录；`choice_known=false`、`ENGINE_SNAPSHOT_DEFERRED reason=no_explicit_target; native=zh-Hans; translated=true`。
- 用户现场：
  - 字幕自动显示、AI 字幕开启时，显示 YouTube 原生**原文字幕**；
  - AI 字幕关闭时，显示 YouTube 原生**目标译文字幕**。

## 2. 诊断根因

1. 当时 `RememberedCaptionSelection` 只是进程内静态状态，诊断里没有任何已保存的目标，`decision=-1`、`choice_known=false`。
2. 旧 `CaptionTargetPolicy` 对 `decision<0` 执行 `withoutTarget(url)`：把 YouTube 的 `tlang=zh-Hans` 请求改成了源请求。诊断里的
   `TARGET_POLICY_APPLIED decision=-1;target=;from_translated=true` 就是这一步。
3. AI 引擎随后没有目标可启动，`ENGINE_SNAPSHOT_DEFERRED` / `NATIVE_SELECTION_IGNORED` 出现；原生渲染器于是显示被改写后的源字幕。
4. AI 关闭时，`DeepSeekCaptionHook.rewriteUrl` 提前返回原始 URL，YouTube 自己显示 `zh-Hans` 译轨——于是形成“AI 开看原文、AI 关看译文”的相反现象。
5. 背景预取事件（`foreground=false`）先于前台应用，进一步说明不能只依赖“原生轨道被应用”这一个触发点。

## 3. 权威边界

| 决策 | 权威 |
|---|---|
| 字幕是否自动出现 | **Morphe 官方 `AutoCaptionsPatch`**（始终显示 / 从不显示 / 未静音时显示 / 静音时显示） |
| 字幕出现时使用哪种语言 | 本补丁的持久语言记忆（用户最后一次显式选择）；无记忆时用会话推断目标 |
| AI 字幕是否进行翻译 | 本补丁的 AI 开关；关闭时不发 API，原生译轨直接显示 |
| 当前视频是否被用户手动关闭 | `CaptionChoice` + `CaptionButtonController`；不写回持久语言 |

官方设置仍按原样工作：我们不会在视频开始时主动打开 CC；只有 CC 已经显示（自动或手动）、或 AI 开启后收到前台字幕请求时，才应用记忆/推断语言。

## 4. 持久记忆

`RememberedCaptionSelection`（`application-host/.../RememberedCaptionSelection.java`）：

- SharedPreferences：`caption_selection_memory`，键 `version/language/translated/asr`。
- `initialize(Context)` 在 `NativeCaptionBridge.initialize()` 中、原生字幕模型选择默认轨之前调用。
- 只有显式选择（原生菜单 / 已确认的菜单请求）写入；自动默认轨不会成为用户选择。
- `off()` 只把本次会话置为 OFF，**保留 language/translated/asr 且不落盘**；新视频 `resume()` 重新变为可用。
- 因此 App 重启后仍记住最后语言，但不会把“上次按了 Off”变成永久的“禁止显示字幕”。
- 语言码经过 `TargetLanguage.fromCode()` 归一化；比较时用同一归一化（`sameLanguage()`），避免 `iw/he`、`zh-CN/zh-Hans` 等写法差异。

## 5. 请求层语言不变量

`CaptionTargetPolicy`（`caption-shared/.../CaptionTargetPolicy.java`）现在只回答“这条请求应该带哪个语言”，不再用“无记忆”来猜源语言：

| 记忆状态 | 源请求（无 tlang） | 译请求（有 tlang） |
|---|---|---|
| 无记忆 + AI 关 | 原样通过 | 原样通过（保留 YouTube 原生译文） |
| 无记忆 + AI 开 | 推断目标后改写 | 推断目标后改写（优先原请求的 `tlang`） |
| 本次 Off `decision==0` | 原样通过 | 原样通过 |
| 记忆 = 翻译 `TRANSLATE(code)` | 改为 `tlang=code` | 改为 `tlang=code` |
| 记忆 = 仅源字幕 `SOURCE_ONLY` | 原样通过 | 去掉 `tlang`，回到源 |
| 原生菜单刚打开 | 原样通过 | 原样通过，由菜单确认写记忆 |

**首次使用（无持久记忆）的目标推断**，只作为本次会话目标，不写入持久记忆：

1. 当前请求本身是译轨 → 用它的 `tlang`（即 YouTube 已经显示的语言）；
2. 当前原生 selection 是译轨 → 用它的语言；
3. 否则用 App 语言（中文按脚本/地区归一到 `zh-Hans` 或 `zh-Hant`）；
4. 再否则用设置里已加入菜单的第一个语言；
5. 如果推断目标等于视频源语言，则不启动翻译（源字幕已经是目标）。

`DeepSeekCaptionHook.rewriteUrl()` 的顺序改为：

1. 先计算语言记忆策略；**AI 关闭也照样返回改写后的原生 URL**。
2. 菜单窗口内的译请求先确认目标并写记忆（即使 AI 关闭）。
3. AI 关闭：直接返回语言策略结果。
4. AI 开启但无记忆：先推断一个会话目标（`TARGET_INFERRED_FOR_AI`），再按该目标改写并启动 AI；不再只是原样放行原生字幕。
5. 只有前台视频、有目标、且不属于 CC Off 的请求才进入 AI 拦截/环回。

这条不变量同时修掉了两个方向：AI 开不再显示原文；AI 关仍显示记忆目标。

## 6. 显示触发与原生默认轨

- `CaptionButtonController` 观察到 CC 变为 ON 时调用 `NativeCaptionBridge.onCaptionsShown()`：
  - 先把记忆语言写回本次 `CaptionChoice`；没有记忆时调用 `defaultTargetCode()` 推断会话目标；
  - 若当前已有原生 selection，走 `refreshNativeTrack()`；
  - 若官方“从不显示”导致没有提交轨道，则用 `onOriginalTrackList()` 保存的当前视频签名源轨 URL 直接启动 AI（`CAPTIONS_SHOWN_AI_STARTED`）；
  - 记忆/推断为仅源字幕时激活源字幕（`CAPTIONS_SHOWN_SOURCE`）。
- `onVideoId` 只在新视频上 `RememberedCaptionSelection.resume()`，不重置目标。
- `NativeCaptionBridge.refreshNativeTrack()` 在 AI 关闭时如果当前是源轨而记忆是译轨，会重新选择记忆目标（`ENGINE_OFF_NATIVE_TARGET`），避免关掉 AI 后露出源字幕。
- `refreshNativeTrack()` 在 AI 开启、无记忆但 CC 已知显示时，用 `defaultTargetCode()` 推断 `CaptionChoice` 并启动 AI（`ENGINE_DEFAULT_TARGET_ACTIVATED`）；未观察到的预取保持被动。
- 自动事件如果恰好就是记忆语言/模式，`matchesRemembered()` 会重新武装 `CaptionChoice`（`NATIVE_MEMORY_REAPPLIED`），而不是把它当成任意 YouTube 默认。
- `hookRememberedDefault` 的 `decision<0` 回退改为 **YouTube 原始默认轨**；只有 `decision==1` 且无法解析目标时才走 `sourceDefault`，由请求策略再改成目标。
- 推断目标**不持久化**：它只解决首次/换环境时“AI 开关已开却还要手选”的空窗；用户显式选择后仍按持久记忆执行。

## 7. 变更文件

- `application-host/.../RememberedCaptionSelection.java`
- `application-host/.../NativeCaptionBridge.java`
- `application-host/.../DeepSeekCaptionHook.java`
- `caption-shared/.../CaptionTargetPolicy.java`
- `infrastructure/.../DeepSeekConfig.java`
- `ui/.../CaptionButtonController.java`
- `ui/.../CaptionQuickToggle.java`
- `patches/.../NativeCaptionBridgePatch.kt`（默认轨回退）
- `patches/.../RememberCaptionSelectionPatch.kt`（描述改为跨 App 启动）
- `patches-list.json`（重新生成）

## 8. 验证

| 项目 | 结果 |
|---|---|
| `:translation-core:testDebugUnitTest` | **247 tests**，0 failures / 0 errors / 0 skipped |
| `:extensions:extension:testDebugUnitTest` | **309 tests**，0 failures / 0 errors / 0 skipped |
| 本地化 | 243 keys × 14 locales 通过 |
| `tools/verify_integration.py` | `errors=[]`、`prompt_and_protocol_literals_unchanged=true`、core class 77、14 locale、embedded MPE 与 standalone 相同 |
| MPP | `build/n39/gradle/patches/libs/patches-1.5.4.mpp`，**607,383 B**，SHA-256 `AF612DB48E8A7A9A1AECA4D2C5959EA4347FBB997A10583C9CDA44782824F981` |

新增/更新回归：

- `CaptionTargetPolicyTest`：无记忆不剥 `tlang`；记忆目标同时改写源/译请求；仅源字幕去 `tlang`；菜单/Off 原样；目标推断优先原生 `tlang`、回退 App 语言、源语言相同则不翻译。
- `CaptionMemoryPersistenceTest`：跨“进程重启”加载；Off 不擦除语言；`resume`；`reset` 清空。
- `CaptionHookMemoryTest`：AI 开/关、无记忆、目标/仅源字幕请求路径；首次 AI 开时用原生 `tlang` 推断会话目标。
- `CaptionShownMemoryTest`：没有原生 selection 时，CC ON 仍能用源 URL 启动记忆目标 / 源字幕；无记忆时推断 App 目标；无翻译需求不启动。
- `ShortsEngineSwitchTest`：既有回归保持通过；新增“AI 关闭后把推断目标重选为原生译轨”。
- `N40LanguageMemoryTest` 等既有记忆回归保持通过。

诊断证据保存于 `validation/runs/20261020-memory-and-official-autocaptions/`（`verify.json` 等）。

## 9. 边界与未完成

- **未连接真机**：本轮没有安装、写入手机或抓取 after 诊断；需要用户安装新 MPP 后复验。
- 官方 `Auto captions` 的四种模式只做了代码路径覆盖；没有在真机上逐一录像。
- 记忆按 App 进程生命周期加载一次；如果用户在运行中清空 App 数据，需要重新选择。
- 推断目标只属于本次会话，不写入持久记忆；App 重启后会重新按原生 `tlang`/App 语言推断。用户显式选择后才会持久化。
- 目标语言不在该视频原生译轨列表时，仍依赖 `cloneTranslation` 或请求层 `tlang`；个别 YouTube 版本可能拒绝克隆轨，此时请求层会尝试原生目标 URL。
- “从不自动显示”下没有任何原生 selection 时，`onCaptionsShown` 会尝试用已保存的签名源轨 + 推断目标启动；若该版本还没有暴露源轨，则要等首个字幕请求到来（请求路径同样会推断目标）。
- 记忆只保存语言/模式，不保存视频、API 配置或 CC 的自动显示策略。
- 如果推断目标正好等于视频源语言，不会启动翻译（原生源字幕已经是目标），这不算“AI 开关无效”。

## 10. 建议的真机复验

1. 官方 Auto captions 设为**始终显示**；AI 开；**首次不选任何目标**，确认第一个视频就显示目标译文；强杀 YouTube 再打开，确认仍自动显示目标译文。
2. 显式选一次不同语言，再换视频/重启，确认持久记忆覆盖推断值。
3. AI 关；换视频，确认显示同一语言的 YouTube 原生译轨，而不是原文。
4. 官方设为**从不显示**；打开 CC 一次，确认按记忆/推断语言显示（AI 开则 AI，AI 关则原生译轨）。
5. 在 `始终显示` 与 `从不显示` 之间切换，确认不会因为启动时的 CC-off 把记忆语言擦掉。
6. 抓诊断时重点看：`TARGET_INFERRED_FOR_AI`、`TARGET_POLICY_APPLIED` 的 `ai/decision/target`、`CAPTIONS_SHOWN`、`CAPTIONS_SHOWN_AI_STARTED`、`ENGINE_DEFAULT_TARGET_ACTIVATED`、`NATIVE_MEMORY_REAPPLIED`、`ENGINE_OFF_NATIVE_TARGET`；不应再出现无记忆时 `decision=-1` 把 `from_translated=true` 改成源请求后直接显示原文的链路。
