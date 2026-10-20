# N47 — Morphe 下“字幕打开时实际轨道由什么决定”调查（2026-10-20）

> 状态：**只读调查**。本轮没有修改产品代码、没有重建 MPP、没有连接手机。
> 目标：在正式改语言记忆逻辑之前，确认字幕打开时那条轨道到底是谁选的、谁能在最后覆盖它。

## 0. 一句话结论

**不是 `Auto captions` 决定的，也不是 `CaptionTargetPolicy` 决定的。**

实际轨道是 `SubtitleManager.setSubtitleTrack(track, selectType)` 的**最后一个写入者**决定的。
在 Morphe 1.47.0 下，这个最后写入者至少可能来自四层：

1. YouTube 自己的 `getDefaultCaptionTrack()`（模型默认/持久轨）；
2. 本补丁的 `hookRememberedDefault`（把默认轨改成记忆目标）；
3. **Morphe 官方 `Preferred caption language`**（默认 `off`；一旦不是 `off`，会在默认轨调用点覆盖第 1/2 层，并在 `setSubtitleTrack` 入口再次覆盖）；
4. 用户菜单的 `PREFERRED_TRACK` 选择（当前视频内最高，但每个新视频会重新开始）。

**语言菜单的勾选只反映第 4 层最终应用的 native track；我们的 AI overlay、`CaptionTargetPolicy`、`rewriteUrl` 都不会改变菜单。**
所以“菜单 zh-Hans、字幕 ru”不是同一个决定的两种显示，而是两个层各自成功的结果。

---

## 1. 先排除两个常见误解

### 1.1 `Auto captions` 不决定语言

Morphe 官方 `AutoCaptionsPatch` 只改一个布尔判断：

```java
public static boolean disableAutoCaptions(boolean original) {
    AutoCaptionsStyle style = Settings.AUTO_CAPTIONS_STYLE.get();
    if (style == AutoCaptionsStyle.BOTH_ENABLED) return false;
    if (captionsButtonStatus.get()) return original;
    boolean wantCaptions = style == AutoCaptionsStyle.WITH_VOLUME_ONLY;
    return !wantCaptions;
}
```

（`Morphe 1.47.0` `AutoCaptionsPatch.java:33-49`）

它回答的是“字幕要不要自动打开”，不是“打开哪条轨道”。
`BOTH_ENABLED` / `BOTH_DISABLED` / `WITH_VOLUME_ONLY` / `WITHOUT_VOLUME_ONLY` 都不会设置语言。
真正设置语言的是 `SubtitleManager` 的轨道选择。

### 1.2 `CaptionTargetPolicy` 不决定菜单

`CaptionTargetPolicy` 发生在 timed-text 请求阶段，也就是轨道已经选定、YouTube 已经准备请求字幕之后。
它只能改写请求 URL 的 `tlang`，让 AI overlay 拿到另一个目标；它不能改变 `SubtitleManager` 当前选中的
native track，因此不能改变菜单勾选。

这也是为什么诊断里可以同时出现：

```text
NATIVE_TRACK_APPLIED ... track=zh-Hans/manual
TARGET_POLICY_APPLIED ... target=ru
```

前者是菜单侧，后者是请求/overlay 侧。

---

## 2. 实际轨道的完整决定链

```text
CC 打开 / 新视频开始
  │
  ├─ 1. Morphe Auto captions 样式：只决定“是否自动显示字幕”
  │
  ├─ 2. YouTube SubtitleManager.getDefaultCaptionTrack()
  │       - 读 CaptioningManager / 视频轨道列表 / YouTube 自己的持久选择
  │       - 返回一个初始 track（可能是源轨、可能是 auto-translate）
  │       - 指纹确认方法体内调用 CaptioningManager.isEnabled()；系统字幕开关至少是输入之一，
  │         系统字幕语言是否参与未从 smali 证明，不能排除
  │
  ├─ 3. 本补丁 hookRememberedDefault
  │       - 直接改 getDefaultCaptionTrack() 的方法体
  │       - 有记忆时返回记忆轨；无记忆时可能返回 sourceDefault 或原始默认
  │
  ├─ 4. Morphe Preferred caption language（1.47.0，设置 != off 时）
  │       - 在 getDefaultCaptionTrack() 调用点之后覆盖第 2/3 层的返回值
  │       - 优先级：provider 字幕 > 原生目标语言轨 > auto-translate > 原轨道
  │
  ├─ 5. setSubtitleTrack(track, selectType)
  │       - 真正的应用点；谁最后调用/最后被改写，谁就是实际轨道
  │       - Morphe Preferred caption language 还会在方法入口再覆盖一次
  │       - 本补丁的 selectNative() 也走这里，因此也可能被 Morphe 覆盖
  │
  ├─ 6. 用户菜单选择
  │       - selectType=PREFERRED_TRACK，且过了 500ms 宽限期
  │       - Morphe 记录 userSelectedTrack=true，本次视频内不再覆盖
  │
  └─ 7. NATIVE_TRACK_APPLIED（诊断观测点）
          - 这是最终应用后的轨道，也是菜单应显示的轨道
```

