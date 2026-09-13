## v2.3.0-dev.8

此版本为 Morphe 开发测试版，需要 真实 Android 设备验证。

- 将显示分组前置到首次上屏前：根据 source-side immutable unit 只读判断分句归属，整组翻译 READY 后一次性冻结 DisplayPlan。
- 禁止后续 unit READY 回写已显示计划；本版不恢复 CONTINUATION_GROUP 或 rebuildDisplayContinuityLocked 式回写机制。
- 增加显示分组等待上限 800ms；超时或成员 permanent failure 时抑制不完整组，并记录 CONTEXTUAL_DISPLAY_SUPPRESSED。
- 保留 strict 显示路径现有两行容量和语义边界；整组超过 hard capacity 且无安全切点时不强行整句溢出上屏。
- 增加默认关闭的“显示文本调试”开关；开启后 DISPLAY_SELECTED 可附 source、canonical 和 group=x-y，供人工抽查。

验收要点：

① 开启“显示文本调试”后抽查 20 个含逗号/句号句子：同一分句必须在同一 group=x-y 内一次性呈现；
② 无 CONTINUATION_GROUP 事件；无整句先显后被自身子切片替换；
③ 分组等待 ≤800ms，超时/成员判负必有 CONTEXTUAL_DISPLAY_SUPPRESSED，且每组至多一条；
④ 统计 display_group_member_permanent_failure 出现次数；
⑤ 冷启动新视频：记录四段 CONTEXTUAL_PREPROCESS_STAGE 耗时，以及 CONTEXTUAL_UNIT_TIMELINE_READY 到首个有效 DISPLAY_SELECTED 的总耗时；
⑥ 本版不含独立成义闸与风控 fail-fast，偶发碎片与风控期缺失属预期。

不声明所有播放器、供应商和 生命周期问题已彻底解决。
## [2.3.0-dev.7](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.3.0-dev.6...v2.3.0-dev.7) (2026-08-21)

### 🐛 Bug Fixes

* validate Morphe-compatible release metadata ([353b614](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/353b614cf69ce020024e2d48285b39af93912e4e))

## [2.3.0-dev.6](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.3.0-dev.5...v2.3.0-dev.6) (2026-08-21)

### Device-test prerelease

- Roll back the dev.5 continuation grouping so a displayed unit range cannot shrink into its own child slice.
- Correct strict display boundary selection and channel accounting: CJK fallback is disabled and token boundaries are final hard-cap fallback only.
- Add four-stage local preprocessing timing diagnostics for `countAppendEvents`, `SourceAtomTimeline.build`, `TranslationUnitTimeline.build`, and cache restore.

Acceptance checks: no `CONTEXTUAL_DISPLAY_CONTINUATION_GROUP`; zero strict-path `cjk_fallback`; `token_boundary` below 5% of `DISPLAY_SELECTED`; first valid display within 1500 ms of `CONTEXTUAL_UNIT_TIMELINE_READY`; and no whole-caption-to-child-slice replacement within one unit time range.

## [2.3.0-dev.5](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.3.0-dev.4...v2.3.0-dev.5) (2026-08-20)

### Device-test prerelease

- Improve English preposition, article, auxiliary, and long-caption display boundaries.
- Improve Simplified/Traditional Chinese display width, relative structures, and numeric-quantity protection.
- Plan dependent translations across adjacent ready units to prevent long-sentence duplication.
- Suppress duplicate bridge units and persist that decision through cache restore.
- Strengthen target-language, proper-name, numeric, and native-expression translation guidance.

## [2.3.0-dev.4](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.3.0-dev.3...v2.3.0-dev.4) (2026-08-20)

### Device-test prerelease

- Fix HTTP 400 batch failures incorrectly making all requested caption units permanent failures and exhausting the subtitle inventory.
- Isolate provider batch rejection into bounded per-unit recovery without reintroducing unbounded retries.
- Improve numeric-quantity, predicate-complement, relative-clause, and Simplified/Traditional Chinese semantic segmentation.
- Improve subtitle MISS diagnostics with readable unit state, retry count, and failure category.

## [2.3.0-dev.3](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.3.0-dev.2...v2.3.0-dev.3) (2026-08-20)

### Device-test prerelease

- Reject clearly inadequate or non-speech translation output without replacing valid canonical captions.
- Improve current-unit rescue, adjacent-unit bridge handling, and cache-quality validation.
- Preserve safer numeric expressions and reporting phrases during source/display planning.
- Add diagnostics for JSON3 append observations, translation-quality rejection, and display misses.

## [2.3.0-dev.2](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.3.0-dev.1...v2.3.0-dev.2) (2026-08-19)

### Device-test prerelease

- Stabilize per-unit partial commit, retry, delayed repair, and future refill after isolated failures.
- Make playback inventory rate-aware and avoid unnecessary body-sent background cancellation.
- Harden pause/resume, playback-rate transition, startup seek, video switch, and shared timeline ownership.
- Improve deterministic Chinese display segmentation, dependent-tail handling, compound-phrase integrity, and continuous timing.
- Safely pass through native timed-text when video ownership cannot yet be verified.

## [2.3.0-dev.1](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.2.0-dev.18...v2.3.0-dev.1) (2026-08-18)

### Experimental device-test prerelease

- Add the Contextual Unit Batch experimental translation core: local immutable translation units, fixed-ID contextual batch translation, and locally determined source ranges.
- Add a unit-level realtime/background scheduler with an independent translation cache, local-only display planning, and expanded token/cost diagnostics.
- Keep Semantic Ledger V2 as the fallback/control path; Contextual Unit Core is enabled by default.

## [2.2.0-dev.18](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.2.0-dev.17...v2.2.0-dev.18) (2026-08-17)

### 🔧 Improvements

* payload slimming and unconditional page merge

dev17 实测（307 秒连续播放，¥0.0568/min）推翻了 dev17 自己的前提：禁用页级合并后缓存命中率是 0.0%（不是预期的回到 10.9%），说明 DashScope 上缓存从未存在，与页级合并无关。而块级回退使每核心 atom 输入从 88.5 tok（dev16 页级）恶化到 138.8 tok。

修复：
- 恢复页级合并（无条件），块级 fallback 仍保留，坏页不会卡死库存
- payload 瘦身：`timing`（默认 estimated）和 `gap_after_ms`（默认 0）仅在非默认值时发送，源轨原生 timing 0% 时省掉每个 atom 约 21 bytes 的冗余字段
- system prompt 声明缺省字段语义，确保信息无损、模型不误读

## [2.2.0-dev.17](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.2.0-dev.16...v2.2.0-dev.17) (2026-08-17)

### 🐛 Bug Fixes

* disable page merge on DashScope and suspend alt-framing ([3658348](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/36583483ccddc3f6f011624fd871600a96778c7e))

## [2.2.0-dev.17](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.2.0-dev.16...v2.2.0-dev.17) (2026-08-17)

### 🐛 Bug Fixes

* disable page merge on DashScope and suspend alt-framing second looks

dev16 实机测试显示缓存命中率从 10.9% 崩溃至 1.4%，成本上涨 73%。根因分析：
- 页级合并在 DashScope 端点未触发前缀缓存复用，98.6% 输入按全价计费
- 边界二次观察成本 ¥0.0220/192 atoms ≈ 109 tok/atom，高于普通块的 88.5 tok/atom

