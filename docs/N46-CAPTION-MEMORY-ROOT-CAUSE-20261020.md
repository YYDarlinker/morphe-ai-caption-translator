# N46 — 字幕记忆与语言菜单不一致的根因调查（2026-10-20）

> 状态：**只读调查**。本轮没有修改产品代码、没有重建 MPP、没有连接手机。
> 结论针对当前工作树（`dev/1.5.4-deepseek`，HEAD `2c96054` + 未提交的 N40–N45 改动）；
> 现场安装的 MPP 为 `607,383 B` / SHA-256 `AF612DB48E8A7A9A1AECA4D2C5959EA4347FBB997A10583C9CDA44782824F981`
> （构建于 2026-10-20 12:15:53），诊断导出于 12:21:33。
>
> **追加（N47）**：Morphe 1.47.0 的 `Preferred caption language` 可能构成原生模型之上的第三个
> 写入层；见 `docs/N47-CAPTION-TRACK-DETERMINATION-20261020.md`。本报告“菜单由原生模型决定”
> 的结论仍然成立，但原生模型本身可能先被 Morphe 覆盖，因此修复前必须先确认该设置值。

## 0. 一句话结论

这不是“记忆写错了”或“某个菜单钩子没生效”，而是**目标语言存在两个互相独立的权威**：

- **语言菜单 / YouTube 原生字幕模型**：决定菜单勾选哪个轨道；
- **`RememberedCaptionSelection` + 请求层 `CaptionTargetPolicy`**：决定实际显示和翻译哪个语言。

显示与请求由第二权威驱动，菜单由第一权威驱动，二者之间没有强制同步；当第二权威的语言在当前视频的
原生译轨里不存在时，请求层还会用 `TargetLanguage.withCode()` **凭空造出 `tlang`**。所以必然出现
“AI 字幕是 ru、菜单却是 zh-Hans/zh”，以及“这个视频没有自动翻译选项，后台仍然 AI 翻译 ru”。

**根因是权威分裂（authority split），不是某个分支的局部错误。** 在现有结构上再加一个
`selectNative`、再修一次默认轨钩子、或再补一次 clone，都只能覆盖某一个视频/某一个 YouTube 版本，
不能建立“菜单显示什么，字幕就显示什么”的不变量。

---

## 1. 输入、代码版本与可复现材料

### 1.1 诊断

- 路径：`D:\HONOR Share\Honor Share\caption-diagnostics-1.5.4-20261020-122129.txt`
- 大小：`1,349,694 B`
- SHA-256：`532F317748C3E38762AC951C4BB52CD7F01CFBEC8E9199567A50167BA93757DD`
- 可解析时间记录：7,682 行；`ui=1.5.4`、`engine=1.5.4`、`core=rebuild-1.4.0/ac65fe8`、
  `build=release-1.5.4`、`official=1.45.0`、`presentation=n29-presentation-v3`。

关键事件计数（只统计带时间戳的记录）：

| 事件 | 次数 | 说明 |
|---|---:|---|
| `VIDEO_SWITCHED` | 7 | 7 次视频切换 |
| `NATIVE_TRACK_APPLIED` | 17 | 11 次 `zh-Hans/manual`、2 次 `zh/manual`、1 次 `ru/manual`、3 次 off/空 |
| `ENGINE_REMEMBERED_ACTIVATED` | 29 | 按记忆目标启动引擎 |
| `TARGET_POLICY_APPLIED` | 35 | 请求 URL 被改写为记忆目标 |
| `NATIVE_REMEMBERED_ACTIVATED` | 2 | 自动默认事件上直接激活记忆目标 |
| `NATIVE_MEMORY_REAPPLIED` | 2 | 自动默认恰好等于记忆时重新武装 |
| `NATIVE_SELECTION_IGNORED` | 2 | 自动译轨不等于记忆时被忽略 |
| `LANGUAGE_REMEMBERED_CLONED` | **0** | 目标缺失时的克隆路径从未成功 |
| `TARGET_CONFIRMED_BY_MENU_REQUEST` | **0** | 没有一次“菜单请求确认目标” |

> 重要：`LANGUAGE_MENU_READY selected=14;items=18` 里的 `selected=14` **不是菜单当前勾选的语言**，
> 而是 `CaptionLanguageSelection.menuCodes()` 的配置语言数量（`NativeCaptionBridge.java:218-225`）。
> 菜单真实勾选状态只能从 `NATIVE_TRACK_APPLIED` 的原生轨道间接判断，不能把 `selected=14` 当成
> 菜单选中了第 14 项。

### 1.2 离线探针（可重复执行）

探针：`validation/runs/20261020-n46-caption-memory-root-cause/N46MemoryAuthorityProbeTest.java`
原始 JUnit XML：同目录 `probe-test-result.xml`
输出：同目录 `probe-output.txt`

