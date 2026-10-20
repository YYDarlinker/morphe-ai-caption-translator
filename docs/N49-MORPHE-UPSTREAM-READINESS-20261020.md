# N49 — 以当前版本向 Morphe 官方提交 PR 的可行性评估（2026-10-20）

> 状态：只读评估。未改产品代码、未重建 MPP、未连接手机。
> 评估对象：`dev/1.5.4-deepseek` 工作树（N48 之后；1 个公开补丁 `AI caption translator`；MPP `592,741 B`，SHA-256 `BB906F...558D`）。
> 对照基准：Morphe Patches `main`（README 标注 1.47.0，2026-10-20 克隆）、`morphe-patcher` 官方文档、官方 PR 模板，以及官方现有的 `Captions` / `Voice over translation` 实现。

## 0. 结论

**以当前形态提交，官方不会合并。** 这不是补版权头、改包名或再修几个 bug 就能解决的问题；它同时撞上了范围、规模、架构、隐私、仓库结构和维护成本六道门槛。

四个直接判断：

1. **规模**：本项目的生产代码是 **31,086 行 / 137 个文件**。官方整个 YouTube 补丁 + 扩展约 **72,331 行**，本项目一个功能就占 **43%**；官方最接近的 `Voice over translation` 扩展是 **4,515 行 / 9 个文件**，本项目是它的 **6.9 倍**。
2. **结构**：4 个自建 Gradle 模块 + 4 个源码目录、独立 MPP、独立设置 XML、独立 14 语种 TSV、127.0.0.1 本地 HTTP 服务、抓取 YouTube Cookie；这些都不是官方仓库的构建/运行方式。
3. **产品**：官方已有 `Captions` 和 `Voice over translation`。一个 3 万行的独立 AI 字幕补丁，必须先回答“为什么不是给 `Captions`/VOT 加一个模式”，否则维护者没有理由接手第二套字幕基础设施。
4. **验证**：N48 之后没有真机/实体设备复验；官方 PR 模板要求至少覆盖支持范围的最小和最大版本，本项当前不满足。

**需要大规模精简吗？需要。** 但精简的对象不是用户功能，而是重复实现：自建设置框架、诊断档案、Token 记账、本地 HTTP 服务、Cookie 抓取、独立发布/取证工具。现实目标是从 **31k 行降到 12–16k 行**；如果按官方建议并入 `Captions`/VOT 并复用其翻译、网络、设置与缓存，有机会压到 **8–12k 行**。

**不减少功能能做到吗？核心功能可以，严格“一项不减”不行。** 实时翻译、源轨选择、Overlay、字号/透明度/位置、Shorts、分页、缓存、多配置、14 语种这些用户可见能力都可以保留；但本地 HTTP 服务、默认开启的诊断档案、抓 Cookie、DeepSeek 厂商默认值、像素级自定义设置界面属于实现/产品选择，上游化时必然要改，其中诊断和厂商默认值还会改变用户可见行为。

---

## 1. 官方标准到底是什么

### 1.1 受理范围

Morphe Patches 的 README 写得很清楚：

> We currently accept pull requests for fixes/improvements to YouTube, YT Music, and Reddit.
> If you have developed patches for other apps, you are encouraged to release your patches in a third party repository.