修复措施：
- Provider 检测：仅在 api.deepseek.com 官方端点启用页级合并，DashScope 回退到逐块发送
- 暂时禁用 alt-framing 二次观察，避免低效成本
- 保留 dev15 的显示切片本地优先（已验证有效）

预期效果：DashScope 用户成本应恢复到 dev14 水平或更低

## [2.2.0-dev.16](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.2.0-dev.15...v2.2.0-dev.16) (2026-08-17)

### 🔧 Improvements

* dev16 merge-page lifecycle hardening — body-sent preemption stop, unconditional page progress, per-page alt reframing, horizon clamp, audit metrics ([318aebc](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/318aebc04beeda1cb0e1ca6815d707aa58fd6279))

## [2.2.0-dev.15](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.2.0-dev.14...v2.2.0-dev.15) (2026-08-16)

### 🔧 Improvements

* merge background page requests and prefer local display slicing ([4ecb29c](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/4ecb29c30c33afcf1f9e55adc958e8d2deccd99d))

## [2.2.0-dev.14](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.2.0-dev.13...v2.2.0-dev.14) (2026-08-15)

### 🐛 Bug Fixes

* govern stalled priority rescue demand ([257e3da](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/257e3daedc9dd241cf06dd38a7e31da317adc327))

## [2.2.0-dev.13](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.2.0-dev.12...v2.2.0-dev.13) (2026-08-15)

### 🔧 Improvements

* pause background prefetch while playback is idle ([22b3599](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/22b35995194b959f3d1bc838fd845fed0897d8df))

## [2.2.0-dev.12](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.2.0-dev.11...v2.2.0-dev.12) (2026-08-15)

### 🐛 Bug Fixes

* restore proactive priority continuity from dev10 ([5f28458](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/5f28458d4421f1b0e63567a20d469068f4e085fe))

## [2.2.0-dev.11](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.2.0-dev.10...v2.2.0-dev.11) (2026-08-15)

### 🐛 Bug Fixes

* suppress stalled priority rescue storms ([5324fbb](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/5324fbb39dd406d4bd03fb8579f3d8ece50b472a))

## [2.2.0-dev.10](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.2.0-dev.9...v2.2.0-dev.10) (2026-08-15)

### 🔧 Improvements

* reuse cache-aware semantic source pages ([d5a9459](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/d5a945962f77e322d745c9873d7dc57744e0e005))

## [2.2.0-dev.9](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.2.0-dev.8...v2.2.0-dev.9) (2026-08-15)

### 🔧 Improvements

* replace rolling background with fixed semantic blocks ([2303531](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/230353199f3fdf6b573a80d60b43cbe51dc57ac6))

## [2.2.0-dev.8](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.2.0-dev.7...v2.2.0-dev.8) (2026-08-15)

### 🐛 Bug Fixes

* defer repeated zero-yield background windows ([4729246](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/4729246af561ac8a637d4fded8787b07a1870277))

## [2.2.0-dev.7](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.2.0-dev.6...v2.2.0-dev.7) (2026-08-15)

### 🐛 Bug Fixes

* preserve CJK words in display slicing ([426a88d](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/426a88df791c5fd24d2ea8ae646f6d725106980a))

## [2.2.0-dev.6](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.2.0-dev.5...v2.2.0-dev.6) (2026-08-15)

### 🔧 Improvements

* refine first-caption latency and display cadence ([e9e576d](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/e9e576decf5080287f35e253a8a9e945cd3515ba))

## [2.2.0-dev.5](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.2.0-dev.4...v2.2.0-dev.5) (2026-08-15)

### 🐛 Bug Fixes

* restore dev3 semantic stability and harden fallback ([4b8bfa3](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/4b8bfa3dd3a0f927c56748f5effba0af8e8bf023))

## [2.2.0-dev.4](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.2.0-dev.3...v2.2.0-dev.4) (2026-08-15)

### 🐛 Bug Fixes

* package dev4 cost-quality pass for device testing ([6b82f8e](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/6b82f8e6392b245eabd80ea723b7e80a08e6db6f))
* preserve exact quality-safe dev4 implementation ([b72e160](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/b72e16031034c4cf970968e641b91bbccbe7860d))
* publish completed dev4 cost pass for device testing ([99af0c4](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/99af0c47a2dfd6c543760f10b4fadc812b260380))

### 🔧 Improvements

* make display slicing single-shot ([1d3431d](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/1d3431dd73fd97dc1264e463476bf4bd1497f282))
* restore quality while reducing cache-miss cost ([a23c18f](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/a23c18fd8d3b55a37f37a8060ba42ef4558aed9d))

## [2.2.0-dev.7](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.2.0-dev.6...v2.2.0-dev.7) (2026-08-15)

### 🐛 Bug Fixes

* publish completed dev4 cost pass for device testing ([99af0c4](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/99af0c47a2dfd6c543760f10b4fadc812b260380))

## [2.2.0-dev.6](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.2.0-dev.5...v2.2.0-dev.6) (2026-08-15)

### 🐛 Bug Fixes

* preserve exact quality-safe dev4 implementation ([b72e160](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/b72e16031034c4cf970968e641b91bbccbe7860d))

## [2.2.0-dev.5](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.2.0-dev.4...v2.2.0-dev.5) (2026-08-15)

### 🔧 Improvements

* restore quality while reducing cache-miss cost ([a23c18f](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/a23c18fd8d3b55a37f37a8060ba42ef4558aed9d))

## [2.2.0-dev.4](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.2.0-dev.3...v2.2.0-dev.4) (2026-08-15)

### 🔧 Improvements

* make display slicing single-shot ([1d3431d](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/1d3431dd73fd97dc1264e463476bf4bd1497f282))

## [2.2.0-dev.3](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.2.0-dev.2...v2.2.0-dev.3) (2026-08-14)

### 🔧 Improvements

* add quality-safe background hysteresis ([2457b65](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/2457b6573d1f9c09048ab9be91c82d770365f66f))

## [2.2.0-dev.2](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.2.0-dev.1...v2.2.0-dev.2) (2026-08-14)

### 🔧 Improvements

* coalesce background horizon refills ([4e88cff](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/4e88cff5501192c84e589ec2794fc7cde30de244))

## [2.2.0-dev.1](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.1.0...v2.2.0-dev.1) (2026-08-14)

### ✨ New Features

* add exact token cost audit ([317a9b0](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/317a9b0be0d75a69be5dc05d0f661fcfdcf73594))

## [2.1.0](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.0...v2.1.0) (2026-08-14)

### 🐛 Bug Fixes