```powershell
# 将探针复制到 extensions/extension/src/test/java/app/yydarlinker/deepseekcaptions/ 后：
.devenv\dev.cmd :extensions:extension:testDebugUnitTest --tests app.yydarlinker.deepseekcaptions.N46MemoryAuthorityProbeTest
```

结果（2 tests，0 failures，0 errors）：

```text
A memory=ru native_menu_applied=zh-Hans activations=[https://www.youtube.com/api/timedtext?v=aaaaaaaaaaa&lang=en&tlang=ru] nativeSelects=0 choice=ru
B memory=ru native_menu_applied=zh activations=[https://www.youtube.com/api/timedtext?v=aaaaaaaaaaa&lang=zh&tlang=ru] nativeSelects=0 choice=ru
```

A 复现“自动默认 zh-Hans + 记忆 ru”：AI 在 ru 上启动，`selectNative()` 一次都没有调用，菜单仍是
zh-Hans。B 复现“只有源轨、没有自动翻译列表”：仍然启动 `tlang=ru` 的 AI 会话，菜单没有任何 ru 行可
表示它。

### 1.3 既有测试其实把当前行为写成了预期

这两个测试在 N45 中是绿的，证明“显示目标可以脱离原生选择”是当前设计的**有意行为**，不是偶发：

- `extensions/.../CaptionShownMemoryTest.java:183`：
  `manualCaptionsOnStartsTheRememberedTargetWithoutANativeSelection` — 没有原生 selection 时，
  仍然用记忆 `ko` 启动 AI。
- `extensions/.../CaptionHookMemoryTest.java:78`：
  `rememberedTargetAppliesWhenTheEngineIsOff` — 源请求（`SOURCE`）也会被改写成 `tlang=ko`，
  完全不检查这个视频是否有 ko 译轨。

所以本轮不能把问题描述成“某个测试没覆盖”；测试覆盖的正是需要被推翻的旧契约。

### 1.4 限制

- 没有连接设备，无法直接读取菜单的 checked row；菜单状态由 `NATIVE_TRACK_APPLIED`、用户现场描述
  和 YouTube 原生模型的职责推断。`NATIVE_TRACK_APPLIED` 的原生 `origin=DEFAULT` 是模型初始化/
  恢复路径，`origin=PREFERRED_TRACK` 是用户显式选择。
- 没有反编译当前 YouTube 版本的 `setSubtitleTrack`/菜单模型；“YouTube 自己是否会替换人工轨”
  不能从这份日志直接证明。第 6 节给出当前架构下能确认和不能确认的边界。
- `NATIVE_TRACK_APPLIED` 在 `refreshNativeTrack()` 内部重选时会被 `switchingManager` 抑制
  （`NativeCaptionBridge.java:506`），因此“日志里没有 ru applied”**不能**单独证明
  `selectNative()` 没有被调用；能证明的是最终菜单/原生 applied 状态没有变成 ru，以及存在
  明确不调用 `selectNative()` 的分支。

---

## 2. 现场时间线：记忆切到 ru 之后，菜单再也没有离开 zh-Hans/zh

用户在 `YX8ks42Azn8` 上显式选择 ru（`1792470007131`，文件第 4323 行）：

```text
NATIVE_TRACK_APPLIED | foreground=true;translated=true;reason=1;origin=PREFERRED_TRACK;track=ru/manual
```

`PREFERRED_TRACK` 表示用户显式选择；`translated=true` 表示这是带 `tlang` 的译轨；
`ru/manual` 表示“目标 ru、源是 manual”，不是“原生人工 ru 字幕”。

此后每次换视频，原生 applied 的轨道和记忆目标如下：

