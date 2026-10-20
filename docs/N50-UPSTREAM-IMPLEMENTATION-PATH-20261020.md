# N50 — 上游化实现路径：从当前 31k 行到 Morphe 官方可评审的 AI 字幕补丁（2026-10-20）

> 状态：**计划**。未改产品代码、未重建 MPP、未连接手机。
> 目标：把当前 31,086 行 / 137 文件的生产代码，改造成 Morphe 官方可评审、可合并的 **8–15k 行、2–4 个 PR**。
> 前提：Reddit 管理员说“乍看没问题，可以提 PR”，这是**产品兴趣信号，不是代码预审**。本路径把“先拿到维护者的范围/位置确认”作为第一个 gate，不直接开 31k 行 PR。

## 0. 执行摘要

推荐按 **方案 A：作为 `Captions` 的子补丁** 上游化：

- 补丁位置：`patches/src/main/kotlin/app/morphe/patches/youtube/layout/captions/aicaption/`
- 扩展位置：`extensions/youtube/src/main/java/app/morphe/extension/youtube/patches/aicaption/`
- 设置：挂在官方 `Captions` 设置页内，用 `PreferenceScreen` 声明，不再手写 XML
- 运行：保留原生字幕语言权威；AI 只翻译用户已选中的 Auto-translate 译轨；失败不显示原文
- 网络：用官方 `Requester`/`Route`；删掉本地 HTTP 服务和 Cookie 抓取
- 诊断：用官方 `Logger`/`Enable debugging`；不做默认常驻档案
- 规模：31k → 13–16k；如果官方要求并入 VOT 的“文字模式”，可到 8–11k

四个 PR 的顺序：

1. **PR1**：补丁骨架 + 官方设置 + 原生字幕接管（不含网络，~2k 行）
2. **PR2**：翻译协议/请求/缓存 + 一个通用 OpenAI 兼容提供方（~4–5k 行）
3. **PR3**：分页/引文对齐/人工 cue/全语种校验（~3–5k 行）
4. **PR4**（可选）：多配置、模型目录、Flyout 开关、opt-in 导出（~1.5–2k 行）

时间：**8–12 周开发 + 2–4 周 review**；按 3–4 个月日历排，不要按“两周提 PR”排。

---

## 1. Gate 0：先拿维护者确认，再动工

### 1.1 先做的三件事

1. 冻结当前 N48 工作树：提交/打 tag `upstream-baseline-20261020`，保存测试结果、MPP hash、录屏。
2. 在官方仓库开一个**简短的 feature issue / discussion**，不是 PR。标题建议：
   `[Feature] AI caption translation for YouTube`
3. 在 issue 里只放一页内容，附 30–60 秒演示视频，明确说“当前实现 31k 行，准备按官方规范重构成 2–4 个 PR；先确认产品位置和范围”。

### 1.2 RFC 必答的 8 个问题

1. **放哪里**：`Captions` 子项、独立 `AI caption translation` 补丁，还是 `Voice over translation` 的文字模式？
2. **为什么不是 VOT**：VOT 是 TTS；本功能是文字 Overlay + 字幕时间轴/分页。两者能否共用翻译/缓存/设置？
3. **语言权威**：是否继续完全跟随 YouTube 原生菜单和官方 `Captions`/`Preferred caption language`？（当前 N48 契约是“是”。）
4. **数据与隐私**：字幕文本发到用户自配的 endpoint；不做遥测；诊断是否只走官方 `Logger`？
5. **API Key**：用户自备；是否接受 Keystore 加密，还是像 VOT 一样用普通 `TextPreference`？
6. **提供商**：首版只做通用 OpenAI 兼容 endpoint，还是必须内置 OpenRouter/Google/MyMemory 列表？
7. **兼容范围**：是否要覆盖官方 9 个目标（最低 20.21.37、minSdk 26），还是允许本补丁声明更窄的 21.16+ / minSdk 28？
8. **维护**：谁负责跟进 YouTube 字幕内部接缝？是否接受“失败关闭 + 版本矩阵 + 定期 composition 审计”的维护方式？

### 1.3 Gate 0 的通过标准

维护者明确回复以下之一：

- “按 A 做，先提 PR1” → 继续；
- “按 B 做独立补丁” → 用同一套代码，改设置入口；
- “并入 VOT 文字模式” → 切方案 C，先复用 VOT 的 translator/provider/cache；
- “暂不接受这个产品” → 停止上游化，保留第三方源。

**没有这个回复，不要开始 31k 行搬迁。** 否则很容易把时间花在错误的产品位置上。

