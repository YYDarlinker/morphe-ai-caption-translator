# N48 — 删除语言记忆补丁与相关逻辑（2026-10-20）

> 状态：**已执行**。本轮按用户要求删除本项目的语言记忆补丁及其覆盖逻辑，不再与 Morphe 官方
> `Captions` / `Preferred caption language` 竞争。语言完全交给 YouTube 原生 caption model 与官方补丁。

## 0. 决策

用户明确要求：

> 不要和官方功能起冲突，完全删除这个补丁及相关逻辑，完全尊重官方设计。
> 不要任何“本视频没有目标语言”之类的提示。

因此最终契约改为：

- **语言菜单 / YouTube 原生 caption model 是唯一语言权威**；
- 本补丁不再持久化语言，不再跨视频恢复语言，不再改写 `tlang` 来制造目标；
- AI 只在 YouTube 已经选中一条 Auto-translate 译轨时，翻译该轨；
- 源字幕选择就是源字幕，不启动翻译；
- 不新增任何提示；目标不可用时不显示替代目标，静默跟随原生选择。

## 1. 删除内容

| 文件/逻辑 | 处理 |
|---|---|
| `patches/.../RememberCaptionSelectionPatch.kt` | 删除 |
| `patches/.../CaptionSupportPatch.kt` 的 `rememberSelection` | 删除 |
| `patches/.../NativeCaptionBridgePatch.kt` 的 `hookRememberedDefault` / `memoryInstalled` 发布 | 删除 |
| `application-host/.../RememberedCaptionSelection.java` | 删除 |
| `application-host/.../CaptionVideoHandoffV2.java` | 删除 |
| `caption-shared/.../CaptionTargetPolicy.java` | 删除 |
| `translation-core/.../CaptionTargetPolicyTest.java` | 删除 |
| `NativeCaptionBridge` 的记忆字段、默认轨恢复、`resolveRemembered`、`restoreDecision`、`sourceDefault`、`selectNative` 重选、记忆分支 | 删除 |
| `DeepSeekCaptionHook` 的 `TARGET_INFERRED_FOR_AI`、`TARGET_POLICY_APPLIED`、菜单窗口确认、源请求加 `tlang` | 删除 |
| `CaptionButtonController` / `CaptionQuickToggle` / `DeepSeekConfig` 的记忆回退与默认目标改写 | 删除 |
| 记忆专项测试（`CaptionMemoryPersistenceTest`、`CaptionHookMemoryTest`、`CaptionShownMemoryTest`、`N40LanguageMemoryTest`、`ModularCaptionTest`、`ShortsEngineSwitchTest`） | 删除/替换为新契约测试 |
| `patches-list.json` 的 `Remember caption selection` | 删除（只保留 `AI caption translator`） |
| `docs/core-provenance.json` 的 `resolveRemembered` / `restoreDecision` / `sourceDefault` / `selectNative` ABI | 删除 |

## 2. 新行为

### 2.1 请求路径

`DeepSeekCaptionHook.rewriteUrl()` 现在：

1. AI 关闭 → 原样返回 YouTube URL；
2. 没有 `tlang` 的源请求 → **原样返回，不添加目标**；
3. 有 `tlang` 的前台译请求 → 认为这是 YouTube 原生选择，记录当前会话 `CaptionChoice`，
   按 `SourceTrackPolicy` 选最佳源轨后交给 AI；
4. CC Off / 显式 Off → 仍然关闭，不复活。

不再存在“记忆目标”这一层。

### 2.2 原生选择

`NativeCaptionBridge.followNativeSelection()`：

- 只看 `onNativeSelection` 给出的原生轨道；
- 源轨 → `CaptionChoice` 记录 source 模式，overlay 显示原文；
- 译轨（包括自动默认/官方 Preferred 选出的译轨）→ 记录该语言，前台且 AI 开启时启动 AI；
- 后台预取只记录 selection，不启动；
- 无持久化、无跨视频目标、无克隆目标。

`onCaptionsShown()` / `refreshNativeTrack()` 只读取当前视频的原生 selection；没有 selection 时不做
任何目标推断。`CaptionChoice` 是当前原生 selection 的进程内投影，不再由 App 单独记忆。

### 2.3 UI

- `CaptionQuickToggle` 不再恢复记忆语言；
- `CaptionButtonController` 短按/CC On 不再把记忆语言写回；
- `choose_translation` 提示不再触发；
- 没有新增任何提示。

## 3. 验证

| 项目 | 结果 |
|---|---|
| `:translation-core:testDebugUnitTest` | **238 tests**, 0 failures / 0 errors / 0 skipped |
| `:extensions:extension:testDebugUnitTest` | **264 tests**, 0 failures / 0 errors / 0 skipped |
| 新增契约测试 | `NativeOnlyCaptionTest`（3 tests）：源请求不被加 `tlang`；自动原生译轨成为当前会话目标；原生源轨不启动翻译 |
| 本地化 | 243 keys × 14 locales 通过 |
| `tools/verify_integration.py` | `errors=[]`、`prompt_and_protocol_literals_unchanged=true`、core class 77、14 locale、embedded MPE 与 standalone 相同 |
| MPP | `build/n39/gradle/patches/libs/patches-1.5.4.mpp`，**592,741 B**，SHA-256 `BB906F3EA60EABED517EB7FC7C8F2422547BD6838C2F015A22D96F31522A558D` |
| MPP 内容 | 不再包含 `RememberedCaptionSelection` / `CaptionTargetPolicy` / `Remember caption selection` 字符串 |

## 4. 边界

- 未连接真机；没有安装/写入手机，也没有 after 诊断。
- 官方 Morphe `Preferred caption language` 仍然是独立设置；它选择什么语言，本补丁就跟随什么语言。
- 若 YouTube/官方补丁没有为当前视频提供目标语言，字幕就停留在原生源字幕；这是刻意的 fail-closed，
  不显示任何替代语言，也不提示。
- 旧诊断/旧报告中的 `TARGET_POLICY_APPLIED`、`ENGINE_REMEMBERED_ACTIVATED`、`NATIVE_REMEMBERED_ACTIVATED`
  等事件在新构建中不再产生；保留旧文档仅作历史证据。