| 时刻（uptime） | 视频 | 原生 applied | 记忆/引擎目标 | 直接观察 |
|---|---|---|---|---|
| `1792470011214` | `QH838aWyhAk` | `translated=false;origin=DEFAULT;track=zh/manual` | `TARGET_POLICY_APPLIED target=ru;from_translated=false` | 源请求被加 `tlang=ru`；日志中没有该视频的自动翻译列表记录（`LANGUAGE_TRACKS_AVAILABLE` 缺失），原生只 applied 源轨 |
| `1792470014127` | `QH838aWyhAk` | 同上 | `ENGINE_REMEMBERED_ACTIVATED target=ru;native=zh;native_translated=false` | `CAPTIONS_SHOWN target=ru;native=AI_STARTED`；随后出现 ru 译文与 API 请求 |
| `1792470022924` | `DqAKQwagCDg` | `translated=true;origin=DEFAULT;track=zh-Hans/manual` | `VIDEO_SWITCHED target=ru` | 原生默认仍是 zh-Hans |
| `1792470023058` | `DqAKQwagCDg` | — | `SOURCE_TRACK_OWN_TARGET shown=ru/MANUAL;translation_request=false` | 该视频**确实有原生人工 ru 轨**，显示层可以无 API 使用它 |
| `1792470023509` | `DqAKQwagCDg` | `translated=true;origin=DEFAULT;track=zh-Hans/manual` | — | 原生 applied 仍是 zh-Hans |
| `1792470023545` | `DqAKQwagCDg` | — | `NATIVE_SELECTION_IGNORED reason=automatic_translation_without_choice;native=zh-Hans` | 代码明确不采纳原生自动选择 |
| `1792470023743` | `DqAKQwagCDg` | — | `ENGINE_REMEMBERED_ACTIVATED target=ru;native=zh-Hans;native_translated=true` | AI 目标 ru，原生仍是 zh-Hans |
| `1792470034836` / `1792470037602` | `DqAKQwagCDg`（CC off 再 on） | `track=zh-Hans/manual` | `NATIVE_REMEMBERED_ACTIVATED target=ru;native=zh-Hans` | 该分支在 `NativeCaptionBridge.java:620-628` 直接 `return`，不会 `selectNative` |
| `1792470042936` | `AaxPLnvCy08` | `track=zh-Hans/manual` | `ENGINE_REMEMBERED_ACTIVATED target=ru;native=zh-Hans` | 再次同样分裂 |
| `1792470058599` | `mhaKh7WITSc` | `translated=false;origin=DEFAULT;track=zh/manual` | `TARGET_POLICY_APPLIED target=ru;from_translated=false` | 只有源轨，仍被加 ru；质量证据里确有中→ru 的 API 请求 |
| `1792470061488` | `mhaKh7WITSc` | 同上 | `ENGINE_REMEMBERED_ACTIVATED target=ru;native=zh;native_translated=false` | 显示 ru，菜单原生仍是 zh |

在 `1792470007131` 之后，`NATIVE_TRACK_APPLIED` 共 11 次，全部是 `zh-Hans/manual`、`zh/manual`
或 off/空；**没有一次 ru**。而 `ENGINE_REMEMBERED_ACTIVATED` 有 29 次，其中绝大多数是 ru。
`LANGUAGE_REMEMBERED_CLONED` 为 0，`TARGET_CONFIRMED_BY_MENU_REQUEST` 为 0。

这组数据与用户描述的三个现象一一对应：

1. 换新视频必然显示上一视频选的 ru —— `onVideoId` + `onCaptionsShown` 的记忆优先路径；
2. 没有自动翻译选项也显示 AI ru —— `CaptionTargetPolicy` 对源请求也 `withCode(ru)`；
3. 菜单显示其他语言，但字幕是 ru —— 菜单只由原生模型决定，记忆目标从未可靠写回菜单。

---

## 3. 机制解剖：到底有几套“目标语言”

### 3.1 两个存储 + 一个进程影子

| 状态 | 存储/生命周期 | 主要写者 | 主要读者 | 菜单是否读它 |
|---|---|---|---|---|
| YouTube 原生 caption model | YouTube 进程/账号；轨道对象 | `onNativeSelectionApplied`、用户菜单、`selectNative` | 语言菜单、原生渲染器、`NATIVE_TRACK_APPLIED` | **是（唯一来源）** |
| `RememberedCaptionSelection` | `SharedPreferences caption_selection_memory`，跨视频、跨 App 启动 | 显式原生选择（`followNativeSelection`）；菜单窗口内请求（`TARGET_CONFIRMED_BY_MENU_REQUEST`） | `CaptionTargetPolicy`、`onCaptionsShown`、`refreshNativeTrack`、`CaptionButtonController`、`CaptionQuickToggle`、`DeepSeekConfig` | **否** |
| `CaptionChoice` | 进程静态；生产代码不在换视频时 `reset()` | 原生显式选择、记忆恢复、CC 按钮、推断 | 显示/引擎/默认目标读取 | 间接（只作为原生选择的应用结果） |

`RememberedCaptionSelection` 的注释和实现都表明它是独立权威：

- `RememberedCaptionSelection.java:37-55` 在 `NativeCaptionBridge.initialize()` 里加载；
- `:57-65` 把任何显式轨道写成 `language/translated/asr`；
- `:113-128` 从 SharedPreferences 恢复，`decision=ON`；
- 它**没有**“当前视频是否有这个语言”的字段，也没有“菜单当前勾选什么”的字段。

`CaptionChoice` 不是完整的第二套语言菜单；它只是进程内影子，但在生产路径里被当成当前选择使用。
更关键的是 `NativeCaptionBridge.onVideoId()` 的注释明确写着：

```java
// The target of this launch is process-scoped, not per video. Never reset it or
// re-apply a previous video's native track here...
```