* backproject rolling caption timing ([13005a3](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/13005a3cea2778ea65a82057090f150428480285))
* bypass AI restore for native caption choices ([99dbf8b](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/99dbf8bdd6026d522284612d13f347f20b25cc37))
* calibrate broadcast captions against ASR timing ([77bfa37](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/77bfa37776d3df367362d786f071a14a779c6377))
* cancel stale display-slice requests ([7ac8f45](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/7ac8f4505a0eb758c7f2f6c5b68e65aa9f52e4a4))
* carry only caption intent across videos ([4b8c96f](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/4b8c96f72425e76cdf990a1f8197d090e92ff379))
* connect composer to read-only window [skip ci] ([e526dcf](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/e526dcf17932b93ba2cca802fb38479077704c1f))
* defer soft-ended target fragments without failing the window ([143434e](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/143434e9852c9a1edce6ad1729705089130e4a32))
* force ASR timing calibration for English source tracks ([15ac0ec](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/15ac0ec995cbf16f8a2affd09cc6dfe5e2fffb00))
* force English source timing calibration ([d66b075](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/d66b075c25c1aeecd5a3bf7ce8bdd69022e00c01))
* heal semantic page seams instead of leaving subtitle holes ([0aa0f7e](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/0aa0f7e1253c1937c7e2a2161fac01aba698b934))
* isolate captions from player transitions ([6c8d4ab](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/6c8d4ab8a8e4c658c09e644727e1c9a77a3609bb))
* isolate dev7 caption cache after semantic salvage changes ([f9d629e](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/f9d629edfe66d3cbfb43a71ad040e790ce585622))
* isolate semantic transport recovery ([7fa3aef](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/7fa3aef16813fec3424a469e187aa24c4cc78722))
* keep AI captions authoritative for foreign source tracks ([0180695](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/018069572d59fcd8cf33ec3a4c14a37ceaed2c0b))
* keep display splits semantically complete ([0712064](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/0712064d2befcf8c53218cf3021f563872b10685))
* keep native menu monitor race-safe ([70fa55f](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/70fa55f18b0d84ce97bb2a33bb064d9da7651e99))
* keep source atom timing monotonic across overlapping cues ([c76fda5](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/c76fda57c53416def414cc996622ddb07262bce1))
* keep source requests on AI sink while AI is visible ([db941d8](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/db941d850879de6fda785c1b1edd4adfb255fbd2))
* keep YouTube native renderer on invisible AI sink ([77cba67](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/77cba67c5151427c16dad8e5f3b3d6c2311e4f3a))
* make caption overlay transition event driven ([e6b75fe](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/e6b75fe42ef3982ce968b0f780acb17221dbbc6f))
* make native caption authority video local ([137a74f](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/137a74f19c9156a60c1eb29a54c4f514f972627a))
* make native pass-through a single decision ([9fd366f](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/9fd366f5b8cbea24f8cdea9e613c265ec2840fb4))
* make semantic captions timing-aware ([f4b0d77](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/f4b0d77de3cd1ff3d8aadcb7e457907a8957af9a))
* make transport recovery session-safe ([36ed952](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/36ed9521db991981193b8bee862bc9e58a06ca89))
* mirror source cue structure into native sink ([8d9dc03](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/8d9dc0305e37c0916835932f154d0cb812daaf8a))
* never commit unfinished translated tails [skip ci] ([8f6441c](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/8f6441c4b64fecb2a309e2a5500b9bcd9b36e5ec))
* normalize rolling caption timelines ([9d1a81d](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/9d1a81dfad8c2ebe4923d1c1031f24c29f884f0d))
* observe native caption off immediately ([ebefe95](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/ebefe9516c2dd8473fa9ca174bb24e5ad84b6844))
* page translated captions by complete sentences ([8e2e1fc](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/8e2e1fcf5151ae997ea263248d1b541e0cc3b6e4))
* preserve AI intent across video handoff ([e3da0b1](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/e3da0b1d3104619a40533c51590fdaf5368eebe3))
* preserve native caption menu ownership ([345b81a](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/345b81a1badd70af35065e76d657ee6e9fb6d0e0))
* preserve roll-up speech intervals ([17ddcf2](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/17ddcf26e704fc35a94499dd2391e0404979fefa))
* prioritize semantic restarts and dangling short cues [skip ci] ([3ce0a13](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/3ce0a13430d8bcaae4dbb2afb1d38c3abf6cb460))
* quarantine overlay for whole miniplayer transition ([c69b2e9](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/c69b2e97bad8692432f64e3000d9b31c29f984cf))
* refine over-merged semantic captions ([c793471](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/c7934717522e8b78c96f0c578f8f802288af0f34))
* remove artificial semantic commit frontier ([6884253](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/68842534c2d39edfff88109a82c2bfc2db2dfe52))
* remove duplicate native caption decision ([4e5b335](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/4e5b3356c788d58fff0af2fb29398f112dbf269c))
* remove heuristic ASR timing gate ([a9d47a3](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/a9d47a3d442209df39d312e25bc39b887e8070c0))
* replace native captions with structural invisible mirror ([01bc05a](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/01bc05a8f83c693c5976fdb5ad3c73d295c5e0af))
* report one-shot default handoff ([f3257d3](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/f3257d30c6c3c76defb96194d0fce01240b1f777))
* respect current-video caption menu authority ([3479be5](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/3479be5e5547c84bcb3d3ccf8c0639ac85a98906))
* respect explicit native caption track requests ([9cf7f69](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/9cf7f69d55a48198e5e9e02c642f0244fb243b12))
* restore AI overlay after repeated miniplayer expansion events ([ef25f37](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/ef25f37a0eec41e327795b8c44e57bc4cc977281))
* restore default AI captions on video switch ([dcef64c](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/dcef64c56e5130a3d6a9414c28e41b704d209d41))
* restore native caption menu off ([e26f1ab](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/e26f1ab28a409ee058809d8da87cb4a9273f1d18))
* restore stable semantic quality baseline ([aba8a0c](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/aba8a0c8793c33bdb941ca33d5c72773fd0152e9))
* retain miniplayer overlay restore across repeated player events ([cdcd298](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/cdcd298766b77c3f02169e2acd75fc0b9f368927))
* route automatic foreign captions through AI ([51bd95b](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/51bd95b9ce9171d542d8682e89b44f68f7d2e284))
* salvage safe semantic prefixes instead of dropping windows ([0f109ce](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/0f109cedcd2090cf6e8af2892310756def35746e))
* scope auto-translate targets to one video ([d07cb2d](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/d07cb2d15c88cb17c59fd1e5d80a41bccca11809))
* scope explicit native captions to current video ([1dd4ab2](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/1dd4ab26a03231d34ed9c79039cfcecf1024f23f))
* separate transport retries from semantic growth ([721a6f8](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/721a6f8ebe35523173a8dd8c19fa726178699c6f))
* stop AI handoff after native track selection ([8f02b18](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/8f02b18596b3132ebd8fe9aee180aad87554359d))
* stop carrying manual targets across videos ([685902d](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/685902d194da34f86b876ae5ab4a9068e40d17eb))
* suppress native subtitles beneath AI captions ([5d81926](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/5d81926d21f5c332e5af7cd12480d95c325520b9))
* sync AI captions with native CC menu Off ([25008a1](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/25008a11b3d8642f0e0c35c9b4c59f9c075d3bf5))
* synchronize semantic retry recovery ([21e26d7](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/21e26d7e74ab8e629ad0624f2892b09e85b0d7e3))

### ✨ New Features