---

## 2. 目标架构

### 2.1 方案 A（推荐）：`Captions` 子补丁

```text
patches/youtube/layout/captions/
├── CaptionsPatch.kt                 (already exists; add settings group entry)
├── aicaption/
│   ├── AiCaptionTranslatorPatch.kt  root patch
│   ├── AiCaptionSettingsPatch.kt    PreferenceScreen declarations
│   ├── Fingerprints.kt              fingerprints only
│   ├── NativeCaptionHooksPatch.kt   activity/video/time/player/track hooks
│   └── CaptionRendererHooksPatch.kt SubtitleWindowView draw suppression
└── ...

extensions/youtube/.../patches/aicaption/
├── AiCaptionRuntime.java            composition root / state
├── model/                           language, render spec, page, cue
├── core/                            protocol, planner, source, page layout, validation
├── source/                          timedtext fetch, cache, source-track policy
├── display/                         overlay, scheduler, page timing
├── settings/                        Settings keys + custom preferences
└── ui/                              overlay view, style preview, language picker
```

补丁根的大致形态：

```kotlin
@Suppress("unused")
val aiCaptionTranslatorPatch = bytecodePatch(
    name = "AI caption translation",
    description = "Translates YouTube captions in real time using an OpenAI-compatible API.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        captionsPatch,
        playerOverlayButtonsHookPatch,
        videoInformationPatch,
        playerTypeHookPatch,
    )
    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        // 设置入口：挂到官方 Captions 设置页，不手写 XML
        settingsMenuCaptionGroup.add(
            PreferenceScreenPreference(
                key = "morphe_ai_caption_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = aiCaptionPreferences,
            ),
        )

        // hooks ...
    }
}
```

如果维护者选 B（独立顶级补丁），只改设置入口：

```kotlin
PreferenceScreen.PLAYER.addPreferences(
    PreferenceScreenPreference(
        key = "morphe_ai_caption_screen",
        sorting = PreferenceScreenPreference.Sorting.UNSORTED,
        preferences = aiCaptionPreferences,
    ),
)
```

其他代码不变。这样产品位置可以最后再定，不必重写。

### 2.2 运行链路

```text
YouTube native caption selection
        │
        ├─ native Captions / Preferred language 仍是唯一语言权威
        ▼
caption track change hook ──► AiCaptionRuntime
        │                         │
        │                         ├─ source fetch (Requester + official cookies)
        │                         ├─ block planner / protocol / page layout
        │                         ├─ provider request (OpenAI-compatible, user key)
        │                         └─ overlay draw (translated pages)
        ▼
SubtitleWindowView.draw ──► suppressed while AI owns the track
```

关键原则：

- **不再有 loopback**：原生 timed-text URL 保持原样，原生渲染器照常拿 cue；只在 `SubtitleWindowView.draw` 层抑制原文字形。
- **不再有 `networkSecurityConfig`**：不引入明文流量。
- **不再有 Cookie 抓取**：用官方 `Requester`/`CaptionCookies` 的链路。
- **不再有独立语言记忆**：沿用 N48 契约。

---

## 3. 仓库与文件映射

### 3.1 删除/不进入 PR 的内容

| 当前内容 | 处理 |
|---|---|
| `caption-contracts/`、`caption-shared/`、`translation-core/`、`infrastructure/`、`application-host/`、`presentation/`、`ui/` 作为独立模块 | 删除 Gradle 模块，代码并入 `extensions/youtube` 的子包 |
| `ui-preview/` | 删除；预览作为 extension 内的自定义 Preference |
| `extensions/extension/`、`extensions/extension.mpe` | 删除；用官方 `extensions/youtube` + `sharedExtensionPatch` |
| `settings.gradle.kts` 的自定义 include、`build.gradle.kts` 的 `verifyIntegrationSources` | 恢复官方文件，不加自定义 task |
| `build/n39/`、`PatchListGenerator`、`patches-bundle.json`、`patches-list.json` 自定义生成 | 不进入 PR；用官方发布流程 |
| `tools/verify_integration.py`、`docs/core-provenance.json`、`docs/core-upstream.patch` | 留在第三方仓库，不进 PR |
| `recovered/`、`validation/runs/`、`docs/N*`、`caption-diagnostics-*.txt` | 不进 PR；从 PR 分支删除 |
| `.releaserc`、`package.json`、`.github/workflows/release.yml` | 不进 PR；用官方 release |
| `CaptionResourceMapping.kt` | 删除；用官方 `app.morphe.patcher.resource.ResourceType` + `resourceLiteral` |
| `FreshYouTubeCookies` | 删除；用官方 cookies/Requester |
| `LoopbackCaptionServer` | 删除；用 `SubtitleWindowView.draw` 抑制 |
| `CaptionDiagnosticsWriter/Archive/Snapshot/QualityTrace` | 删除；用 `Logger`/`Enable debugging` |
| `TokenCostAudit/Report` | 先删；如果要保留，改成 ~100 行 usage summary |
| `ApiProfiles*` | 首版单配置；多配置放 PR4 |
| `CaptionSettingPreference/CaptionSettingCategory` | 删除；用官方 preference 基类 |
| `captionlocales/`、`source-keys.tsv`、`index.txt` | 迁移到官方 `addresources`，不在运行时注入 |