### 2.1 新视频的轨道在 `VIDEO_SWITCHED` 之前就已经选好

诊断里四个新视频都是这个顺序：

| 视频 | 背景/默认轨 applied | `VIDEO_SWITCHED` |
|---|---|---|
| `QH838aWyhAk` | `1792470011214` `zh/manual` | `1792470011266` |
| `DqAKQwagCDg` | `1792470022924` `zh-Hans/manual` | `1792470022976` |
| `AaxPLnvCy08` | `1792470042936` `zh-Hans/manual` | `1792470042994` |
| `mhaKh7WITSc` | `1792470058599` `zh/manual` | `1792470058633` |

这说明：**新视频的初始轨道是在后台 caption model 初始化/恢复时决定的，早于 `onVideoId` 和
`onCaptionsShown`。** 我们的 `onVideoId`/`onCaptionsShown` 只能在之后改 overlay 或尝试重选；
它们无法决定“第一帧菜单里是哪条轨道”。

能决定初始轨道的只有两个缝：

- 第 3 层：改 `getDefaultCaptionTrack()` 的返回值（我们的 `hookRememberedDefault`）；
- 第 4 层：改它的调用点（Morphe 的 `getPreferredCaptionTrack`）。

### 2.2 本补丁的 `hookRememberedDefault` 在哪一层

`NativeCaptionBridgePatch.kt` 在 `defaultTrack` 方法开头插入：

```text
invoke-static {p0}, NativeCaptionBridge->resolveRemembered(...)
...
if decision < 0 -> original default
if decision == 0 -> null
decision == 1 -> sourceDefault if resolveRemembered null
```

（`patches/.../NativeCaptionBridgePatch.kt:553-581`）

它修改的是 `getDefaultCaptionTrack()` 的**方法体**。Morphe 的 `getPreferredCaptionTrack` 则是在
**调用点**拿到这个方法的返回值后再替换。因此：

```text
我们的 hook 先执行 -> 返回 ru
Morphe 的调用点 hook 后执行 -> 如果 Preferred != off，可改成 zh-Hans
```

这就是“我们的 hook 明明返回 ru，原生 applied 却是 zh-Hans”的第一候选解释。

### 2.3 本补丁的 `selectNative()` 也会被 Morphe 拦截

`refreshNativeTrack()` 的重选走：

```java
selectNative(manager, null, value.origin, value.reason);
selectNative(manager, track, value.origin, value.reason);
```

（`NativeCaptionBridge.java:1053-1059`）

它把当前 selection 的 `origin` 原样传下去；新视频的 origin 通常是 `DEFAULT`。
Morphe 的 `onSetSubtitleTrack()` 在 `setSubtitleTrack` 入口执行：

- 只有 `selectType == PREFERRED_TRACK` 且过了 500ms 宽限期，才认为“用户显式选择”；
- 否则只要 `Preferred caption language != off`，就再次调用 `getPreferredCaptionTrack()` 覆盖。

（`PreferredCaptionLanguagePatch.java:171-219`）

所以我们的 `selectNative(..., DEFAULT, ...)` 在 Morphe 开启 Preferred 时，会被 Morphe 改回
它自己的语言。菜单当然不会变成 ru。

---

## 3. Morphe `Preferred caption language` 的具体行为

### 3.1 它是什么

Morphe 1.47.0 的 `Captions` 补丁包含：