（`NativeCaptionBridge.java:727-729`）

这不是意外，而是 N45 的设计选择：目标属于“本次启动”，不属于视频。用户现在明确拒绝的正是这个
设计选择。

### 3.2 换视频 / CC 打开：记忆在原生选择之前就赢了

新视频路径有三层触发，全部优先记忆：

1. `NativeCaptionBridge.onVideoId()` 只 `resume()` 记忆，不重置（`:712-738`）。
2. `CaptionButtonController.onVideoId()` 如果上一个视频的 `captionIntent` 还是 ON，会直接调用
   `NativeCaptionBridge.onCaptionsShown()`（`CaptionButtonController.java:139-159`）。
3. `onCaptionsShown()` 的第一优先级是记忆：

```java
String language = RememberedCaptionSelection.language();
boolean translated = RememberedCaptionSelection.translated();
if (RememberedCaptionSelection.decision() != 1 || language.isEmpty()) {
    ... // 只有没有记忆时才看 CaptionChoice/defaultTargetCode
}
...
CaptionChoice.select(language, translated);
Refresh refreshed = refreshNativeTrack();   // 有记忆目标 -> 启动 AI
```

（`NativeCaptionBridge.java:1074-1129`）

此外，CC 短按打开、CC 被观察为 ON 时也会重新选中记忆：

- `CaptionButtonController.java:619-626`：短按后若记忆非空，`resume()` + `CaptionChoice.select(memory)`；
- `CaptionButtonController.java:691-702`：原生 CC 变 ON 时同样。

所以“打开新视频必然显示上一视频手动点选的语言”不是某个 YouTube 回调的偶然结果，而是
**onVideoId → onCaptionsShown → 记忆优先**这条主动路径的必然结果。即使原生菜单此时已经准备好
另一个语言，代码也不会先读它。

### 3.3 请求层：没有“这个视频有没有目标译轨”这个输入

`CaptionTargetPolicy.apply()` 的签名只有：

```java
apply(String url, int decision, String language, boolean translatedMemory, boolean nativeMenuOpen)
```

没有 `translatedTracks`、没有 `nativeTracks`、没有 current selection。因此它不可能判断目标是否可用。
当 `decision==1 && translatedMemory` 时：

```java
if (language == null || language.trim().isEmpty()) return url;
// The remembered target also covers source requests...
return TargetLanguage.withCode(url, language);
```

（`CaptionTargetPolicy.java:34-37`）

`TargetLanguage.withCode()` 只是查询参数改写（`TargetLanguage.java:115-153`），不会验证视频是否有
该译轨。结果：

- 源请求 `?v=...&lang=zh` → `?v=...&lang=zh&tlang=ru`；
- 该视频没有自动翻译列表也照样成立（探针 B，`QH838aWyhAk`/`mhaKh7WITSc` 的
  `TARGET_POLICY_APPLIED from_translated=false`）；
- `DeepSeekCaptionHook` 在 AI 开启且前景时把这条请求拦截到 loopback，启动 AI
  （`DeepSeekCaptionHook.java:134-152`、`:181-250`）。

这不是“YouTube 的原生自动翻译”，而是补丁自己合成的翻译请求。用户看到“没有自动翻译选项也在
后台翻译”，根因就在这一层。

### 3.4 显示层：即使有原生目标轨，也只更新 overlay，不更新菜单

`startEngine()` 是显示/引擎的统一入口：

```java
SourceSelection own = translate ? SourceTrackPolicy.ownTargetTrack(context, selected) : null;
if (own != null) CaptionActions.activateSource(context, own.url);
else if (translate) { ... CaptionActions.activate(context, source); }
else CaptionActions.activateSource(context, selected);
```

（`NativeCaptionBridge.java:697-708`）

`CaptionActions.activateSource()` 只向宿主发 `Action.SOURCE`（`CaptionActions.java:120-122`），
由 overlay 显示；它**不调用** `NativeCaptionBridge.selectNative()`。同时
`suppressNativeDraw()` 在 AI 会话可见时隐藏原生渲染器（`NativeCaptionBridge.java:287-298`）。
所以在“视频自带目标语言字幕”的场景：

- `SourceTrackPolicy.ownTargetTrack()` 找到 `ru/MANUAL`（`SourceTrackPolicy.java:126-155`）；
- 日志显示 `SOURCE_TRACK_OWN_TARGET shown=ru/MANUAL;translation_request=false`；
- 显示层确实用了原生 ru，不花 API；
- 但语言菜单还停在原生模型的 zh-Hans，因为 overlay 不是原生模型，`activateSource` 不改轨道。

这正是用户说的“有 YouTube 自带字幕的目标译文会自动替换为自带字幕”——**显示层的替换已经存在，
菜单没有跟着替换**。补丁自己实现了替换，不是 YouTube 在替它做。