* add canonical translation display slicer ([1fa5c11](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/1fa5c11c52b96ab4e85a1744776c9383e61ebc0f))
* add centered semantic unit planner ([56b6e0c](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/56b6e0cfeae6a6d579085fca4f7a11e08e2285e6))
* add reversible semantic window composer ([bf5e389](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/bf5e389395006d2fde4f0924bd999b18c343ed97))
* add rhythm-aware semantic ledger runtime ([02d1bf5](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/02d1bf5ab6b85446f3f61afdeb8da86a13e93fc3))
* add semantic ledger caption runtime ([986f92b](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/986f92b7a745b6d3af5f9315cfd416d1ef228485))
* add semantic sentence boundary refiner [skip ci] ([f081fcd](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/f081fcde0fe281b720cc4e9cceeecc0a528fe1de))
* add semantic window composer [skip ci] ([b984d33](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/b984d33dd0a503dd5dafdee329dbf531ddbfde48))
* expose read-only translated window [skip ci] ([e624b86](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/e624b866474411b47c0a0de7bd7001c20017629e))
* let AI mark cross-cue semantic joins [skip ci] ([16fc8b1](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/16fc8b1a892a5220de1198ab42508f5dc98426bf))
* make semantic paging source-first with commit horizon ([706b38c](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/706b38c2d8e394d9b1e4fcfbdbed4ba6e3dbda6e))
* preserve lexical source atoms for semantic boundaries ([f6017a5](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/f6017a55ae5f8978d546386ae6f21f3b739df9aa))
* rebuild semantic caption boundaries ([2abb912](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/2abb912309d44564feefef6853b34412d98aa211))
* replace cue splitting with AI page-span planning ([fe1ebc6](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/fe1ebc6eb6320738495e730724503ff1931aec60))
* route captions through rhythm-aware ledger ([3ca0e19](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/3ca0e19f351fab5f44bdb9cf4d3af6cf52cb284f))
* route captions through semantic ledger runtime ([fb7fc69](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/fb7fc6950e775b9b106f69b8b17e3a49120bdf77))
* schedule subtitles on lexical source atoms ([596b16b](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/596b16b04cee62f7803ba871d557630941002aae))

## [2.1.0-dev.33](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.1.0-dev.32...v2.1.0-dev.33) (2026-08-14)

### 🐛 Bug Fixes

* sync AI captions with native CC menu Off ([25008a1](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/25008a11b3d8642f0e0c35c9b4c59f9c075d3bf5))

## [2.1.0-dev.32](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.1.0-dev.31...v2.1.0-dev.32) (2026-08-14)

### 🐛 Bug Fixes

* suppress native subtitles beneath AI captions ([5d81926](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/5d81926d21f5c332e5af7cd12480d95c325520b9))

## [2.1.0-dev.31](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.1.0-dev.30...v2.1.0-dev.31) (2026-08-14)

### 🐛 Bug Fixes

* mirror source cue structure into native sink ([8d9dc03](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/8d9dc0305e37c0916835932f154d0cb812daaf8a))
* replace native captions with structural invisible mirror ([01bc05a](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/01bc05a8f83c693c5976fdb5ad3c73d295c5e0af))

## [2.1.0-dev.30](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.1.0-dev.29...v2.1.0-dev.30) (2026-08-14)

### 🐛 Bug Fixes

* keep source requests on AI sink while AI is visible ([db941d8](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/db941d850879de6fda785c1b1edd4adfb255fbd2))
* keep YouTube native renderer on invisible AI sink ([77cba67](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/77cba67c5151427c16dad8e5f3b3d6c2311e4f3a))

## [2.1.0-dev.29](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.1.0-dev.28...v2.1.0-dev.29) (2026-08-14)

### 🐛 Bug Fixes

* carry only caption intent across videos ([4b8c96f](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/4b8c96f72425e76cdf990a1f8197d090e92ff379))
* keep native menu monitor race-safe ([70fa55f](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/70fa55f18b0d84ce97bb2a33bb064d9da7651e99))
* make native caption authority video local ([137a74f](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/137a74f19c9156a60c1eb29a54c4f514f972627a))
* make native pass-through a single decision ([9fd366f](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/9fd366f5b8cbea24f8cdea9e613c265ec2840fb4))
* remove duplicate native caption decision ([4e5b335](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/4e5b3356c788d58fff0af2fb29398f112dbf269c))

## [2.1.0-dev.28](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.1.0-dev.27...v2.1.0-dev.28) (2026-08-14)

### 🐛 Bug Fixes

* report one-shot default handoff ([f3257d3](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/f3257d30c6c3c76defb96194d0fce01240b1f777))
* restore default AI captions on video switch ([dcef64c](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/dcef64c56e5130a3d6a9414c28e41b704d209d41))

## [2.1.0-dev.27](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.1.0-dev.26...v2.1.0-dev.27) (2026-08-14)

### 🐛 Bug Fixes

* respect current-video caption menu authority ([3479be5](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/3479be5e5547c84bcb3d3ccf8c0639ac85a98906))
* scope auto-translate targets to one video ([d07cb2d](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/d07cb2d15c88cb17c59fd1e5d80a41bccca11809))
* stop carrying manual targets across videos ([685902d](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/685902d194da34f86b876ae5ab4a9068e40d17eb))

## [2.1.0-dev.26](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.1.0-dev.25...v2.1.0-dev.26) (2026-08-14)

### 🐛 Bug Fixes

* keep AI captions authoritative for foreign source tracks ([0180695](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/018069572d59fcd8cf33ec3a4c14a37ceaed2c0b))
* preserve AI intent across video handoff ([e3da0b1](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/e3da0b1d3104619a40533c51590fdaf5368eebe3))
* route automatic foreign captions through AI ([51bd95b](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/51bd95b9ce9171d542d8682e89b44f68f7d2e284))
* scope explicit native captions to current video ([1dd4ab2](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/1dd4ab26a03231d34ed9c79039cfcecf1024f23f))

## [2.1.0-dev.25](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.1.0-dev.24...v2.1.0-dev.25) (2026-08-14)

### 🐛 Bug Fixes

* restore native caption menu off ([e26f1ab](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/e26f1ab28a409ee058809d8da87cb4a9273f1d18))

## [2.1.0-dev.24](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.1.0-dev.23...v2.1.0-dev.24) (2026-08-14)

### 🐛 Bug Fixes

* restore AI overlay after repeated miniplayer expansion events ([ef25f37](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/ef25f37a0eec41e327795b8c44e57bc4cc977281))
* retain miniplayer overlay restore across repeated player events ([cdcd298](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/cdcd298766b77c3f02169e2acd75fc0b9f368927))

## [2.1.0-dev.23](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.1.0-dev.22...v2.1.0-dev.23) (2026-08-14)

### 🐛 Bug Fixes

* force ASR timing calibration for English source tracks ([15ac0ec](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/15ac0ec995cbf16f8a2affd09cc6dfe5e2fffb00))
* force English source timing calibration ([d66b075](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/d66b075c25c1aeecd5a3bf7ce8bdd69022e00c01))
* remove heuristic ASR timing gate ([a9d47a3](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/a9d47a3d442209df39d312e25bc39b887e8070c0))

## [2.1.0-dev.22](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.1.0-dev.21...v2.1.0-dev.22) (2026-08-14)