```kotlin
dependsOn(
    autoCaptionsPatch,
    preferredCaptionLanguagePatch,
    captionCookiesPatch,
    transcriptPatch,
)
```

（`CaptionsPatch.kt:19-24`）

`PreferredCaptionLanguagePatch` 的说明是：

> Adds an option to automatically select captions in your preferred language
> (provider subtitles first, then auto-translated).

它默认值：

```java
public static final StringSetting PREFERRED_CAPTION_LANGUAGE =
        new StringSetting("morphe_preferred_caption_language", "off", true);
```

（`Settings.java:353`）

**默认是 `off`。** 这一点很重要：如果用户没有改过这个设置，它不应该参与覆盖。
但只要用户把它设成 `default`（跟随 App 语言）或具体语言（例如中文），它就会成为实际决定者。

### 3.2 它的优先级

`getPreferredCaptionTrack()`：

1. 如果原轨道是 disable/null，保留；
2. 如果 `prefLang == off`，保留原轨道；
3. 如果 `userSelectedTrack == true`，保留原轨道；
4. 目标语言 = 设置值，或 `default` 时用 App 语言；
5. **Priority 1**：直接轨（provider 字幕，`vss` 以 `.` 开头）中匹配目标语言的轨道；
6. **Priority 1b**：原生目标语言轨（视频音频本身是目标语言）；
7. **Priority 2**：只有在视频确实有 auto-translate 选项时，找 auto-translate 目标轨；
8. **Priority 3**：回退到原轨道。

（`PreferredCaptionLanguagePatch.java:82-169`）

注意：它自己就实现了“provider 字幕优先，其次 auto-translate”。如果用户把 Preferred 设为中文，
它会主动选择中文 provider 字幕或中文 auto-translate；这会让新视频菜单固定为中文。

### 3.3 它每个视频都会重置

```java
public static void newVideoStarted(...) {
    userInteractionAllowed.set(false);
    userSelectedTrack.set(false);
    recordProgrammaticSelection(null);
    videoStartTime = System.currentTimeMillis();
}
```

（`PreferredCaptionLanguagePatch.java:224-230`）

也就是说：

- 用户在 A 视频手动选 ru -> `userSelectedTrack=true`，A 视频内保留 ru；
- 换到 B 视频 -> `newVideoStarted` 把 `userSelectedTrack` 重置为 false；
- 如果 Preferred != off，B 视频又按 Preferred 选中文/目标语言；
- 我们的记忆 ru 即使还在，也会被 Morphe 在默认轨/`setSubtitleTrack` 覆盖。

这与诊断中“用户选 ru 后，新视频 applied 又回到 zh-Hans/zh”完全一致。
但**只有 Preferred != off 时这才是充分解释**；如果它是 off，则另找第 3/5 层的原因。

### 3.4 1.45.0 没有这个补丁

Morphe 1.45.0 的 `patches-list.json` 中 `Captions` 仍只有：

> Adds an option to disable captions from being automatically enabled or to set caption cookies.

`Preferred caption language` 是 1.47.0 的新功能：