### 3.5 菜单层：只有 best-effort 的 `selectNative`，且存在明确绕过分支

菜单的 checked row 只由 YouTube 原生 caption model 决定。补丁试图写回它的地方很少：

1. `refreshNativeTrack()` 在 `started && remembered` 时取 `resolveRemembered()`，然后
   `selectNative(manager, null)` + `selectNative(manager, track)`（`:1049-1059`）；
2. `hookRememberedDefault` 在模型初始化时尝试让默认轨等于记忆（`NativeCaptionBridgePatch.kt:553-581`）。

但这两条都不能构成不变量：

- **`NATIVE_REMEMBERED_ACTIVATED` 分支直接返回**（`:604-628`），没有 `selectNative`；探针 A
  的 `nativeSelects=0` 就是这条路径。
- **`resolveRemembered()` 只搜索 `translatedTracks`**（`:1139-1171`）。视频有原生人工目标轨、
  但没有 Auto-translate 列表时，它找不到该轨；`cloneTranslation` 失败时返回 null。诊断里
  `LANGUAGE_REMEMBERED_CLONED=0`，克隆路径从未成功。
- **`refreshNativeTrack()` 的重选只在 `ownsManager(value) && value.origin != null` 时发生**
  （`:1043-1050`）；而且 `switchingManager` 会抑制这次重选产生的 `onNativeSelection` 日志
  （`:506`），所以不能用“没有 ru applied 日志”单独断言重选没发生；但最终菜单仍显示 zh-Hans，
  说明重选没有形成用户可见的菜单状态。
- **`hookRememberedDefault` 不是不变量**：诊断中记忆已经是 ru，原生默认仍多次 applied
  `zh-Hans/manual` 或 `zh/manual`。可能原因包括钩子未覆盖该版本的默认路径、执行时列表/记忆尚未
  就绪、或 `resolveRemembered` 返回 null 后回退；无论哪一种，都不能把“菜单会等于记忆”当作前提。
- **菜单内容来自配置语言，不来自记忆**：`augmentTranslations` 用
  `CaptionLanguageSelection.menuCodes()` 添加行（`NativeCaptionBridge.java:145-226`，
  `CaptionLanguageSelection.java:63-69`），它读的是 `caption_language_menu`，不是
  `RememberedCaptionSelection`。如果记忆语言既不在原生列表、也不在配置列表，菜单里根本没有这一行，
  但请求层仍然能造出它。

### 3.6 冷启动 / 第一个视频：记忆和 YouTube 的持久选择没有对账

App 重启后：

1. `RememberedCaptionSelection.initialize()` 从 SharedPreferences 恢复 ru，`decision=ON`；
2. YouTube 自己的 caption model 恢复它持久化的轨道（本机历史默认是 zh-Hans）；
3. 两者没有比较。`onCaptionsShown()` 仍然先读记忆 ru；
4. 于是第一个视频就可能“菜单 zh-Hans、AI 字幕 ru”。

`hookRememberedDefault` 是唯一可能在这之前把原生默认改成 ru 的补丁，但它依赖：
`remembered` 已加载、`resolveRemembered` 能在当次列表里找到目标、初始化路径确实经过该
`defaultTrack` 方法。诊断显示这些条件并不总成立（原生仍 applied zh-Hans）。因此**冷启动的第一帧
没有“菜单 = 显示”的保证**。

### 3.7 记忆保存的是“轨道描述”，不是“目标语言”

`RememberedCaptionSelection.select(code, translation, automatic)` 保存三个维度：

- `language`：轨道语言；
- `translated`：该轨道是否带 `tlang`；
- `asr`：源轨是 ASR 还是人工。

这是**一条原生轨道描述**，不是“用户要翻译到哪个目标语言”。把它跨视频复用会产生语义错位：

- 用户选了一条原文字幕 → `translated=false` → 下次请求会被 `withoutTarget()` 剥掉 `tlang`，
  表现为“只要源字幕”；
- 用户选了一条译轨 → `translated=true` → 下次任何视频都被 `withCode()` 强制成这个目标；
- `asr` 还会影响 `resolveRemembered()` 对 `vss` 的匹配（`:1150-1152`），这是源轨来源，不是目标偏好。

根本问题不是“保存得不够多”，而是**保存的语义层级错了**：菜单选的是“哪个轨道/哪个目标”，
记忆却把它抽象成“目标语言 + 模式”，再应用到不相关的视频。

### 3.8 30 秒菜单窗口是另一个时间维度上的权威

`CaptionButtonController.nativeMenuRecentlyOpened()` 是一个 30 秒时间窗
（`NATIVE_MENU_WINDOW_MS=30_000`，`CaptionButtonController.java:38`、`:247-252`）：