### 🐛 Bug Fixes

* calibrate broadcast captions against ASR timing ([77bfa37](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/77bfa37776d3df367362d786f071a14a779c6377))

## [2.1.0-dev.21](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.1.0-dev.20...v2.1.0-dev.21) (2026-08-14)

### 🐛 Bug Fixes

* preserve roll-up speech intervals ([17ddcf2](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/17ddcf26e704fc35a94499dd2391e0404979fefa))

## [2.1.0-dev.20](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.1.0-dev.19...v2.1.0-dev.20) (2026-08-14)

### 🐛 Bug Fixes

* observe native caption off immediately ([ebefe95](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/ebefe9516c2dd8473fa9ca174bb24e5ad84b6844))

## [2.1.0-dev.19](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.1.0-dev.18...v2.1.0-dev.19) (2026-08-14)

### 🐛 Bug Fixes

* backproject rolling caption timing ([13005a3](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/13005a3cea2778ea65a82057090f150428480285))
* make caption overlay transition event driven ([e6b75fe](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/e6b75fe42ef3982ce968b0f780acb17221dbbc6f))
* quarantine overlay for whole miniplayer transition ([c69b2e9](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/c69b2e97bad8692432f64e3000d9b31c29f984cf))

## [2.1.0-dev.18](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.1.0-dev.17...v2.1.0-dev.18) (2026-08-14)

### 🐛 Bug Fixes

* bypass AI restore for native caption choices ([99dbf8b](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/99dbf8bdd6026d522284612d13f347f20b25cc37))
* preserve native caption menu ownership ([345b81a](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/345b81a1badd70af35065e76d657ee6e9fb6d0e0))
* respect explicit native caption track requests ([9cf7f69](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/9cf7f69d55a48198e5e9e02c642f0244fb243b12))
* stop AI handoff after native track selection ([8f02b18](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/8f02b18596b3132ebd8fe9aee180aad87554359d))

## [2.1.0-dev.17](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.1.0-dev.16...v2.1.0-dev.17) (2026-08-14)

### 🐛 Bug Fixes

* normalize rolling caption timelines ([9d1a81d](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/9d1a81dfad8c2ebe4923d1c1031f24c29f884f0d))

## [2.1.0-dev.16](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.1.0-dev.15...v2.1.0-dev.16) (2026-08-14)

### 🐛 Bug Fixes

* cancel stale display-slice requests ([7ac8f45](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/7ac8f4505a0eb758c7f2f6c5b68e65aa9f52e4a4))
* make transport recovery session-safe ([36ed952](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/36ed9521db991981193b8bee862bc9e58a06ca89))
* separate transport retries from semantic growth ([721a6f8](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/721a6f8ebe35523173a8dd8c19fa726178699c6f))
* synchronize semantic retry recovery ([21e26d7](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/21e26d7e74ab8e629ad0624f2892b09e85b0d7e3))

## [2.1.0-dev.15](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.1.0-dev.14...v2.1.0-dev.15) (2026-08-14)

### 🐛 Bug Fixes

* isolate semantic transport recovery ([7fa3aef](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/7fa3aef16813fec3424a469e187aa24c4cc78722))

## [2.1.0-dev.14](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.1.0-dev.13...v2.1.0-dev.14) (2026-08-14)

### 🐛 Bug Fixes

* isolate captions from player transitions ([6c8d4ab](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/6c8d4ab8a8e4c658c09e644727e1c9a77a3609bb))

## [2.1.0-dev.13](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.1.0-dev.12...v2.1.0-dev.13) (2026-08-14)

### ✨ New Features

* add rhythm-aware semantic ledger runtime ([02d1bf5](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/02d1bf5ab6b85446f3f61afdeb8da86a13e93fc3))
* route captions through rhythm-aware ledger ([3ca0e19](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/3ca0e19f351fab5f44bdb9cf4d3af6cf52cb284f))

## [2.1.0-dev.12](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.1.0-dev.11...v2.1.0-dev.12) (2026-08-14)

### ✨ New Features

* add canonical translation display slicer ([1fa5c11](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/1fa5c11c52b96ab4e85a1744776c9383e61ebc0f))

## [2.1.0-dev.11](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.1.0-dev.10...v2.1.0-dev.11) (2026-08-14)

### ✨ New Features

* add semantic ledger caption runtime ([986f92b](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/986f92b7a745b6d3af5f9315cfd416d1ef228485))
* route captions through semantic ledger runtime ([fb7fc69](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/fb7fc6950e775b9b106f69b8b17e3a49120bdf77))

## [2.1.0-dev.10](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.1.0-dev.9...v2.1.0-dev.10) (2026-08-14)

### ✨ New Features

* add centered semantic unit planner ([56b6e0c](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/56b6e0cfeae6a6d579085fca4f7a11e08e2285e6))

## [2.1.0-dev.9](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.1.0-dev.8...v2.1.0-dev.9) (2026-08-14)

### 🐛 Bug Fixes

* restore stable semantic quality baseline ([aba8a0c](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/aba8a0c8793c33bdb941ca33d5c72773fd0152e9))

## [2.1.0-dev.7](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.1.0-dev.6...v2.1.0-dev.7) (2026-08-14)

### 🐛 Bug Fixes

* defer soft-ended target fragments without failing the window ([143434e](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/143434e9852c9a1edce6ad1729705089130e4a32))
* heal semantic page seams instead of leaving subtitle holes ([0aa0f7e](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/0aa0f7e1253c1937c7e2a2161fac01aba698b934))
* isolate dev7 caption cache after semantic salvage changes ([f9d629e](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/f9d629edfe66d3cbfb43a71ad040e790ce585622))
* salvage safe semantic prefixes instead of dropping windows ([0f109ce](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/0f109cedcd2090cf6e8af2892310756def35746e))

## [2.1.0-dev.6](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.1.0-dev.5...v2.1.0-dev.6) (2026-08-14)

### 🐛 Bug Fixes

* keep source atom timing monotonic across overlapping cues ([c76fda5](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/c76fda57c53416def414cc996622ddb07262bce1))

### ✨ New Features

* make semantic paging source-first with commit horizon ([706b38c](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/706b38c2d8e394d9b1e4fcfbdbed4ba6e3dbda6e))
* preserve lexical source atoms for semantic boundaries ([f6017a5](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/f6017a55ae5f8978d546386ae6f21f3b739df9aa))
* schedule subtitles on lexical source atoms ([596b16b](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/596b16b04cee62f7803ba871d557630941002aae))

## [2.1.0-dev.5](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.1.0-dev.4...v2.1.0-dev.5) (2026-08-14)

### ✨ New Features

* replace cue splitting with AI page-span planning ([fe1ebc6](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/fe1ebc6eb6320738495e730724503ff1931aec60))

## [2.1.0-dev.4](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.1.0-dev.3...v2.1.0-dev.4) (2026-08-14)

### 🐛 Bug Fixes

* never commit unfinished translated tails [skip ci] ([8f6441c](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/8f6441c4b64fecb2a309e2a5500b9bcd9b36e5ec))

### ✨ New Features