来源：[MorpheApp/morphe-patches — Contributing](https://github.com/MorpheApp/morphe-patches#contributing)。

这意味着：

- **YouTube 在受理范围内**，不是“第三方应用一律拒绝”；官方也接受过 `feat(YouTube - Swipe controls)` 这类新功能 PR。
- 但受理的是**可评审的 fix/improvement**，不是把一个独立 add-on 整体搬进官方仓库。一个 31k 行、4 个自建模块 + 4 个源码目录、带本地服务和自定义发布链的新子系统，已经超出“一个 PR”的常规尺度。
- 官方明确把“其他应用”的补丁导向第三方源。本项目虽然目标是 YouTube，但它的产品形态、发布节奏和品牌都是独立 add-on；维护者完全可能建议“继续作为第三方源”，即使代码本身合格。

### 1.2 补丁最小化与模块化

官方补丁开发文档的要求：

> Keep patches as minimal as possible. This reduces the risk of failing patches.
> Write patches with modularity and reusability in mind.
> Document your patches.

来源：[morphe-patcher — Project structure and conventions](https://github.com/MorpheApp/morphe-patcher/blob/main/docs/3_structure_and_conventions.md)。

这条标准对本项目最致命，因为问题不在“补丁层太大”，而在**扩展层自建了一整套与官方平行的基础设施**：

- 官方 `Captions` 根补丁由 4 个小补丁组成，单个文件多在 30–165 行；设置用 `PreferenceScreen.PLAYER.addPreferences(...)` 声明。
- 本项目补丁层 1,625 行、10 个文件，其中 `NativeCaptionBridgePatch.kt` 单文件 **655 行**；扩展层 31k 行。
- 官方 `Voice over translation` 的扩展 4,515 行，已经包含翻译提供方、TTS、缓存、底部面板和设置；本项目却是它的近 7 倍。

官方并不禁止大补丁（`VideoInformationPatch.kt` 约 900 行），但“大”必须建立在复用官方共享库、只做本功能独有逻辑的基础上。本项目的 31k 行里包含大量官方已有的能力：HTTP 请求、设置渲染、本地化、日志、缓存、播放器按钮、弹窗。

### 1.3 仓库结构

官方仓库的结构是固定的：

- 补丁：`patches/src/main/kotlin/app/morphe/patches/youtube/...`
- 扩展：`extensions/youtube/src/main/java/app/morphe/extension/youtube/...`
- 命名空间：`app.morphe.patches` / `app.morphe.extension`
- 兼容范围：`app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE`，当前 9 个目标，最低 `20.21.37`、`minSdk 26`
- 设置/localization：`PreferenceScreen` + `patches/src/main/resources/addresources/values*/<context>/strings.xml`，由 Crowdin 管理

本项目是：

- 4 个显式 Gradle 模块：`caption-contracts`、`caption-shared`、`translation-core`、`ui-preview`
- 4 个被 `sourceSets` 拉进扩展的源码目录：`infrastructure`、`application-host`、`presentation`、`ui`（`patches` / `extensions:extension` 由插件生成）
- 自建 `extensions/extension.mpe`，命名空间 `app.yydarlinker.extension`
- 自建 `patches/src/main/resources/captionlocales`、`source-keys.tsv`、`index.txt`
- 自建构建目录 `build/n39/gradle/...`、`PatchListGenerator`、`verify_integration.py`、自定义 release 流程

这些在独立仓库里是合理的工程选择；在官方仓库里则是**构建模型和评审模型的改变**。官方 `settings.gradle.kts` 只 `include(":patches:stub")`，扩展模块由插件生成。为一个功能新增 4 个模块 + 4 个源码目录，维护者通常不会接受，除非先经过专门讨论。

### 1.4 设置与本地化

官方设置是声明式的：

```kotlin
PreferenceScreen.PLAYER.addPreferences(
    PreferenceScreenPreference(
        key = "morphe_captions_screen",
        sorting = PreferenceScreenPreference.Sorting.UNSORTED,
        preferences = settingsMenuCaptionGroup,
    ),
)
```

官方字符串放在 `patches/src/main/resources/addresources/values*/youtube/strings.xml`，由 Crowdin 同步；当前仓库有 **492 个 strings.xml**，覆盖数十个 locale，并使用 `values-ar-rSA`、`values-in-rID`、`values-pt-rBR`、`values-vi-rVN` 这类区域限定符。

本项目则是：

- 在 `CaptionSettingsPatch.kt` 里手工构造 XML，插入 `res/xml/morphe_prefs*.xml`，并锚定在 `morphe_vot_screen` 后面；
- 使用 `android.preference.Preference` 等已废弃 API 和自定义 `CaptionSettingPreference`；
- 设置键（`deepseek_*`）不在官方 `Settings` 注册表里，使用私有 `SharedPreferences`；
- 14 个 locale 由 `captionlocales/values*/...` + `source-keys.tsv` 维护，中文原文当 key，共 **243 个 key × 14 locale**；
- locale 目录名是 `values-ar`、`values-in`、`values-pt`、`values-vi`，与官方的区域限定符不一致。

这会带来三个上游无法接受的问题：

1. **设置不可被官方备份/导入导出/搜索/主题体系完整接管**，因为键没有注册；
2. **翻译无法进入 Crowdin 流程**，243 个 key × 14 语言是仓库内的私有资产；
3. **设置页面是另一套渲染/生命周期实现**，官方改 `AbstractPreferenceFragment` 时本项目会跟着坏。

### 1.5 网络、隐私与安全边界

官方接受网络型补丁：`Voice over translation` 支持 Google、MyMemory、OpenRouter（含付费 API Key），`DeArrow`、`SponsorBlock`、`AI channel filter` 都访问外部服务。所以“AI/付费 API/联网”本身不是拒绝理由。

官方没有的是“一个字幕功能自带本地 HTTP 服务”：

- 本项目 `LoopbackCaptionServer` 在 `127.0.0.1` 开 `ServerSocket`，把 YouTube 的 timed-text 请求改写到本地，返回不可见 cue，让原生渲染器“满意”，再由 Overlay 画译文。
- 为了让明文 HTTP 能访问本地，`CaptionSettingsPatch` 修改 `AndroidManifest.xml` 的 `android:networkSecurityConfig`，在现有配置里追加 `cleartextTrafficPermitted="true"` 的 `127.0.0.1` 域。
- `DeepSeekCaptionHook` 还会把不该发的译轨请求改写到 `http://127.0.0.1:9/deepseek-caption-unavailable` 作为“关闭的 sink”。

406 行本地服务 + 全局 manifest 安全配置 + 假 URL sink，在官方评审里会被逐条质疑：生命周期、端口、耗电、并发、与 `Network proxy`/`Override certificate pinning` 的交互、明文流量、错误处理、可观测性。官方现有补丁没有为单个字幕功能启动本地 HTTP 服务的先例；官方只在一个全局安全补丁里修改 `networkSecurityConfig`（`Override certificate pinning`），且它 `default = false`。

其他安全/隐私点：

- `FreshYouTubeCookies` 用 Chrome UA 抓 `https://www.youtube.com/sw.js`，取匿名 Cookie。官方已有 `CaptionCookies` 补丁；上游会要求复用或让用户提供，而不是抓取。
- 诊断档案在播放路径持续入队写盘，默认记录请求/生命周期元数据；`display_text_debug` 打开后记录原文/译文。README 明确说导出文件可能包含视频标识、字幕文本、提供方响应和端点。作为“用户主动导出”的调试功能可以讨论；作为默认常驻档案，上游会要求 opt-in、保留上限和更明确的告知。
- 仓库根目录还跟踪了一个 **354,054 B** 的 `caption-diagnostics-1.3.5-...txt`，以及 `recovered/` 下 **1,448 个文件**。这些是本地取证/历史资产，不应进入上游 PR 分支。

### 1.6 验证与许可

官方 PR 模板：

```
- [ ] Tested on both the minimum and maximum supported versions
- [ ] Tested on experimental supported versions (Optional)
```

来源：[PULL_REQUEST_TEMPLATE.md](https://github.com/MorpheApp/morphe-patches/blob/main/.github/PULL_REQUEST_TEMPLATE.md)。

本项目 N48 的状态是：238 + 264 个 JVM/Robolectric 测试通过、243×14 本地化通过、MPP 构建成功；但**未连接真机、未安装/写入手机、没有 after 诊断**。这不足以勾选官方模板的第一项。

许可方面：

- 本项目是 GPLv3，方向正确；
- 但 137 个生产文件中 **135 个在前 8 行没有版权/许可头**；官方文件普遍带 Morphe/ReVanced 版权头；
- 本地 `NOTICE` 只保留了 7c 的简短文本，缺少官方 NOTICE 的 7b（保留用户通知）和 7e（商标权不授予）；这与 `CaptionResourceMapping.kt` 中“See the included NOTICE for GPLv3 Section 7 terms”的表态不一致。
- 官方还要求衍生作品使用独立品牌；本项目“Anchored AI Captions”本身没问题，但上游化后代码/包名/文档都要换成 `app.morphe...`，不能继续用 `app.yydarlinker`。

---

## 2. 当前版本对照记分卡

| 维度 | 当前版本 | 官方期望 | 判断 |
|---|---|---|---|
| 受理范围 | YouTube 功能，但整体是独立 add-on | 可评审的 fix/improvement | ⚠️ 需先 RFC |
| 规模/最小化 | 31,086 行生产代码，1 个功能 | 复用官方库，补丁/扩展保持可评审 | ❌ 6.9× VOT |
| 仓库结构 | 4 个自建模块 + 4 个源码目录 + ui-preview + 独立 MPE | `patches/youtube` + `extensions/youtube` | ❌ 需并入 |
| 补丁层 | 1,625 行 / 10 文件，最大 655 行 | 官方 Captions 单文件 30–165 行 | ❌ 接缝过多 |
| 设置 | 手工 XML 注入，`android.preference`，私有 prefs | `PreferenceScreen` + 注册 Settings | ❌ 另起炉灶 |
| 本地化 | 243 key × 14，TSV + 中文原文 key | addresources + Crowdin，区域限定符 | ❌ 无法直接上游 |
| 网络架构 | 127.0.0.1 HTTP 服务 + manifest 安全配置 | 直接 hook/官方网络层 | ❌ 最大架构阻断 |
| Cookie | 抓 `/sw.js`，Chrome UA | 官方 `CaptionCookies` 或用户提供 | ❌ 不应抓取 |
| 隐私 | 常驻诊断档案，可导出视频/字幕信息 | opt-in、最小化、明确告知 | ⚠️/❌ 需重做 |
| 官方 API 复用 | 15 处 `Class.forName`，hook FlyoutUtils/AbstractPreferenceFragment | 官方 hook/API，避免跨补丁内部耦合 | ❌ 脆弱 |
| 兼容范围 | 7 个目标，minSdk 28；2 个实验目标未测 | 官方 9 个目标，minSdk 26 | ⚠️ 需对齐或说明 |
| 验证 | 83 个测试文件 / 8,785 行；N48 无真机 | PR 模板 min/max 设备验证 | ⚠️ 测试强，设备证据弱 |
| 许可 | GPLv3；135/137 无头；NOTICE 不完整 | 完整头 + 官方 NOTICE | ❌ 需补齐 |
| 文档 | 英文 README 仍写“记忆”；ARCHITECTURE 仍写失败显示原文；COMMUNITY 仍写 2 个补丁 | 与当前行为一致 | ❌ 自相矛盾 |
| 仓库卫生 | 工作树 73 文件改动/5,790 insertions；跟踪诊断与 recovered | 干净分支、单一目的 | ❌ 不能直接提 |

需要说明：**这不是“代码质量差”的评估**。项目有不少上游会欣赏的资产：83 个测试文件 / 8,785 行的回归覆盖、失败关闭的 fingerprint、DEX/composition 审计、API Key 的 Keystore 加密、没有分发预编译 APK、注释密度高，N48 还主动删掉了与官方语言权威冲突的记忆补丁。问题不在基本功，而在**形态、规模和维护边界**：这些优点不足以让维护者接手 31k 行、带本地服务器和私有设置体系的新子系统。

---

## 3. 必须修改的内容

### P0 — 不解决就不会进入评审

**P0.1 先把“要不要做”谈清楚，而不是直接开 PR**

先开一个简短的 issue/RFC，回答四个问题：

1. 这个功能放在哪里？独立的 `AI caption translator`，还是 `Captions` 的一个选项，还是 `Voice over translation` 的“文字模式”？
2. 为什么官方已有的 VOT（翻译 + TTS）不能覆盖？本项目的增量价值是“字幕时间轴/分页/Overlay”，还是“另一个翻译提供方”？
3. 谁维护？31k 行、7 个目标、依赖 YouTube 内部字幕接缝，官方是否有维护意愿？
4. 数据与费用：用户自备 API Key、字幕文本出境、诊断保留策略，官方能否接受？

没有这个前置共识，后面所有重构都可能白做。

**P0.2 并入官方模块与命名空间**

- 删除 `caption-contracts`、`caption-shared`、`translation-core`、`infrastructure`、`application-host`、`presentation`、`ui`、`ui-preview` 这些自建模块/源码目录（其中 4 个是显式 Gradle 模块）；代码并入 `extensions/youtube`（必要时 `extensions/shared`）。
- 补丁并入 `patches/src/main/kotlin/app/morphe/patches/youtube/layout/captions/`，作为 `Captions` 的子补丁或独立子补丁，使用 `sharedExtensionPatch`、`settingsPatch`。
- 删除 `extensions/extension.mpe`、`PatchListGenerator`、自定义 `build/n39` 目录、`tools/verify_integration.py` 等发布/取证工具；这些留在第三方仓库，不进上游 PR。
- 包名改为 `app.morphe.patches.youtube...` / `app.morphe.extension.youtube...`；不要保留 `app.yydarlinker`。

**P0.3 删除本地 HTTP 服务与 manifest 安全配置修改**

- 删除 `LoopbackCaptionServer`、`DeepSeekCaptionHook.rewriteUrl()` 中的 loopback 改写、`127.0.0.1:9` 的 closed sink。
- 删除 `CaptionSettingsPatch.allowLoopbackTraffic()` 对 `android:networkSecurityConfig` 的修改。
- 改为直接 hook 原生字幕渲染/字幕 cue 注入：让原生层拿到空文本或透明文本，Overlay 只负责画译文；不再需要“本地 HTTP 返回不可见 cue”这一层。
- 如果短期必须保留 loopback 作为 PoC，至少把它做成 `default = false` 的独立实验补丁，并在 RFC 中明确它是临时方案；不要把它作为主路径。

**P0.4 删除常驻诊断档案与仓库内的取证资产**

- 诊断改为显式 opt-in（用户点“开始记录”才写），或直接复用官方 `Logger`/`Enable debugging` 的导出方式；不要默认在播放路径常驻写盘。
- 默认不记录视频 ID + 字幕文本 + 提供方响应；如必须记录，明确保留上限、脱敏、到期清理。
- 从 PR 分支删除 `caption-diagnostics-1.3.5-...txt`、`recovered/`、`validation/runs/`、`docs/N*` 等历史/取证资产；它们可以留在第三方仓库，但不属于上游变更。

**P0.5 设置与本地化改用官方机制**

- 用 `PreferenceScreen.PLAYER.addPreferences(...)` / `PreferenceScreen.VIDEO.addPreferences(...)` 声明设置，不再手工拼 XML、不再锚定 `morphe_vot_screen`。
- 把 `deepseek_*` 键注册进官方 `Settings`，使用 `SharedSettings`/官方 `SharedPreferences` 体系；这样导入导出、搜索、主题、备份才能覆盖。
- 用官方 preference 基类（`SwitchPreference`、`ListPreference`、`TextPreference`、`NonInteractivePreference`、`SeekBarPreference` 等）替换 `CaptionSettingPreference`/`CaptionSettingCategory`/自定义 `android.preference` 实现；只保留确实必要的自定义控件（如实时预览），并让它继承官方基类。
- 字符串迁到 `patches/src/main/resources/addresources/values*/youtube/strings.xml`，用稳定 key；14 个 locale 可以保留，但交给 Crowdin/官方资源流程，不要 TSV + 中文原文 key。
- locale 目录改用官方限定符（`values-ar-rSA`、`values-in-rID`、`values-pt-rBR`、`values-vi-rVN` 等）。

**P0.6 删除 Cookie 抓取**

- 删除 `FreshYouTubeCookies` 的 `/sw.js` 抓取和 Chrome UA。
- 复用官方 `CaptionCookies` 补丁的 Cookie 设置，或在缺少 Cookie 时按失败处理并提示用户；不要由字幕补丁自行抓取 YouTube 站点 Cookie。

**P0.7 清理分支、补头、补 NOTICE**

- 从官方 `dev` 建干净分支，只 cherry-pick 与本功能相关的提交；不要把 73 个文件、5,790 行 insertion 的整段历史直接作为 PR。
- 给 135 个无头文件加 GPLv3/Morphe 贡献头；保留从官方 fork 的文件的原始头。
- 用官方完整 NOTICE（7b/7c/7e）替换本地精简版。
- 删除自定义 `.releaserc`、`package.json`、`patches-bundle.json`、`patches-list.json` 的生成逻辑；上游用自己的发布流程。

**P0.8 真机/实体设备验证**

- 至少覆盖补丁声明的最小和最大目标（当前是 21.16.256 和 21.40.161；若按官方范围则是 20.21.37 和 21.40.161）。
- 用真实 patcher + 原始 APK 完整 composition，确认所有补丁应用、字幕钩子绑定、安装后可播放；保留 DEX/日志证据。
- N48 之后没有真机复验，这一项目前是空的。

### P1 — 不做会显著降低通过概率

**P1.1 兼容范围对齐**

- 用官方 `COMPATIBILITY_YOUTUBE`（9 个目标，minSdk 26）或明确声明更窄的范围并解释原因；不要复制一份 7 个目标的私有 `Constants.kt`。
- 当前 minSdk 28 与官方扩展 minSdk 26 不一致；要么补 26/27 的运行时保护，要么在补丁层显式声明 `minSdk 28` 并接受 20.21.37 用户不可用。
- 5 个 experimental 目标中至少 2 个未测试；在 PR 描述里如实标注，不要用“已用真实补丁器验证”覆盖所有目标。

**P1.2 降低对官方内部实现的耦合**

- 15 处 `Class.forName`、对 `FlyoutUtils`、`AbstractPreferenceFragment`、`PlayerFlyoutMenuComponentsFilter`、`ShortsPlayerState` 的 hook，都是跨补丁内部耦合；官方改一行就可能坏。
- 优先使用官方公开 hook/`sharedExtensionPatch` 提供的接口；确实缺 hook 时，先向上游提一个小 PR 增加 hook，再在此基础上做功能。
- 不要直接往官方 `FlyoutUtils` 里加 bridge 方法；用官方 `addPlayerBottomButton`/`addFlyoutButton` 等扩展点。

**P1.3 提供商默认值与网络安全**

- `DeepSeekConfig.DEFAULT_BASE_URL = https://api.deepseek.com`、`DEFAULT_MODEL = deepseek-v4-flash` 是厂商默认值；官方 VOT 虽然把 DeepSeek V4 Flash 作为 OpenRouter 模型之一，但不会把某一家的直连端点设为默认。上游化时改成“无默认/通用 OpenAI 兼容”或官方 VOT 的提供方列表。
- `saveBaseUrl()` 目前接受 `http://`；即使 manifest 的 base-config 默认禁止明文，UI 仍允许用户输入。上游会要求 HTTPS-only（localhost 除外），并在校验层拒绝 `http`。
- 任意 base URL + 自动模型目录会带来 SSRF/支持面/密钥外发风险；至少限制 scheme、禁止 userinfo/fragment、对未知 host 二次确认，或改成已知提供方列表 + 自定义。

**P1.4 精简非核心子系统**

按当前文件统计：

| 子系统 | 行数 | 上游判断 | 建议 |
|---|---:|---|---|
| 诊断（含诊断 UI） | 1,544 | 非核心，隐私敏感 | 改成 opt-in + 官方 Logger，或删 |
| Token 记账（Audit + Report） | 889 | 非核心 | 保留一个简单费用摘要即可 |
| 设置/配置/预览 UI（含配置/模型/诊断 UI） | 5,085 | 官方已有 | 用官方 preference 基类，目标 ~1,500 |
| Loopback 服务 | 406 | 不应存在 | 删除，换直接 hook |
| Cookie 抓取 | 124 | 不应存在 | 删除，用官方 CaptionCookies |
| 测试（不计入 31k 生产代码） | 8,785 | 质量资产，但过重 | 保留 2–3k 高价值回归 |

这些削减不是“减功能”，而是把官方已有的能力还给官方。设置类里配置/模型/密钥相关约 1.4k 行，仍会保留功能，只是改用官方 Preference。

**P1.5 修正文档自相矛盾**

- `README.md:13` 仍写“Optionally remembers your caption selection across videos for the current app session”；N48 已删除记忆补丁。
- `docs/ARCHITECTURE.md:11` 仍写“A failed block keeps the video's own source line visible”；N48 的契约是“任何情况下不显示原文”。
- `docs/COMMUNITY-INTRODUCTION.md:33` 仍写“An optional second patch remembers...”；当前只有 1 个公开补丁。
- `docs/COMPATIBILITY.md` 同时出现 1.47.0 和 1.48.0-dev.6，版本叙事需要统一。
- 上游 reviewer 会先读 README/架构文档；这些矛盾会直接削弱可信度。

**P1.6 测试策略**

- 83 个测试文件、8,785 行是优势，但官方 `patches/src/test` 只有 2 个文件；把整套 Robolectric/MockWebServer 基础设施带进上游，会增加构建时间和依赖面。
- 保留与补丁结构、分页、协议、失败关闭、设置迁移相关的高价值测试；把纯 UI/自定义控件测试留在第三方仓库。
- 至少加一个“真实 APK composition -> 补丁应用 -> 钩子绑定”的集成测试，这是官方更看重的。

### P2 — 代码质量与可维护性

- `CaptionOverlayV2.java` 1,477 行、`NativeCaptionBridge.java` 723 行、`CaptionButtonController.java` 670 行、`DeepSeekSliderPreference.java` 598 行；上游会要求拆分并补注释。
- `NativeCaptionBridgePatch.kt` 里的 `METADATA_SCHEMA` / `TRANSLATION_ENTRY_SCHEMA` 是不可读的二进制字符串；要么改成可读的 protobuf 定义/命名常量，要么在上游评审前解释来源与稳定性。
- `CaptionSettingsPatch` 用 `source-keys.tsv` 把中文原文映射到资源名；原文一改就静默回退。改成稳定 key。
- 统一 ktlint/Java 格式；当前项目没有官方 CI 的格式检查，但上游 review 会看。
- 提交历史用语义化 commit，按 PR 拆分；不要一个 commit 里同时删记忆补丁、改分页、改文档。

---

## 4. 到底要精简多少

先说结论：**“大规模精简”是必须的，但不是把 31k 行直接删到 5k；31,086 行只算生产代码，8,785 行测试另计。** 合理的做法是三层：

### 第一层：机械清理（31k → 29–30k 生产代码）

- 并入官方模块、删除自建构建/发布工具：约 -0.5k；
- 删除 `ui-preview`、重复的 `Constants`/资源映射、死代码：约 -1k；
- 补头、改包名、改 locale 目录、清理历史资产。

这一层不减功能，但**不足以让官方接受**，因为架构问题还在。

### 第二层：去重（29–30k → 20–23k 生产代码）

- 设置/配置/预览 UI：5,085 → ~1,500（约 -3.6k，用官方 preference 基类，只保留预览等必要自定义控件）；
- 诊断：1,544 → ~300（约 -1.2k，opt-in + 官方 Logger）；
- Token 记账：889 → ~150（约 -0.7k，简单摘要）；
- Loopback：406 → 0（直接 hook）；
- Cookie 抓取：124 → 0（用官方 CaptionCookies）；
- 其他重复/死代码：约 -1k；
- 测试另计：8,785 → ~3,000（保留高价值回归，不计入生产代码）。

这一层是真正的“大规模精简”，但用户可见能力基本不变。

### 第三层：与官方能力融合（20–23k → 12–16k；并入 VOT/Captions 可到 8–12k）

- 用官方 `Requester`/`Route` 替换自建 HTTP 层；
- 用官方 `TextTranslator`（VOT 已有 Google/MyMemory/OpenRouter）替换部分翻译协议/提供方逻辑；
- 用官方 `Settings`/`SharedSettings` 替换私有配置；
- 用官方播放器/Overlay 基类替换部分自定义 View；
- 保留本项目真正独有的部分：字幕时间轴、分页、引文对齐、失败关闭、源轨选择、Overlay 渲染。

如果官方同意把 AI 字幕做成 `Captions` 或 VOT 的一个模式，这一层可以做得更深；如果坚持独立补丁，第三层会受限于官方是否愿意暴露 hook。

MPP 体积也会随之下降：当前 **592,741 B**；按上述去重，合理目标是 **250–350 KB**。

---

## 5. 不减少功能，是否可能

把“功能”拆成“用户可见能力”和“实现/产品选择”两层看：

| 能力 | 能否保留 | 说明 |
|---|---|---|
| 实时翻译原生字幕轨 | ✅ | 核心，保留 |
| 源轨选择（人工优先/口述语言） | ✅ | 核心，保留 |
| Overlay、字号、透明度、垂直位置 | ✅ | 核心，保留；实现可换官方基类 |
| 普通视频/全屏/Shorts | ✅ | 核心，保留 |
| 分页、阅读时间、引文对齐、失败关闭 | ✅ | 本项目真正的差异化，保留 |
| 翻译缓存 | ✅ | 保留，可复用官方缓存模式 |
| 多套 API 配置/模型选择 | ✅ | 保留能力，UI 用官方 List/Text Preference |
| 自定义翻译要求 | ✅ | 保留，TextPreference |
| 测试 API | ✅ | 保留，小请求 |
| 14 语种 UI | ✅ | 保留，迁 Crowdin 后可能更多 |
| Flyout 快捷开关 | ✅ | 保留，但注册官方 Settings |
| 诊断导出 | ⚠️ | 可保留为 opt-in；默认常驻档案不行 |
| Token 费用 | ⚠️ | 可保留简单摘要；精确审计 889 行不值得 |
| 本地 HTTP 服务 | ❌ | 内部实现，不是用户功能；必须换 |
| 抓取 YouTube Cookie | ❌ | 内部实现；用官方 Cookie 补丁 |
| 默认 DeepSeek 端点/模型 | ❌ | 产品选择；改通用/无默认 |
| 像素级自定义设置界面 | ⚠️ | 能力可保留，外观/交互会向官方靠拢 |
| 任意 HTTP/自动模型发现 | ⚠️ | 可保留大部分，但会加 HTTPS/提供方限制 |

所以：

- **“核心翻译功能一项不减”是可能的**，代价是数月的重构和真机验证。
- **“连实现方式、默认值、UI 像素、诊断行为都不变”是不可能的**；这些本来就不是官方会接受的产品形态。
- 如果官方要求并入 VOT，部分“多套配置/模型目录”可能会被官方提供方列表替代；这是产品层面的谈判，不是代码问题。

---

## 6. 如果一定要 upstream：建议的分阶段方案

### 方案 A：并入 `Captions`（推荐）

把 AI 字幕作为官方 `Captions` 设置页里的一个子项，而不是新的顶级补丁：

1. **PR1（~2–3k 行）**：`Captions` 子补丁 + 设置骨架 + Overlay/原生字幕接管 + 源轨选择；不联网，先证明补丁结构、设置、渲染在 min/max 上成立。
2. **PR2（~4–6k 行）**：翻译协议/请求/缓存 + 首个提供方；复用官方 HTTP/设置/本地化；不做诊断档案。
3. **PR3（~3–4k 行）**：分页/引文对齐/失败关闭/质量校验；把本项目真正的算法价值带进来。
4. **PR4（~2k 行，可选）**：多配置、模型目录、Flyout 开关、opt-in 诊断。

每个 PR 独立可构建、可回滚、可测试；不要让维护者一次 review 31k 行。

### 方案 B：并入 `Voice over translation` 的“文字模式”

如果官方认为“字幕翻译”与 VOT 太接近，可以复用 VOT 的：

- 翻译提供方/密钥/模型设置；
- `TranscriptTranslator`/`TextTranslator`/缓存；
- 费用提示/隐私说明；
- 设置页面和本地化。

本项目只保留“文字 Overlay + 时间轴/分页”这一层。这样代码量可能压到 **6–9k**，但需要 VOT 维护者同意扩展产品边界。

### 方案 C：保留第三方源，只上游通用能力

如果官方对“AI 字幕”产品本身不感兴趣，最稳的是：

- AI 翻译继续留在本项目；
- 把通用的“源轨选择/字幕分页/原生字幕接管/设置集成模式”拆成小 PR 给官方；
- 不要求官方接收 31k 行和付费 API 支持责任。

这通常比“整体迁入”更容易被接受，也能让本项目继续独立迭代。

---

## 7. 最终判断

| 提交形态 | 判断 | 主要原因 |
|---|---|---|
| 当前 31k 行整包 | **不会合并** | 规模、loopback、设置/本地化、隐私、仓库结构 |
| 只做改名/补头/格式化 | **仍不会** | 架构和产品问题没解决 |
| 完成 P0/P1、缩到 12–16k、先 RFC、真机验证 | **有机会，但不确定** | 取决于维护者是否愿意接手；可能要求并入 Captions/VOT |
| 并入 Captions/VOT 的 8–12k 版本 | **机会最大** | 复用官方基础设施，评审面小 |
| 保留第三方源，只上游通用改进 | **最可行** | 不争产品边界，PR 小，维护责任清晰 |

一句话：**当前版本不是“再打磨一下就能提”的 PR，而是一个需要重新划定产品边界、重做网络/设置/本地化架构、并把规模减半的工程。** 如果目标是“让官方接受”，先做 RFC，再做最小 PoC，再拆 PR；如果目标是“让用户用上”，继续维护第三方源是更理性的选择。

---

## 8. 附：关键证据与文件

- 生产代码规模：137 文件 / 31,086 行（`caption-contracts`、`caption-shared`、`translation-core`、`infrastructure`、`application-host`、`presentation`、`ui`、`patches` 的 `src/main`）。
- 官方 YouTube 代码：25,672 行补丁 + 46,659 行扩展 = 72,331 行（本地克隆 `main`，按行数粗略统计，含注释/空行）。
- 官方 VOT：`extensions/youtube/.../patches/voiceovertranslation` 9 文件 / 4,515 行；补丁 `VoiceOverTranslationPatch.kt` 约 100 行。
- 官方 Captions：`patches/youtube/layout/captions` 6 文件，单文件 30–165 行；设置通过 `PreferenceScreen.PLAYER.addPreferences`。
- 官方设置 UI：`extensions/youtube/.../settings` 24 文件 / 3,292 行（全部 YouTube 设置）；本项目设置/配置/预览相关 28 文件 / 5,085 行。
- 官方本地化：`patches/src/main/resources/addresources` 下 492 个 `strings.xml`；本项目 `captionlocales` 14 locale / 243 key。
- 官方测试：`patches/src/test` 2 个文件；本项目 83 个测试文件 / 8,785 行。
- 本项目 MPP：`build/n39/gradle/patches/libs/patches-1.5.4.mpp`，592,741 B，SHA-256 `BB906F3EA60EABED517EB7FC7C8F2422547BD6838C2F015A22D96F31522A558D`。
- 关键实现：`infrastructure/.../LoopbackCaptionServer.java`（406 行）、`patches/.../CaptionSettingsPatch.kt`（203 行）、`application-host/.../DeepSeekCaptionHook.java`（219 行）、`infrastructure/.../FreshYouTubeCookies.java`（124 行）、`patches/.../NativeCaptionBridgePatch.kt`（655 行）。
- 官方参考：[README Contributing](https://github.com/MorpheApp/morphe-patches#contributing)、[Patcher conventions](https://github.com/MorpheApp/morphe-patcher/blob/main/docs/3_structure_and_conventions.md)、[PR template](https://github.com/MorpheApp/morphe-patches/blob/main/.github/PULL_REQUEST_TEMPLATE.md)、[Voice over translation](https://github.com/MorpheApp/morphe-patches/tree/main/extensions/youtube/src/main/java/app/morphe/extension/youtube/patches/voiceovertranslation)、[Captions](https://github.com/MorpheApp/morphe-patches/tree/main/patches/src/main/kotlin/app/morphe/patches/youtube/layout/captions)。