- 窗口内 `CaptionTargetPolicy.apply()` 直接原样放行；
- 窗口内任何译轨请求都会被 `TARGET_CONFIRMED_BY_MENU_REQUEST` 写成记忆
  （`DeepSeekCaptionHook.java:155-179`）；
- 窗口外又回到记忆重写。

但窗口并不等于“菜单真的打开了”，也不等于“用户真的点了这一项”。它把“时间”当成了“选择证据”，
会造成两类错位：打开菜单但没选也会确认当前轨道；用户选了新语言但窗口过期后，请求又被旧记忆改写。
本次诊断没有 `TARGET_CONFIRMED_BY_MENU_REQUEST`，所以它不是本日志的主因，但它是同一权威分裂的
一部分，修复时不应保留为最终语言决策依据。

---

## 4. 为什么“再加一个补丁”不是根因修复

| 只修一处 | 仍然失败 |
|---|---|
| 在 `NATIVE_REMEMBERED_ACTIVATED` 里补 `selectNative(ru)` | 视频没有 ru 译轨时无行可选；冷启动仍可能走原生 zh-Hans；CC off/on 仍有别的分支；用户选别的语言仍被记忆重写 |
| 让 `hookRememberedDefault` 更可靠 | 只覆盖模型初始化，不覆盖运行时菜单选择、请求层、CC 按钮；版本相关路径仍可能绕过 |
| 让 `resolveRemembered` 也搜索 `nativeTracks` | 只能让“有原生目标轨”的视频菜单/显示一致；无目标轨/无自动翻译的视频仍会造 `tlang` |
| 删除 `CaptionTargetPolicy` 的 `withCode` | 显示层 `onCaptionsShown` 仍会用记忆启动 AI；菜单仍不更新；只是把“造 URL”挪到别处 |
| 删除持久化、只保留进程记忆 | 仍会在换视频时自动套用旧目标；只是重启后不出现，用户明确要的“记住上次选择”也没有正确实现 |

这些方案都保留了“记忆是权威、菜单是另一套”的前提。要满足用户描述的规则，必须把这个前提反过来。

---

## 5. 目标模型（建议的单一权威）

用户要的规则可以形式化为：

> **语言菜单当前勾选的项，是字幕显示语言的唯一权威。**
> 菜单勾选源字幕 → 显示源字幕；菜单勾选某个 Auto-translate 目标 → 显示该目标；
> 菜单勾选某个原生目标语言字幕 → 显示该原生字幕，不调用翻译 API。
> 换视频/重启后，记住的是这个“菜单选择状态”，不是另一套独立语言变量。

对应不变量：

1. **I1 — 单一权威**：目标语言只来自 YouTube 原生 caption model 的当前 selection
   （`NATIVE_TRACK_APPLIED` 的 `language/translated` 或菜单回调）。
   `CaptionChoice` 只能是它的投影，`RememberedCaptionSelection` 不应成为第二权威。
2. **I2 — 显示 = 菜单**：任何时候 overlay 的目标语言必须等于菜单勾选语言；
   不存在“菜单没有这一行但 overlay 显示它”的状态。
3. **I3 — 不做无源翻译**：只有当前视频的原生 `translatedTracks` 里存在该目标（或用户刚通过
   Auto-translate 菜单请求了它）时，才允许 `tlang=目标`；不得从源 URL 凭空加 `tlang`。
4. **I4 — 冷启动/换视频先读原生**：先等 caption model/menu 的 selection，再决定显示；
   不得在原生选择之前用持久记忆覆盖。
5. **I5 — 源/目标分离**：选择源字幕就是源字幕，不能把源轨语言当成翻译目标；
   `translated/ASR` 是源轨属性，不是目标偏好。
6. **I6 — 原生目标轨优先**：目标语言有原生轨道时，选中它（菜单随之），overlay 只做样式；
   没有原生轨道但有 Auto-translate 行时，才走 AI 翻译。
7. **I7 — 不用时间窗当选择证据**：移除 30 秒 `nativeMenuRecentlyOpened` 确认；
   只认真实的 `PREFERRED_TRACK`/菜单选择事件。

如果产品确实需要跨 App 启动记住语言：让 YouTube 自己持久化菜单选择（它现在就在做），
或者只持久化“菜单选项的镜像”，并在每次使用前用当前 `translatedTracks`/`nativeTracks` 验证；
验证失败就不显示该目标，而不是造一个。

---

## 6. “YouTube 自己会替换人工轨吗？”——本轮的确认边界

### 6.1 能确认的

- 当前补丁**已经自己实现**了“目标语言有原生字幕就不调用 API”：
  `SourceTrackPolicy.ownTargetTrack()`（`SourceTrackPolicy.java:126-155`）按
  `SourceSelection.sameWritingLanguage` 找人工轨优先的轨道；