* let AI mark cross-cue semantic joins [skip ci] ([16fc8b1](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/16fc8b1a892a5220de1198ab42508f5dc98426bf))
* rebuild semantic caption boundaries ([2abb912](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/2abb912309d44564feefef6853b34412d98aa211))

## [2.1.0-dev.3](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.1.0-dev.2...v2.1.0-dev.3) (2026-08-14)

### 🐛 Bug Fixes

* connect composer to read-only window [skip ci] ([e526dcf](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/e526dcf17932b93ba2cca802fb38479077704c1f))
* prioritize semantic restarts and dangling short cues [skip ci] ([3ce0a13](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/3ce0a13430d8bcaae4dbb2afb1d38c3abf6cb460))

### ✨ New Features

* add reversible semantic window composer ([bf5e389](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/bf5e389395006d2fde4f0924bd999b18c343ed97))
* add semantic window composer [skip ci] ([b984d33](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/b984d33dd0a503dd5dafdee329dbf531ddbfde48))
* expose read-only translated window [skip ci] ([e624b86](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/e624b866474411b47c0a0de7bd7001c20017629e))

## [2.1.0-dev.2](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.1.0-dev.1...v2.1.0-dev.2) (2026-08-14)

### 🐛 Bug Fixes

* page translated captions by complete sentences ([8e2e1fc](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/8e2e1fcf5151ae997ea263248d1b541e0cc3b6e4))

## [2.1.0-dev.1](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.1-dev.2...v2.1.0-dev.1) (2026-08-14)

### 🐛 Bug Fixes

* refine over-merged semantic captions ([c793471](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/c7934717522e8b78c96f0c578f8f802288af0f34))

### ✨ New Features

* add semantic sentence boundary refiner [skip ci] ([f081fcd](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/f081fcde0fe281b720cc4e9cceeecc0a528fe1de))

## [2.0.1-dev.2](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.1-dev.1...v2.0.1-dev.2) (2026-08-13)

### 🐛 Bug Fixes

* keep display splits semantically complete ([0712064](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/0712064d2befcf8c53218cf3021f563872b10685))

## [2.0.1-dev.1](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.0...v2.0.1-dev.1) (2026-08-13)

### 🐛 Bug Fixes

* make semantic captions timing-aware ([f4b0d77](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/f4b0d77de3cd1ff3d8aadcb7e457907a8957af9a))

## [2.0.0](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v1.0.0...v2.0.0) (2026-08-13)

### ⚠ BREAKING CHANGES

* removes the launcher settings activity and the previous all-caption proxy implementation. Configuration now lives inside Morphe settings and only Chinese auto-translate requests are intercepted.

### 🐛 Bug Fixes

* add animation diagnostic baseline ([a012dd7](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/a012dd76420bb19d9add6445fd8531988c4bbb1e))
* add runtime split diagnostic mode ([ffaf5f7](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/ffaf5f708ec1068a2a2b6eed87eaae06fc1f3f65))
* anchor captions to stable player geometry ([4776ba9](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/4776ba9adcb57d980fe979478a058b0c9106bd64))
* avoid source caption 429s with Cronet reuse and source cache ([bb1a505](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/bb1a50585f93dd8e8898b4e6b9e820c23fee0c11))
* capture YouTube Cronet engine for source captions ([fd769d8](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/fd769d8f14ebc241ce30a111b92ccbf1766dab22))
* compile XML caption parser on Android stubs ([c6d6456](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/c6d645693e8f19586fb3cff4993c2d57cf283473))
* final captions ([8dce74d](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/8dce74d864171bda9d611bb38b787e7bb2703760))
* force visible AI for animation isolation ([1dfeaf9](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/1dfeaf93810975dfb2082582b265ace043075552))
* harden localhost caption bridge and add diagnostics ([1394eae](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/1394eaee45e3b9db34557c08824ca8bb99f474cf))
* isolate animation scanners from caption runtime ([57d8ddb](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/57d8ddbfb10331efe66187ba21d0cd1982e57d24))
* isolate caption button scanner animation impact ([11b9210](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/11b9210245717bf02d8851c71ace137248c37b2d))
* isolate captions from player animations ([cbe5bbc](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/cbe5bbce52e5c1733ffce4316da2c679b4268e3b))
* isolate language menu scanner animation impact ([e8484ba](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/e8484ba953aa025f94efbf3798f936e07ea000f6))
* keep Chinese captions visible during AI translation ([21cb917](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/21cb917637c6ec04fc57e4f5472e4f248e1c4261))
* make subtitle batch translation tolerant and self-recovering ([b3c78d5](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/b3c78d557419ff1de0f98d3d59da044f2977b828))
* pass YouTube Cronet engine into caption hook ([6210dfd](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/6210dfdf0546c2f8fa41fd637893e3c135a19172))
* preserve AI captions across videos ([2b236f1](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/2b236f13eba4c4d346cff903127f4191d7a39aba))
* repair cross-video AI caption handoff ([c730297](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/c73029779df491bb0740900b6a938a149ae885ed))
* restore AI captions after resume ([27d85ce](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/27d85ce2fac476aaab3134fc26587fab7516e156))
* restore caption button AI toggle ([efe300e](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/efe300e75a400215180f501be64482a42aae1825))
* restore compatible release markers ([410762d](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/410762d2f094b7cc74713ec75817c78b746a9b5e))
* restore language menu build ([6275776](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/62757768095ce6aa29bb96b1def2e61675e340ec))
* reveal captions only after positioning ([f245197](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/f245197bc37593257960c437f46a83ad3bb5c816))
* semantic caption display ([484fdb9](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/484fdb947689247a3dea94b30100f731de53ae68))
* stabilize AI captions and player state ([029b47d](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/029b47d1d1fa5c4ba72e26fe7d2a5e533a748e17))
* stabilize caption geometry and sizing ([a9729d3](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/a9729d327b4b88a8614199788c5249cd6fccf2dd))
* stabilize caption scheduling and player transitions ([e821745](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/e821745e4aa0e699e0324e9bbca8609e7bce7409))
* suppress music-only caption labels [skip ci] ([b58051a](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/b58051ab922a7130b6f9653f3e43c81c4881f375))
* suppress music-only caption labels [skip ci] ([a98c5a9](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/a98c5a9cd0e488bbbe419b0885616d80b26872f4))
* suppress non-speech music labels [skip ci] ([00cfa4c](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/00cfa4c3c355bb0480dda17fed043ca7ec246065))
* tune caption timing and suppress music labels [skip ci] ([b75e5ae](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/b75e5ae9af66ee4a8a6623b36cbf3839f4fcfc9f))
* tune caption timing and suppress music labels [skip ci] ([381bba9](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/381bba9e27a198150d8c539dbb67a53de1ac4d3d))
* use positional subtitle arrays and faster parallel batches ([5b4d119](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/5b4d119aa84ba237c3fbd719b82bfcceaaf46591))

### ✨ New Features