### 3.2 代码映射

| 当前包/目录 | 目标包/目录 | 说明 |
|---|---|---|
| `patches/.../deepseekcaptions/AiCaptionTranslatorPatch.kt` | `patches/.../layout/captions/aicaption/AiCaptionTranslatorPatch.kt` | 保持根补丁，缩到 ~100 行 |
| `CaptionSupportPatch.kt` | `.../aicaption/NativeCaptionHooksPatch.kt` | 合并初始化/hook 安装 |
| `CaptionSettingsPatch.kt` | `.../aicaption/AiCaptionSettingsPatch.kt` | 改成声明式，删除 XML/manifest 逻辑 |
| `NativeCaptionBridgePatch.kt` | `.../aicaption/Fingerprints.kt` + `NativeCaptionHooksPatch.kt` + `CaptionRendererHooksPatch.kt` | 655 行拆成 3 个文件，单文件 <250 行 |
| `CaptionQuickTogglePatch.kt` | `.../aicaption/AiCaptionFlyoutPatch.kt` 或删除 | 用官方 `addPlayerBottomButton`/Flyout API，不再 bridge 进 `FlyoutUtils` |
| `.../shared/CaptionResourceMapping.kt` | 删除 | 用官方 `ResourceType`/`resourceLiteral` |
| `caption-shared` 21 文件 | `extensions/youtube/.../aicaption/model/` | 语言、渲染规格、Unicode、cue/page 值对象 |
| `translation-core` 27 文件 | `.../aicaption/core/` | protocol/planner/source/page/validation/salvage |
| `infrastructure` 19 文件 | `.../aicaption/{source,settings,network}/` | 只留源加载、缓存、配置、密钥；删诊断/token/loopback/cookie |
| `application-host` 15 文件 | `.../aicaption/runtime/` | 原生桥、播放器证据、会话、生命周期 |
| `presentation` 4 文件 | `.../aicaption/display/` | 调度/显示窗口 |
| `ui` 39 文件 | `.../aicaption/{ui,settings}/` | overlay、按钮、设置/预览；删自定义 preference 基类 |
| `patches/.../shared/Constants.kt` | 删除，改用 `COMPATIBILITY_YOUTUBE` | 不再复制目标列表 |

### 3.3 代码行预算

| 子系统 | 当前 | 目标 | 主要动作 |
|---|---:|---:|---|
| `translation-core` | 10,275 | 6,500–7,500 | 复用官方 Requester；删重复网络/诊断 |
| `ui` | 7,991 | 1,800–2,200 | 用官方 preference 基类；保留 overlay/预览 |
| `infrastructure` | 4,378 | 800–1,200 | 删 diagnostics/token/loopback/cookie |
| `application-host` | 3,436 | 2,200–2,600 | 精简原生桥和播放器证据 |
| `caption-shared` | 2,390 | 1,200–1,600 | 去重、用 ICU/官方语言数据 |
| `patches` | 1,625 | 800–1,000 | 拆分、去设置 XML/自定义资源映射 |
| 生产代码合计 | 31,086 | 13,300–16,100 | 先到 ~15k，再争取 12k |
| 测试 | 8,785 | 2,500–3,000 | 保留高价值回归；不带上 Robolectric 全套 |

如果并入 VOT，`translation-core` 的协议/提供商/缓存可再少 ~3–4k，总目标 **8–11k**。

---

## 4. 关键改造的顺序与做法

### 4.1 第一步：删 loopback，不要先搬翻译

这是最大的架构风险，必须最早验证。当前代码已经有 `NativeCaptionBridge.suppressNativeDraw()` 和 `SubtitleWindowView.draw` hook；先证明它就是 loopback 的替代品。

改动：