- 诊断里 `DqAKQwagCDg` 的
  `SOURCE_TRACK_OWN_TARGET shown=ru/MANUAL;translation_request=false` 和
  `CAPTION_REBUILD ... source_passthrough` 证明它在显示层生效；
- 但 `startEngine` 走的是 `CaptionActions.activateSource` overlay（`NativeCaptionBridge.java:700-707`），
  不是 `selectNative`；因此菜单没有体现这次替换；
- AI 开启且 overlay 可见时，原生渲染器被 `suppressNativeDraw()` 隐藏
  （`NativeCaptionBridge.java:287-298`）。所以即使 YouTube 原生想替换，当前架构也不会让它成为
  用户可见的状态。

### 6.2 不能确认的

- 在这个 YouTube 版本里，Auto-translate 菜单选择某个语言时，YouTube 自己是否会优先使用同语言的
  人工轨；
- 换视频后 YouTube 自己的持久化选择是否会保留这个语言。

这两个问题在当前架构下都**不可观测**：请求在进入 YouTube 前已经被 `CaptionTargetPolicy` 改写，
原生渲染又被 overlay 遮住。要验证，需要一次对照实验：关闭记忆/AI，只用原生菜单，在“有/无目标人工轨”
的视频上观察 `NATIVE_TRACK_APPLIED` 与菜单。没有真机，本轮不能下结论。

### 6.3 结论

**不要新增一套“让 YouTube 自己去替换”的逻辑。** 补丁已经有 `SourceCatalog` /
`ownTargetTrack` 这份可用信息；缺的是把“当前显示的原生目标轨”同步成原生菜单选择，或者更彻底地
让原生选择成为唯一权威。当前实现的问题是“补丁替 YouTube 做了显示替换，却没有替它更新菜单”。

---

## 7. 复现步骤（离线与真机）

### 7.1 离线（无需手机，确定性）

1. 复制探针到测试树：

   ```powershell
   Copy-Item validation\runs\20261020-n46-caption-memory-root-cause\N46MemoryAuthorityProbeTest.java extensions\extension\src\test\java\app\yydarlinker\deepseekcaptions\
   ```

2. 运行：

   ```powershell
   .devenv\dev.cmd :extensions:extension:testDebugUnitTest --tests app.yydarlinker.deepseekcaptions.N46MemoryAuthorityProbeTest
   ```

3. 读取 `extensions/extension/build/test-results/testDebugUnitTest/TEST-app.yydarlinker.deepseekcaptions.N46MemoryAuthorityProbeTest.xml`；
   期望 `A/B ... nativeSelects=0`，且 activation URL 带 `tlang=ru`。
4. 删除复制到测试树的探针，保持 `git status` 干净。

### 7.2 真机（修复后复验，当前版本用于对照）

1. 清除 App 数据；打开第一个视频；在原生 Auto-translate 菜单选 ru；记录菜单勾选和字幕语言。
2. 切到一个**没有 ru 译轨/没有自动翻译列表**的视频：当前版本应出现 `TARGET_POLICY_APPLIED
   from_translated=false` + `ENGINE_REMEMBERED_ACTIVATED target=ru`，字幕 ru、菜单 zh/zh-Hans；
   期望修复后不再出现这条链路，字幕跟随菜单（源或不可用）。
3. 切到一个**有原生 ru 人工轨**的视频：当前版本应出现 `SOURCE_TRACK_OWN_TARGET shown=ru/MANUAL`，
   字幕 ru、菜单仍 zh-Hans；期望修复后菜单也显示 ru，且不调用 API。
4. 强杀 App 再打开第一个视频：当前版本记忆 ru 仍会显示 ru，而菜单可能仍是 YouTube 的 zh-Hans；
   期望修复后菜单与字幕一致。
5. 在 CC off → on、换视频、打开/关闭 AI 开关后重复 2–4。
6. 诊断验收：正常路径应满足 `NATIVE_TRACK_APPLIED` 的目标语言 == `CAPTIONS_SHOWN` 的 target；
   不应出现“source 请求被 `TARGET_POLICY_APPLIED` 加 tlang”或“`ENGINE_REMEMBERED_ACTIVATED`
   的 native 与 target 不同”而没有对应菜单更新的事件。

---

## 8. 代码证据索引