* add complete-sentence captions and native settings ([d40cf42](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/d40cf42f2e36dd3a18b3c37c4562b2853fa5edbc))
* add multilingual caption sessions and model discovery ([789dc86](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/789dc866cfa96a1687c7ff7da28a3e0ccd4e36aa))
* add play-head driven AI captions ([1454e1c](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/1454e1c02aa7a7e00c1ddb974b315ec3f0a760a5))
* add realtime micro-batches and draggable captions ([f150821](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/f150821a19c352f905a3c99cfaf1a21e83d16a26))
* align AI captions with native playback ([283c3f4](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/283c3f4a310509e6d30e1736a07e6311e8b4561b))
* cache successful source caption tracks ([2ea7ba3](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/2ea7ba3df5c0262b7393a7c7552b2fe61900eaba))
* polish caption menu latency and layout ([c350ed6](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/c350ed66cc4e4612a2dd687c85f65682e83cf797))
* prefer semantic sentence caption segments ([f9d3f8d](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/f9d3f8d0ce69bfffd78e025a83a7448b0ba57173))
* refine caption display and defaults ([6c462c7](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/6c462c74314da24294b4f7bc7b91a77a376b2433))
* refresh Timed Text cookies on demand ([c0f5fee](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/c0f5fee0a4c22a7539bd160f68beaeb282e700a8))
* rewrite DeepSeek caption translator from scratch ([9a75386](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/9a75386bb5bcf08870484498697593c9de2a8397))
* route Chinese auto-translate through DeepSeek API ([cfdfd8c](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/cfdfd8cd63f3f3a03c10616b05fbb12d9891f4f6))

### 🔧 Improvements

* shorten first-caption translation latency ([51b27f2](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/51b27f20cb11c93db2764e6b11e44780b3affbb5))

## [2.0.0-dev.31](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.0-dev.30...v2.0.0-dev.31) (2026-08-13)

### 🐛 Bug Fixes

* final captions ([8dce74d](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/8dce74d864171bda9d611bb38b787e7bb2703760))
* suppress music-only caption labels [skip ci] ([b58051a](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/b58051ab922a7130b6f9653f3e43c81c4881f375))
* suppress music-only caption labels [skip ci] ([a98c5a9](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/a98c5a9cd0e488bbbe419b0885616d80b26872f4))
* suppress non-speech music labels [skip ci] ([00cfa4c](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/00cfa4c3c355bb0480dda17fed043ca7ec246065))
* tune caption timing and suppress music labels [skip ci] ([b75e5ae](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/b75e5ae9af66ee4a8a6623b36cbf3839f4fcfc9f))
* tune caption timing and suppress music labels [skip ci] ([381bba9](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/381bba9e27a198150d8c539dbb67a53de1ac4d3d))

## [2.0.0-dev.30](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.0-dev.29...v2.0.0-dev.30) (2026-08-13)

### 🐛 Bug Fixes

* semantic caption display ([484fdb9](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/484fdb947689247a3dea94b30100f731de53ae68))

## [2.0.0-dev.29](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.0-dev.28...v2.0.0-dev.29) (2026-08-13)

### 🐛 Bug Fixes

* repair cross-video AI caption handoff ([c730297](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/c73029779df491bb0740900b6a938a149ae885ed))

## [2.0.0-dev.28](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.0-dev.27...v2.0.0-dev.28) (2026-08-13)

### 🐛 Bug Fixes

* preserve AI captions across videos ([2b236f1](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/2b236f13eba4c4d346cff903127f4191d7a39aba))

## [2.0.0-dev.27](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.0-dev.26...v2.0.0-dev.27) (2026-08-13)

### 🐛 Bug Fixes

* anchor captions to stable player geometry ([4776ba9](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/4776ba9adcb57d980fe979478a058b0c9106bd64))

## [2.0.0-dev.26](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.0-dev.25...v2.0.0-dev.26) (2026-08-13)

### 🐛 Bug Fixes

* restore AI captions after resume ([27d85ce](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/27d85ce2fac476aaab3134fc26587fab7516e156))

## [2.0.0-dev.25](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.0-dev.24...v2.0.0-dev.25) (2026-08-13)

### ✨ New Features

* prefer semantic sentence caption segments ([f9d3f8d](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/f9d3f8d0ce69bfffd78e025a83a7448b0ba57173))

## [2.0.0-dev.24](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.0-dev.23...v2.0.0-dev.24) (2026-08-13)

### 🐛 Bug Fixes

* stabilize caption geometry and sizing ([a9729d3](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/a9729d327b4b88a8614199788c5249cd6fccf2dd))

## [2.0.0-dev.23](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.0-dev.22...v2.0.0-dev.23) (2026-08-13)

### 🐛 Bug Fixes

* restore caption button AI toggle ([efe300e](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/efe300e75a400215180f501be64482a42aae1825))

## [2.0.0-dev.22](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.0-dev.21...v2.0.0-dev.22) (2026-08-13)

### 🐛 Bug Fixes

* reveal captions only after positioning ([f245197](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/f245197bc37593257960c437f46a83ad3bb5c816))

## [2.0.0-dev.21](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.0-dev.20...v2.0.0-dev.21) (2026-08-13)

### 🐛 Bug Fixes

* isolate caption button scanner animation impact ([11b9210](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/11b9210245717bf02d8851c71ace137248c37b2d))

## [2.0.0-dev.20](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.0-dev.19...v2.0.0-dev.20) (2026-08-13)

### 🐛 Bug Fixes

* isolate language menu scanner animation impact ([e8484ba](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/e8484ba953aa025f94efbf3798f936e07ea000f6))

## [2.0.0-dev.19](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.0-dev.18...v2.0.0-dev.19) (2026-08-13)

### 🐛 Bug Fixes

* force visible AI for animation isolation ([1dfeaf9](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/1dfeaf93810975dfb2082582b265ace043075552))

## [2.0.0-dev.18](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.0-dev.17...v2.0.0-dev.18) (2026-08-13)

### 🐛 Bug Fixes

* add runtime split diagnostic mode ([ffaf5f7](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/ffaf5f708ec1068a2a2b6eed87eaae06fc1f3f65))
* isolate animation scanners from caption runtime ([57d8ddb](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/57d8ddbfb10331efe66187ba21d0cd1982e57d24))

## [2.0.0-dev.17](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.0-dev.16...v2.0.0-dev.17) (2026-08-13)

### 🐛 Bug Fixes

* add animation diagnostic baseline ([a012dd7](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/a012dd76420bb19d9add6445fd8531988c4bbb1e))

## [2.0.0-dev.16](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.0-dev.15...v2.0.0-dev.16) (2026-08-13)

### 🐛 Bug Fixes

* isolate captions from player animations ([cbe5bbc](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/cbe5bbce52e5c1733ffce4316da2c679b4268e3b))

## [2.0.0-dev.15](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.0-dev.14...v2.0.0-dev.15) (2026-08-13)

### 🐛 Bug Fixes

* stabilize AI captions and player state ([029b47d](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/029b47d1d1fa5c4ba72e26fe7d2a5e533a748e17))

## [2.0.0-dev.14](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.0-dev.13...v2.0.0-dev.14) (2026-08-13)

### 🐛 Bug Fixes

* stabilize caption scheduling and player transitions ([e821745](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/e821745e4aa0e699e0324e9bbca8609e7bce7409))

## [2.0.0-dev.13](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.0-dev.12...v2.0.0-dev.13) (2026-08-13)

### ✨ New Features