1. `DeepSeekCaptionHook.rewriteUrl()` 的 loopback 分支全部删除，直接返回 `originalUrl`。
2. 删除 `LoopbackCaptionServer`、`CLOSED_TRANSLATION_SINK`、`CaptionSettingsPatch.allowLoopbackTraffic()`。
3. 保留 `hookNativeDraw`，把 `suppressNativeDraw()` 的判断收紧为“AI 拥有原生轨或 Overlay 可见”。
4. 如果首帧仍闪现原文，扩展 hook 到原生 cue 文本设置点（`SubtitleWindowView`/caption view 的 setText），仍然不要引入本地服务器。
5. 删除 `AndroidManifest.xml` 的 `networkSecurityConfig` 改动。

验收：

- `git grep 127.0.0.1`、`networkSecurityConfig` 为 0；
- CC off/on、自动/人工字幕、Shorts 循环、全屏、PiP、seek、换视频均无原文闪烁/双字幕；
- AI 关闭时原生字幕完全正常；
- 失败时无原文兜底（N48 契约）。

### 4.2 第二步：设置改成官方声明式

删除 `CaptionSettingPreference`/`CaptionSettingCategory`/`CaptionSettingsPatch` 的 XML 注入，改成：

- `settingsMenuCaptionGroup.add(PreferenceScreenPreference(...))`（方案 A）
- 或 `PreferenceScreen.PLAYER.addPreferences(...)`（方案 B）
- `Settings.java` 注册 `BooleanSetting`/`StringSetting`/`IntegerSetting`/`FloatSetting`
- 普通项：`SwitchPreference`、`ListPreference`、`TextPreference`、`NonInteractivePreference`
- 滑块：官方 `SeekBarPreference` + `SeekBarConfig.register(...)`
- 自定义项：`NonInteractivePreference(key, tag = "app.morphe.extension.youtube.patches.aicaption.settings.StylePreviewPreference")`，类继承官方 preference/`AbstractPreferenceFragment`

建议的设置键（首版）：

| Key | 类型 | 默认 | 官方控件 |
|---|---|---|---|
| `morphe_ai_caption_enabled` | BooleanSetting | false | SwitchPreference |
| `morphe_ai_caption_base_url` | StringSetting | "" | TextPreference |
| `morphe_ai_caption_api_key` | StringSetting | "" | TextPreference（首版按 VOT 先例存原文；如加密则排除导入导出） |
| `morphe_ai_caption_model` | StringSetting | "" | TextPreference |
| `morphe_ai_caption_prompt` | StringSetting | 资源默认值 | TextPreference |
| `morphe_ai_caption_languages` | StringSetting（CSV/JSON） | "" | 自定义多选 Preference |
| `morphe_ai_caption_flyout` | BooleanSetting | true | SwitchPreference |
| `morphe_ai_caption_text_size` | IntegerSetting | 13 | SeekBarPreference |
| `morphe_ai_caption_background_opacity` | IntegerSetting | 70 | SeekBarPreference |
| `morphe_ai_caption_position_*` | IntegerSetting | 0 | SeekBarPreference |
| `morphe_ai_caption_clear_cache` | — | — | NonInteractivePreference |

首版不要做多 profile、模型目录、自动发现；这些放 PR4。

> API Key 存储选择：官方 VOT 用普通 `StringSetting` 存 OpenRouter key，这是最简单的先例；Keystore 密文会带来“导入到另一台设备无法解密”的问题。如果坚持加密，必须把该键排除在设置导入导出之外，或把密文与设备绑定写清楚；不要既加密又让用户跨设备恢复。

验收：

- `res/xml/morphe_prefs*.xml` 不再被手写/插入；
- `Settings` 导入导出/搜索/主题能覆盖新键；
- `android.preference.Preference` 自定义基类为 0（官方基类除外）；
- 设置页在 14 个 locale 下正常。

### 4.3 第三步：本地化迁移

把 `captionlocales/values*/caption_addon_strings.xml` 转成官方 `addresources`：

| 当前 | 官方 |
|---|---|
| `values` | `values`（英文 base） |
| `values-ar` | `values-ar-rSA` |
| `values-de` | `values-de-rDE` |
| `values-es` | `values-es-rES` |
| `values-fr` | `values-fr-rFR` |
| `values-hi` | `values-hi-rIN` |
| `values-in` | `values-in-rID` |
| `values-ja` | `values-ja-rJP` |
| `values-ko` | `values-ko-rKR` |
| `values-pt` | `values-pt-rBR`（可另加 `pt-rPT`） |
| `values-ru` | `values-ru-rRU` |
| `values-vi` | `values-vi-rVN` |
| `values-zh-rCN` | `values-zh-rCN` |
| `values-zh-rTW` | `values-zh-rTW` |