| 主题 | 位置 |
|---|---|
| 持久记忆加载 | `application-host/.../RememberedCaptionSelection.java:37-55`, `:113-128` |
| 记忆写入（任何显式轨道） | `RememberedCaptionSelection.java:57-65` |
| 换视频不重置目标 | `NativeCaptionBridge.java:712-738`（注释在 `:727-729`） |
| CC 打开优先记忆 | `NativeCaptionBridge.java:1074-1129`；`CaptionButtonController.java:619-626`, `:691-702` |
| 自动默认事件直接激活记忆 | `NativeCaptionBridge.java:604-628` |
| 显示/引擎入口不选原生 | `NativeCaptionBridge.java:697-708`；`CaptionActions.java:120-122` |
| 请求策略无可用性输入 | `caption-shared/.../CaptionTargetPolicy.java:16-38` |
| 请求策略调用/拦截 | `DeepSeekCaptionHook.java:91-102`, `:134-152`, `:181-250` |
| 菜单窗口 30 秒 | `CaptionButtonController.java:38`, `:247-252`；`DeepSeekCaptionHook.java:155-179` |
| 重选原生 best-effort | `NativeCaptionBridge.java:1043-1059` |
| `resolveRemembered` 只查译轨 | `NativeCaptionBridge.java:1139-1171` |
| 默认轨钩子 | `patches/.../NativeCaptionBridgePatch.kt:553-581`, `:583-633` |
| 原生目标轨显示替换 | `SourceTrackPolicy.java:126-155`；`NativeCaptionBridge.java:697-708` |
| 原生渲染遮罩 | `NativeCaptionBridge.java:287-298` |
| 菜单内容来自配置语言 | `NativeCaptionBridge.java:145-226`；`CaptionLanguageSelection.java:63-69` |
| 已把旧行为写成预期的测试 | `CaptionShownMemoryTest.java:183`；`CaptionHookMemoryTest.java:78` |

诊断证据行（文件 `caption-diagnostics-1.5.4-20261020-122129.txt`，SHA-256 见 §1.1）：

- 用户选 ru：第 4323 行 `1792470007131 NATIVE_TRACK_APPLIED ... ru/manual`
- QH838 源轨/无自动翻译：第 4611 行 `1792470011214 ... zh/manual`；第 4619 行
  `TARGET_POLICY_APPLIED ... from_translated=false`；第 4850 行
  `ENGINE_REMEMBERED_ACTIVATED target=ru;native=zh`
- DqAK 原生 ru 人工轨：第 5055 行 `SOURCE_TRACK_OWN_TARGET ... shown=ru/MANUAL`
- DqAK 原生 zh-Hans / 忽略 / 记忆 ru：第 5107 行 `1792470023509 ... zh-Hans/manual`；
  第 5141 行 `ENGINE_REMEMBERED_ACTIVATED target=ru;native=zh-Hans`
- DqAK CC off→on 的记忆激活：第 5857 行 `1792470034836 ... zh-Hans/manual`；
  第 5880 行 `NATIVE_REMEMBERED_ACTIVATED target=ru;native=zh-Hans`
- Aax 原生 zh-Hans / 记忆 ru：第 6049 行 `1792470042936 ... zh-Hans/manual`；
  第 6268 行 `ENGINE_REMEMBERED_ACTIVATED target=ru;native=zh-Hans`
- mha 源轨 / 记忆 ru：第 6748 行 `1792470058599 ... zh/manual`；
  第 6983 行 `ENGINE_REMEMBERED_ACTIVATED target=ru;native=zh`

---

## 9. 结论

1. 用户看到的三个现象不是三个 bug，而是**一个结构性事实**：语言菜单由 YouTube 原生模型拥有，
   字幕/翻译目标由补丁的持久记忆和请求改写拥有，两者没有同步，且后者可以在前者没有对应语言时
   凭空生成目标。
2. 现有 N43/N45 的修复都在“让记忆更可靠”这个方向上；但用户要的是“菜单是唯一状态”。
   继续在记忆侧加钩子，会保留“菜单 vs 显示”两个权威，无法满足“不管菜单选哪个语言都显示那个语言”。
   `docs/N45-CAPTION-MEMORY-20261020.md:5` 的契约本身写的是“官方 Auto captions 决定字幕是否
   自动出现；本补丁只决定出现时用哪种语言，并把显式选择持久化”——即“补丁记忆是语言权威”。
   用户现在要求“菜单是语言权威”。这是**契约冲突**，不是实现细节错误；N43/N45 在这个契约内做得
   越完整，现象就越稳定地复现。
3. 正确修复应把原生 selection 作为唯一权威：显示、请求、AI 目标都从它派生；目标不在当前视频
   原生译轨/菜单里时，不显示该目标，而不是造 `tlang`。原生目标轨优先，Auto-translate 作为菜单
   确实提供该语言时的回退。
4. 当前补丁已经具备“目标语言有原生字幕就不调用 API”的显示能力（`SourceTrackPolicy.ownTargetTrack`），
   缺的是让菜单反映这次选择；但这不应通过再写一套记忆/替换逻辑实现，而应通过单一权威状态机实现。