* align AI captions with native playback ([283c3f4](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/283c3f4a310509e6d30e1736a07e6311e8b4561b))

## [2.0.0-dev.12](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.0-dev.11...v2.0.0-dev.12) (2026-08-13)

### 🐛 Bug Fixes

* restore language menu build ([6275776](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/62757768095ce6aa29bb96b1def2e61675e340ec))

### ✨ New Features

* polish caption menu latency and layout ([c350ed6](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/c350ed66cc4e4612a2dd687c85f65682e83cf797))

## [2.0.0-dev.11](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.0-dev.10...v2.0.0-dev.11) (2026-08-13)

### ✨ New Features

* refine caption display and defaults ([6c462c7](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/6c462c74314da24294b4f7bc7b91a77a376b2433))

## [2.0.0-dev.10](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.0-dev.9...v2.0.0-dev.10) (2026-08-13)

### ✨ New Features

* add multilingual caption sessions and model discovery ([789dc86](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/789dc866cfa96a1687c7ff7da28a3e0ccd4e36aa))

## [2.0.0-dev.9](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.0-dev.8...v2.0.0-dev.9) (2026-08-13)

### ✨ New Features

* add complete-sentence captions and native settings ([d40cf42](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/d40cf42f2e36dd3a18b3c37c4562b2853fa5edbc))

## [2.0.0-dev.8](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.0-dev.7...v2.0.0-dev.8) (2026-08-13)

### ✨ New Features

* add realtime micro-batches and draggable captions ([f150821](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/f150821a19c352f905a3c99cfaf1a21e83d16a26))

## [2.0.0-dev.7](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.0-dev.6...v2.0.0-dev.7) (2026-08-13)

### ✨ New Features

* add play-head driven AI captions ([1454e1c](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/1454e1c02aa7a7e00c1ddb974b315ec3f0a760a5))

## [2.0.0-dev.6](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.0-dev.5...v2.0.0-dev.6) (2026-08-13)

### 🐛 Bug Fixes

* keep Chinese captions visible during AI translation ([21cb917](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/21cb917637c6ec04fc57e4f5472e4f248e1c4261))

## [2.0.0-dev.5](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.0-dev.4...v2.0.0-dev.5) (2026-08-13)

### 🐛 Bug Fixes

* avoid source caption 429s with Cronet reuse and source cache ([bb1a505](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/bb1a50585f93dd8e8898b4e6b9e820c23fee0c11))
* capture YouTube Cronet engine for source captions ([fd769d8](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/fd769d8f14ebc241ce30a111b92ccbf1766dab22))
* pass YouTube Cronet engine into caption hook ([6210dfd](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/6210dfdf0546c2f8fa41fd637893e3c135a19172))

### ✨ New Features

* cache successful source caption tracks ([2ea7ba3](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/2ea7ba3df5c0262b7393a7c7552b2fe61900eaba))
* refresh Timed Text cookies on demand ([c0f5fee](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/c0f5fee0a4c22a7539bd160f68beaeb282e700a8))

## [2.0.0-dev.4](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.0-dev.3...v2.0.0-dev.4) (2026-08-13)

### 🐛 Bug Fixes

* use positional subtitle arrays and faster parallel batches ([5b4d119](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/5b4d119aa84ba237c3fbd719b82bfcceaaf46591))

### 🔧 Improvements

* shorten first-caption translation latency ([51b27f2](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/51b27f20cb11c93db2764e6b11e44780b3affbb5))

## [2.0.0-dev.3](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.0-dev.2...v2.0.0-dev.3) (2026-08-13)

### 🐛 Bug Fixes

* make subtitle batch translation tolerant and self-recovering ([b3c78d5](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/b3c78d557419ff1de0f98d3d59da044f2977b828))

## [2.0.0-dev.2](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.0.0-dev.1...v2.0.0-dev.2) (2026-08-12)

### 🐛 Bug Fixes

* harden localhost caption bridge and add diagnostics ([1394eae](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/1394eaee45e3b9db34557c08824ca8bb99f474cf))

## [2.0.0-dev.1](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v1.1.0-dev.1...v2.0.0-dev.1) (2026-08-12)

### ⚠ BREAKING CHANGES

* removes the launcher settings activity and the previous all-caption proxy implementation. Configuration now lives inside Morphe settings and only Chinese auto-translate requests are intercepted.

### 🐛 Bug Fixes

* compile XML caption parser on Android stubs ([c6d6456](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/c6d645693e8f19586fb3cff4993c2d57cf283473))
* restore compatible release markers ([410762d](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/410762d2f094b7cc74713ec75817c78b746a9b5e))

### ✨ New Features

* rewrite DeepSeek caption translator from scratch ([9a75386](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/9a75386bb5bcf08870484498697593c9de2a8397))

## [1.1.0-dev.1](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v1.0.0...v1.1.0-dev.1) (2026-08-12)

### ✨ New Features

* route Chinese auto-translate through DeepSeek API ([cfdfd8c](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/cfdfd8cd63f3f3a03c10616b05fbb12d9891f4f6))

## 1.0.0 (2026-08-12)

### 🐛 Bug Fixes

* compile against template dependencies ([fe93725](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/fe937259f4f4d69187976a036b1bd90e207ded9b))
* restore release readme markers ([013e3e6](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/013e3e63f2352eee3391a3eb240dd4a93897ec51))

### ✨ New Features

* add AI caption translator ([744a0a4](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/744a0a4f2dbf45fed798d7b5ad521145ad57fc10))

## 1.0.0-dev.1 (2026-08-12)

### 🐛 Bug Fixes

* compile against template dependencies ([fe93725](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/fe937259f4f4d69187976a036b1bd90e207ded9b))
* restore release readme markers ([013e3e6](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/013e3e63f2352eee3391a3eb240dd4a93897ec51))

### ✨ New Features

* add AI caption translator ([744a0a4](https://github.com/YYDarlinker/youtube-ai-caption-translator/commit/744a0a4f2dbf45fed798d7b5ad521145ad57fc10))
## [2.3.0-dev6-rc1](https://github.com/YYDarlinker/youtube-ai-caption-translator/compare/v2.3.0-dev5...v2.3.0-dev6-rc1) (2026-08-21)

### Device-test prerelease

- Roll back the dev5 continuation grouping so a displayed unit range cannot shrink into its own child slice.
- Correct strict display boundary selection and channel accounting: CJK fallback is disabled and token boundaries are final hard-cap fallback only.
- Add four-stage local preprocessing timing diagnostics for `countAppendEvents`, `SourceAtomTimeline.build`, `TranslationUnitTimeline.build`, and cache restore.

Acceptance checks for this release: no `CONTEXTUAL_DISPLAY_CONTINUATION_GROUP`; zero strict-path `cjk_fallback`; `token_boundary` below 5% of `DISPLAY_SELECTED`; first valid display within 1500 ms of `CONTEXTUAL_UNIT_TIMELINE_READY`; and no whole-caption-to-child-slice replacement within one unit time range.

This release does not contain the INV-7 independence gate. Occasional single-word fragments such as “年” are expected in this release and are not an acceptance failure.

This prerelease is for Morphe real-device testing and does not claim that every playback or provider edge case is fully resolved.