做法：

1. 在 `.tmp/` 写一次性 Python 脚本，读 14 个 XML + `source-keys.tsv`，生成 `patches/src/main/resources/addresources/values*/youtube/strings.xml`。
2. key 统一前缀 `morphe_ai_caption_`；去掉中文原文 key。
3. 模型名/提供商名用 `translatable="false"`。
4. 删除 `captionlocales/`、`source-keys.tsv`、`index.txt`，以及运行时资源注入代码。
5. 用官方 `ResourceUtils.getString`/`StringRef`/`@string`，不要在 Java 里保留中文 fallback。

> 设置顺序风险：`settingsMenuCaptionGroup` 在 `CaptionsPatch.execute` 里被挂到 `PreferenceScreen.PLAYER`；如果实际 XML 生成发生在 `AiCaptionSettingsPatch.execute` 之前，嵌套屏幕可能不出现。PR1 先验证；不行就改为直接 `PreferenceScreen.PLAYER.addPreferences(...)`，不要回到手写 XML。

验收：

- 14 locale 全部能加载；切换系统/应用语言正确；
- 没有 `source-keys.tsv`、没有运行时 XML 注入；
- 新增字符串能被 Crowdin 扫描。

### 4.4 第四步：网络层换成官方 Requester

删除：

- `DeepSeekApiClient` 的 `HttpURLConnection` 生命周期；
- `FreshYouTubeCookies`；
- `ProviderRequestPolicy` 里重复的 header/timeout；
- 自定义 `openWithYouTubeCronet`。

改用：

- `app.morphe.extension.shared.requests.Requester.openConnection(...)`
- `Requester.parseString/parseErrorString/parseJSONObject`
- `Route`/`CompiledRoute` 组织 `/chat/completions`、`/models`
- 官方 `ConnectionProvider`（如果进程里有 Cronet，Requester 会走它）
- `CaptionCookiesPatch` 的 Cookie 机制；不要自己抓 `/sw.js`

> Cookie 不能想当然：官方 `CaptionCookiesPatch` hook 的是 `org.chromium.net.UrlRequest.Builder`，而 `Requester` 用的是 `HttpURLConnection`；两者不是同一条链路。PR2 必须先验证 AI 的 timedtext 源加载是否需要 Cookie：能走签名 URL 就不接 Cookie；必须接时，用 YouTube 的 `UrlRequest`/Cronet 或显式失败，不要恢复 `/sw.js` 抓取。

首版只支持一个通用 OpenAI 兼容 endpoint：

- `https://` only；拒绝 `http://`、userinfo、fragment；
- 不设 `api.deepseek.com` 默认值；
- 模型手动输入；
- 不做自动 provider 探测；
- 401/403/429/超时/空响应都有明确失败状态，不显示原文。

验收：

- 一个真实 endpoint 能翻译短视频；
- 401/429/断网/空 JSON 不崩溃、不显示原文；
- 不抓 Cookie、不读 YouTube 登录态；
- API Key 不出现在日志/诊断。

### 4.5 第五步：诊断和 Token 记账降级

诊断：

- 删除 `CaptionDiagnosticsWriter/Archive/Snapshot/QualityTrace`、`DeepSeekDiagnosticsPreference`。
- 加一个 `AiCaptionLog` facade，内部只调 `Logger.printDebug`/`Logger.printException`。
- 设置页用官方 `ExportLogToClipboardPreference` 或 `Enable debugging`，不再有私有诊断档案。
- 如果维护者要求“字幕专用报告”，再做 opt-in 的 200 条环形 buffer，不写盘、不含文本。

Token：

- 删除 `TokenCostAudit/Report`（889 行）。
- 只从 provider response 的 `usage` 里取 `prompt_tokens/completion_tokens`，可选显示一行“本次约 X tokens”；不按视频/会话做精确审计。
- 如果维护者不要，直接删。

验收：

- 播放路径没有文件 I/O、没有 `Keystore` 读取；
- `git grep CaptionDiagnostics` 为 0；
- 默认不产生任何用户数据文件。

### 4.6 第六步：核心算法移植

按依赖顺序移植，先纯逻辑、后 Android：