> **YouTube - Captions:** Add an option to select preferred caption language
> ([#3566](https://github.com/MorpheApp/morphe-patches/pull/3566))

因此：

- 如果实际安装的是 Morphe 1.45.0：没有 Preferred 层，实际轨道只可能来自 YouTube 默认 +
  我们的 hook/重选；
- 如果实际安装的是 Morphe 1.47.0：存在 Preferred 层，默认 off，但用户设置可让它成为最高优先级的
  默认轨写入者。

诊断里的 `official=1.45.0` **不是实际安装版本**：
`CaptionDiagnostics.java:287` 是硬编码字符串，`NativeCaptionBridge.java:293` 也是硬编码
`build=n30;official=1.45.0`。项目状态和 `COMPATIBILITY.md` 写的是官方 1.47.0。
必须看 Morphe 管理器里的实际补丁版本，不能把诊断这个字段当证据。

---

## 4. 当前诊断能证明什么、不能证明什么

### 4.1 能证明

1. 新视频的初始轨道在 `VIDEO_SWITCHED` 之前就 applied 了（见 §2.1 四个时间对）；
2. 这些初始轨道是 `origin=DEFAULT`，不是用户菜单选择；
3. 用户显式选择 ru 时是 `origin=PREFERRED_TRACK;reason=1`（`1792470007131`）；
4. 用户选 ru 后，后续新视频的原生 applied 全部回到 `zh-Hans/manual` 或 `zh/manual`，
   没有一次 ru；
5. 本补丁的 `TARGET_POLICY_APPLIED`/AI overlay 可以显示 ru，但 `NATIVE_TRACK_APPLIED` 仍是
   zh-Hans/zh；
6. `LANGUAGE_REMEMBERED_CLONED=0`，`TARGET_CONFIRMED_BY_MENU_REQUEST=0`。

### 4.2 不能证明

**不能只凭诊断区分“Morphe Preferred 覆盖”与“YouTube 默认/我们的 hook 没跑”。**
原因是两者最终都可能表现为：

```text
NATIVE_TRACK_APPLIED | translated=true;origin=DEFAULT;track=zh-Hans/manual
```

`origin` 只说明 YouTube 的调用来源类型，不说明是哪个补丁层写的；Morphe 的覆盖也发生在默认轨
调用点，最终仍可能以 DEFAULT 落下来。

### 4.3 决定性判据

只需要知道一个外部输入：

> **Morphe 设置 → Captions → Preferred caption language 当前是什么值？**

- **不是 `off`**（例如 `default`、`中文`、`zh-Hans`）：
  Morphe 就是实际轨道的最高优先写入者；我们的记忆在菜单层永远抢不过它（除非改这个设置或
  改 Morphe 的覆盖逻辑）。这也能直接解释“为什么每个新视频都是 zh-Hans”。
- **是 `off`**：
  Morphe 不参与；实际决定者是 YouTube 默认/持久轨，我们的 `hookRememberedDefault` 没有胜出，
  需要继续查是 hook 路径未命中、执行时机太早/太晚、`resolveRemembered` 返回 null，还是 YouTube
  在之后又 apply 了默认轨。

如果实际安装的是 1.45.0，则没有这个设置，直接落在第二种。

---

## 5. 用于定位的对照实验（不改代码）

### 5.1 先看设置

`YouTube → 设置 → Morphe → Captions（字幕） → Preferred caption language（首选字幕语言）`

记录三项：

1. 实际官方补丁版本（1.45.0 还是 1.47.0+）；
2. `Preferred caption language` 的值；
3. `Auto captions` 的值（只用于解释是否自动显示，不解释语言）。

### 5.2 四格对照

| Preferred | 本补丁记忆 | 预期新视频 native 轨道 | 说明 |
|---|---|---|---|
| off | off | YouTube 默认/持久轨 | 基线 |
| off | on（ru） | 应由我们的 hook 变成 ru | 如果仍是 zh-Hans，说明我们的 hook 路径/时机有问题 |
| on（中文） | off | 中文 provider 或中文 auto-translate | 证明 Morphe 是决定者 |
| on（中文） | on（ru） | 仍是中文；ru 只可能在 overlay | 证明 Morphe 覆盖了我们的菜单写入 |

### 5.3 最小验证

1. 记下当前 `Preferred` 值；
2. 改成 `off`，强杀 YouTube，打开一个之前会显示 zh-Hans 的视频；
3. 看 `NATIVE_TRACK_APPLIED` 是否变成源轨/其他语言；
4. 若变了 -> Morphe Preferred 是决定者；
5. 若仍是 zh-Hans -> 继续查 YouTube 默认/我们的 hook。

---

## 6. 对后续方案的直接影响

### 6.1 如果 Morphe Preferred 不是 off

那么“菜单是唯一权威”这个目标，在当前安装组合下**不可能只靠我们的代码实现**，因为 Morphe 会在
每个新视频重置并覆盖菜单。可选方案只有：

1. **让用户把 Morphe Preferred 设为 off**，把语言选择交回原生菜单 + 本补丁；
2. **与 Morphe 集成**：把它的 Preferred 值当作唯一目标，本补丁不再记忆另一套语言；
3. **不选 Morphe 的 Captions 补丁**（会失去 Auto captions 样式等）；
4. 我们的补丁在 Morphe 之后再次强制 `selectNative`（不推荐：这是继续打补丁，而且会与用户设置打架，
   仍可能被 Morphe 的 `setSubtitleTrack` 入口覆盖）。

如果用户明确设置了 Preferred，它其实是用户的另一个显式偏好。正确的产品决策应是
“只保留一个语言设置入口”，而不是让两个设置互相覆盖。

### 6.2 如果 Morphe Preferred 是 off

那么实际决定链退化为：

```text
YouTube getDefaultCaptionTrack()
  -> 我们的 hookRememberedDefault
  -> setSubtitleTrack
  -> YouTube 可能再次 apply 默认
```

这时要查的是我们的 hook 是否真的在 `defaultTrack` 路径上、执行时记忆/轨道列表是否就绪、
`resolveRemembered` 是否返回 null，以及 YouTube 是否在 hook 之后又用持久轨覆盖。
这仍然是“谁最后写 setSubtitleTrack”的问题，只是参与者少了 Morphe。

### 6.3 无论哪种情况，都不应再靠 overlay 改菜单

`CaptionTargetPolicy` 和 AI overlay 只能改字幕文本，不能改菜单。要让菜单和字幕一致，必须：

- 在 `setSubtitleTrack` 这一层让最终 track 等于要显示的语言；或者
- 不显示菜单无法表示的语言（fail-closed）。

在 Morphe Preferred 开启时，第一条需要修改/绕过 Morphe 的覆盖；第二条则意味着我们的记忆在
每个新视频都会被 Morphe 的首选语言取代。两者都需要先解决“谁是唯一语言设置”的产品问题。

---

## 7. 代码证据索引

| 主题 | 位置 |
|---|---|
| Morphe `Captions` 包含 Preferred | `.tmp/morphe-src/.../CaptionsPatch.kt:19-24` |
| Morphe Preferred 默认 `off` | `.tmp/morphe-1.47/Settings.java:353` |
| Morphe 默认轨调用点覆盖 | `.tmp/morphe-src/.../PreferredCaptionLanguagePatch.kt:146-172` |
| Morphe `setSubtitleTrack` 入口覆盖 | 同上 `:174-181` |
| Morphe 优先级 | `.tmp/morphe-src/.../PreferredCaptionLanguagePatch.java:82-169` |
| Morphe 每视频重置 | 同上 `:224-241` |
| Morphe `Auto captions` 只改布尔 | `.tmp/morphe-src/.../AutoCaptionsPatch.java:33-49`；`.kt:55-67` |
| 本补丁默认轨 hook | `patches/.../NativeCaptionBridgePatch.kt:553-581` |
| 本补丁重选入口 | `NativeCaptionBridge.java:1043-1059` |
| `NATIVE_TRACK_APPLIED` 观测点 | `NativeCaptionBridge.java:549-576` |
| 诊断 `official=1.45.0` 是硬编码 | `CaptionDiagnostics.java:287`；`NativeCaptionBridge.java:293` |

外部版本依据：

- Morphe 1.45.0 patch list（无 Preferred）：<https://raw.githubusercontent.com/MorpheApp/morphe-patches/v1.45.0/patches-list.json>
- Morphe 1.47.0 CHANGELOG（新增 Preferred）：<https://github.com/MorpheApp/morphe-patches/blob/v1.47.0/CHANGELOG.md>

---

## 8. 结论

1. **字幕是否打开**：Morphe `Auto captions` 决定；它不决定语言。
2. **字幕轨道**：`SubtitleManager.setSubtitleTrack` 的最后写入者决定；初始轨道在
   `getDefaultCaptionTrack` 阶段、且发生在 `VIDEO_SWITCHED` 之前。
3. **Morphe 1.47** 多了一个 `Preferred caption language`，默认 `off`；非 `off` 时它在默认轨调用点
   和 `setSubtitleTrack` 入口都能覆盖我们的记忆，且每个新视频重置。它是最可能的、能解释
   “每个新视频都 zh-Hans”的第三权威。
4. 当前诊断无法区分“Morphe Preferred”与“YouTube 默认/我们的 hook 失败”；需要先确认该设置值
   和实际官方补丁版本。
5. 在正式修改前，必须先决定：**语言设置只保留一个入口**。如果保留 Morphe Preferred，就不能再让
   本补丁的记忆成为菜单权威；如果保留本补丁记忆，就应让用户关闭 Morphe Preferred，或明确与其集成。