1. `model`：`TargetLanguage`、`CaptionLanguageProfile`、`CaptionRenderSpec`、`CaptionClause`、`Page`、`CueRange`
2. `core`：`RebuildProtocol`、`RebuildSource`、`RebuildPlanner`、`RebuildPageLayout`、`RebuildDisplayGroups`、`RebuildReview`、`RebuildSemantics`、`RebuildNumbers`、`RebuildSalvage`
3. `source`：`RawCaptionSource`、`SourceCaptionCache`、`SourceTrackPolicy`、`SourceFormatPolicy`
4. `runtime`：`AiCaptionRuntime`、`CaptionPlayerAuthority`、`CaptionPlayerTransitionGuard`、`CaptionLifecycleRestore`
5. `display`：`CaptionOverlay`、`CaptionScheduler`、`CaptionPageTiming`
6. `settings`/`ui`：官方 preference + 自定义 preview/picker

移植时：

- 用 `Logger`/`Utils`，不用 `android.util.Log`/`System.out`；
- 用 `Utils.getContext()`/`Utils.dpToPx` 等官方工具；
- 保留 fail-closed、引文对齐、阅读分页、N48 的“不显示原文”；
- 删除所有诊断 `mark(...)` 调用（或改 `Logger.printDebug`）；
- 删除 `TokenCostAudit.on...` 调用；
- 删除 `CaptionAddonSupport.aiInstalled()` 这类为独立 bundle 设计的桩，改成直接类引用。

### 4.7 第七步：播放器按钮/Flyout 用官方扩展点

当前 `CaptionQuickTogglePatch` 往官方 `FlyoutUtils` 里加 bridge，属于跨补丁内部耦合。改成：

- 官方 `addPlayerBottomButton("Lapp/morphe/extension/youtube/videoplayer/AiCaptionButton;")`；或
- 官方 `FlyoutUtils.addFlyoutButton(...)`/`PlayerFlyoutMenuComponentsFilter` 的公开入口；
- 不再反射/改写 `FlyoutUtils` 内部方法；
- 如果官方没有公开的 flyout 入口，先提一个小 PR 增加 hook，再依赖它。

验收：

- 不再出现 `addonCaptionButton` 之类的 bridge；
- 官方 `FlyoutUtils` 文件不需要为本补丁修改；
- 按钮在普通视频/Shorts/全屏的显示与点击正常。

---

## 5. PR 拆分

### PR1 — `feat(YouTube - AI captions): add settings and native caption takeover`

**目标**：证明补丁能进官方构建、设置能挂进 Captions、原生字幕能被接管，不涉及网络。

包含：

- `AiCaptionTranslatorPatch.kt`（~100 行）
- `AiCaptionSettingsPatch.kt`（~80 行）
- `Fingerprints.kt`（~150 行）
- `NativeCaptionHooksPatch.kt`（~200 行）
- `CaptionRendererHooksPatch.kt`（~100 行）
- `AiCaptionRuntime` + `NativeCaptionBridge` 精简版（~600 行）
- `CaptionOverlay` 精简版（~800 行）
- `Settings.java` 新增块（~30 行）
- `addresources` 基础 strings（14 locale，资源行）
- 删除 loopback / network config / 自定义 settings XML

不包含：翻译请求、API Key、模型、分页（先用占位符证明链路，占位符不显示原文）。

验收：

- `./gradlew :patches:buildAndroid` 通过；
- 21.20.405 + 21.16.256 应用成功，钩子绑定；
- Captions 设置页出现 AI 子页；
- AI 打开时原生字幕不绘制，Overlay 可见；关闭后原生正常；
- `127.0.0.1`、`networkSecurityConfig`、`captionlocales` 为 0；
- 设备录屏/截图附 PR。

估计：~2–2.5k 行，1–2 周。

### PR2 — `feat(YouTube - AI captions): translate captions with an OpenAI-compatible API`

**目标**：打通一个通用 endpoint 的翻译、缓存、失败状态。

包含：

- `core/RebuildProtocol`、`RebuildSource`、`RebuildPlanner`、`RebuildApi`、`RebuildCache`（~3–4k）
- `network/AiCaptionRequester` + `Route`（~300）
- `settings` 的 base URL/key/model/prompt/test API（~300）
- `Requester` 接入、删除 `FreshYouTubeCookies`/自定义 HTTP
- 401/403/429/超时/空 JSON 的失败状态
- 基础单元测试（~500）

不包含：复杂分页/人工 cue/全语种质量校验（PR3）。

验收：

- 真实 endpoint 翻译 1–2 分钟视频；
- 失败不显示原文、不崩溃；
- API Key 加密/脱敏；
- 无 Cookie 抓取；
- 设备测试 21.20.405 + 21.16.256。

估计：~4–5k 行，2–3 周。

### PR3 — `feat(YouTube - AI captions): paginate and validate translated captions`

**目标**：把项目真正的算法价值带进来。

包含：

- `RebuildPageLayout`、`CaptionLanguagePager`、`CaptionClauses`、`RebuildDisplayGroups`、`RebuildReview`、`RebuildSemantics`、`RebuildNumbers`、`RebuildSalvage`（~3–4k）
- 人工 cue 契约（`RebuildCueContract` 的精简版）
- 源轨选择/语言 profile/全语种校验
- 分页/引文/失败关闭回归（~1–1.5k）

验收：

- 长句/多语种/无标点/人工 cue/Shorts 的分页符合当前契约；
- 22/32 拒绝矩阵的回归（按上游可复现的子集）；
- 失败关闭、无原文、无重复页；
- 设备 soak 30 分钟，无崩溃/明显耗电。

估计：~3–5k 行；如果超过 4k，拆成 PR3a（分页）和 PR3b（人工 cue/全语种）。

### PR4（可选）— `feat(YouTube - AI captions): add profiles and model selection`

只在维护者明确需要时做：

- 多 profile（官方 `ListPreference` + 自定义 dialog，JSON 存 `StringSetting`）
- 模型目录（`/models` 拉取 + 手动输入）
- Flyout 开关（官方 `addPlayerBottomButton`/Flyout API）
- opt-in 字幕专用日志（如维护者要求）

验收：不引入私有 `SharedPreferences`；不引入第二套设置渲染；不增加默认诊断写盘。

估计：~1.5–2k 行，1 周。

---

## 6. 时间表

| 阶段 | 内容 | 时间 | Gate |
|---|---|---|---|
| 0 | 冻结基线、RFC、维护者确认 | 2–3 天 | 维护者同意 A/B/C |
| 1 | 干净分支、补丁骨架、官方设置、strings | 3–5 天 | PR1 可构建 |
| 2 | 删 loopback、原生 draw 抑制、设备验证 | 1–2 周 | 无原文闪烁/双字幕 |
| 3 | PR1 提交/review | 1 周 | PR1 合并或反馈 |
| 4 | 翻译/Requester/缓存/失败状态 | 2–3 周 | PR2 设备通过 |
| 5 | 分页/人工 cue/全语种 | 2–3 周 | PR3 回归通过 |
| 6 | 兼容矩阵/性能/隐私/最终 review | 1–2 周 | PR 模板勾选 |

总计 **8–12 周开发 + 2–4 周 review**；如果只有一个人、还要上班，按 3–4 个月排。

---

## 7. 验证矩阵

### 7.1 YouTube 版本

| 版本 | 优先级 | 说明 |
|---|---|---|
| 20.21.37 | 若支持 minSdk 26 | 官方最低目标；Android 8 |
| 21.16.256 | P0 | 当前稳定下限 |
| 21.20.405 | P0 | 推荐稳定 |
| 21.23.492 | P1 | 最后一个 Android 9 |
| 21.28.208 | P1 | 官方实验 |
| 21.38.130 / 21.39.522 | P2 | 实验，至少补丁应用 |
| 21.40.161 | P0 | 当前最高实验目标 |
| 20.51.39 | P2 | 官方旧稳定 |

PR 模板要求 min/max；如果只声明 21.16+，就在 `compatibleWith` 里明确，不要声称全范围。

### 7.2 场景

- 普通视频、全屏、Shorts（循环/重进）、PiP、后台返回
- CC off/on、自动字幕、人工字幕、上传字幕（双行）
- 原生 Auto-translate 选择、官方 Preferred 选择、无目标语言
- 视频切换、seek、暂停、播放结束、旋转
- 断网、401、403、429、超时、空 JSON、截断 JSON
- 长块（>30s）、单长句、无标点、RTL、14 locale
- API Key 错误/清除、模型不存在、base URL 非法
- 低端机/Android 9/13/15/16

### 7.3 证据

- `:patches:buildAndroid` 成功；
- 真实 patcher + 原始 APK composition，所有补丁应用、钩子绑定、DEX 审计；
- 设备录屏：AI 开/关、原文不闪、分页、失败状态；
- 性能：启动延迟、内存、请求数、30 分钟 soak；
- 隐私：默认无文件写盘、无 Cookie 抓取、日志脱敏。

---

## 8. 风险与回退

| 风险 | 触发 | 回退 |
|---|---|---|
| 维护者拒绝产品位置 | Gate 0 | 保留第三方源；或只上游通用字幕能力 |
| draw 抑制在某个版本失效 | PR1/2 设备测试 | hook cue setter/透明文本；**不要恢复 loopback** |
| 官方要求并入 VOT | RFC 回复 | 切方案 C，复用 VOT translator/cache，代码量目标 8–11k |
| 官方不接受多 profile/诊断 | PR4 review | 直接删 PR4，不影响 PR1–3 |
| 官方要求 minSdk 26 | PR1 review | 补 26/27 兼容或声明窄范围；不要假装支持 |
| PR 太大 review 不动 | PR2/3 | 继续拆：协议/请求/缓存/分页/人工 cue 各自独立 |
| 翻译质量/语言覆盖争议 | PR3 | 把质量校验降为 advisory；保留失败关闭；用真实矩阵证据 |

---

## 9. 首 7 天清单

**Day 1**
- 提交/打 tag 当前 N48；保存测试结果、MPP hash、演示录屏。
- Fork 官方 `morphe-patches`，`git checkout -b feat/youtube-ai-caption-translation origin/dev`。
- 跑一次 `./gradlew :patches:buildAndroid`，记录基线环境。

**Day 2**
- 写 1 页 RFC；列出 A/B/C 三个位置和推荐 A。
- 把本报告第 3 节的映射表贴进 issue，说明“当前 31k，目标 13–16k，分 4 个 PR”。
- 发给维护者/在 issue 里 @，等 Gate 0。

**Day 3–4**
- 在干净分支建 `aicaption` 包和空补丁；加 `Settings` 键、`addresources` 基础 strings。
- 把 `AiCaptionTranslatorPatch` 缩到 ~100 行，挂到 `settingsMenuCaptionGroup`。
- `:patches:buildAndroid` 通过。

**Day 5**
- 移植 `NativeCaptionBridge` 的 draw 抑制路径；删除 loopback/network config/closed sink。
- 在 21.20.405 上测试：AI 开时原生不画、Overlay 可见；AI 关时原生正常。

**Day 6–7**
- 设备 smoke：普通/全屏/Shorts/CC off/on/seek/换视频。
- 修首帧闪烁；如无法修，记录并设计 cue setter hook，不要恢复服务器。
- 开 PR1 draft，附录屏和“未完成：翻译/分页”的说明。

---

## 10. Definition of Done

- [ ] 官方 `settings.gradle.kts` 无新增模块；无自定义 build task。
- [ ] 一个补丁，官方命名/描述，`compatibleWith(COMPATIBILITY_YOUTUBE)` 或明确窄范围。
- [ ] `127.0.0.1`、`networkSecurityConfig`、`FreshYouTubeCookies`、`LoopbackCaptionServer` 全部为 0。
- [ ] 0 个自定义 `android.preference` 基类；所有设置键注册进 `Settings`。
- [ ] 字符串在 `addresources`；14 locale 可加载；无 TSV/运行时注入。
- [ ] 诊断 opt-in/官方 Logger；无默认文件写盘、无默认文本记录。
- [ ] 网络走官方 `Requester`；HTTPS-only；无厂商默认 endpoint；无 Cookie 抓取。
- [ ] PR1–PR3 合并；PR4 按需；每个 PR < ~4k 行。
- [ ] min/max YouTube + 至少 2 台设备验证；PR 模板勾选。
- [ ] 版权头/NOTICE 完整；无 `recovered/`、诊断 txt、发布文件。
- [ ] 生产代码 8–16k；MPP 体积明显下降；无原文兜底。

---

## 11. 参考

- [Morphe Patches — Contributing](https://github.com/MorpheApp/morphe-patches#contributing)
- [Morphe Patcher — Structure and conventions](https://github.com/MorpheApp/morphe-patcher/blob/main/docs/3_structure_and_conventions.md)
- [Morphe Patches — PR template](https://github.com/MorpheApp/morphe-patches/blob/main/.github/PULL_REQUEST_TEMPLATE.md)
- [官方 Captions](https://github.com/MorpheApp/morphe-patches/tree/main/patches/src/main/kotlin/app/morphe/patches/youtube/layout/captions)
- [官方 Voice over translation](https://github.com/MorpheApp/morphe-patches/tree/main/extensions/youtube/src/main/java/app/morphe/extension/youtube/patches/voiceovertranslation)
- [官方 Requester](https://github.com/MorpheApp/morphe-patches/blob/main/extensions/shared/library/src/main/java/app/morphe/extension/shared/requests/Requester.java)
- [官方 Settings](https://github.com/MorpheApp/morphe-patches/blob/main/extensions/youtube/src/main/java/app/morphe/extension/youtube/settings/Settings.java)
