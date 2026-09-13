# AI Caption Translator for YouTube / Morphe

这是一个 Morphe 自定义补丁。它把 YouTube 原生“自动翻译”菜单变成用户自有 API 的实时
AI 字幕入口：菜单选择哪一种语言，模型就翻译成哪一种语言。所有 AI 译文都由播放器内的本机
字幕层显示，不依赖 YouTube 服务端翻译轨道。

## 最终交互

不新增播放器按钮，也不新增桌面图标。

在 YouTube 中照常操作：

```text
点按播放器字幕按钮 → 默认直接启用 AI 中文（简体）

或：视频 → 字幕 → 自动翻译 → 中文（简体）/ 中文（繁体）/ 任意目标语言
```

字幕按钮的默认语言可以在 Morphe 设置中修改，也可以设为“跟随 YouTube”。补丁会在
YouTube 没有提供简体中文时，进入“自动翻译”语言页会打开补丁自己的完整语言选择器，
“中文（简体）”固定为第一项。选择器通过 YouTube 的真实虚拟无障碍菜单节点触发原生翻译
命令，再把目标代码替换为所选语言，因此不再修改繁体行高度，也不依赖菜单项必须是普通
TextView。选择以后，两条链路立即并行：

```text
YouTube 任意 tlang 请求 ──→ 127.0.0.1 立即返回合法空轨
                         （绝不重定向回 YouTube 翻译地址）

移除 tlang 的原字幕 ──→ 解析原生 cue 时间轴 ──→ 当前与随后最多 2 cue 抢占送往 API
                                                ↓（相邻 cue 只提供语境）
                                YouTube 播放器内 AI 字幕层显示
                                           ↓
                         单路有界批次滚动预取后续 24 秒
```

YouTube 原英文字幕的每个 cue 都是最终显示单位：模型可以参考前后 cue 理解跨段语义，但必须
逐项返回，译文始终沿用原 cue 的开始和结束时间。补丁不再把若干 cue 聚成十几秒长句，也不再
按中文字数重新创造二次时间轴，因此不会把完整词语切到两帧或让快速口播字幕逐渐慢半拍。较长
译文由播放器在同一个 cue 内自然换成最多两行。播放器原生时间回调之间再以 80 ms 节拍短程
插值，短 cue 也不必等下一次约一秒一次的回调才更新。

选择语言后会立刻看到“AI 字幕准备中”的反馈；当前 cue 一返回就显示，不等待整部视频翻译
完成。原始字幕、目标语言和视频 ID 都写入会话身份；切换视频时旧字幕与旧 API 请求会立即终止。

## App 内设置

补丁直接向 **YouTube → 设置 → Morphe** 注入带字幕图标的统一入口：

**AI 字幕翻译**

点击后进入带返回工具栏的 Morphe 原生二级设置页。二级页不重复显示图标，所有输入框和滑杆都
直接在页面内；没有整页弹窗，也没有“保存”按钮。开关即时保存，滑杆松手即保存，文本停止输入
约 0.85 秒后自动保存（离开输入框时还会再确认一次）。页面包含：

- 启用 AI 字幕翻译
- 字幕按钮默认语言（默认 AI 中文〔简体〕，也可跟随 YouTube 或改选其他语言）
- API 地址
- 模型（根据 API 地址和 Key 自动获取、下拉选择，也支持手动输入）
- API Key
- 翻译要求
- 字幕大小（12–36 sp，页内滑杆）
- 黑色背景不透明度（0–100%，页内滑杆）
- 恢复字幕默认位置
- 测试 API
- 清除翻译缓存
- 删除本机 API Key
- 字幕链路诊断（点击项目即可复制完整内容）

**没有 Activity、没有 Launcher intent、没有额外桌面设置图标。**

API Key 使用 Android Keystore 生成的不可导出 AES-GCM 密钥加密后保存在设备本机；Key
不会写进补丁文件或 GitHub 仓库。

## 默认配置

```text
API 地址：https://api.deepseek.com
模型：deepseek-v4-flash
字幕按钮默认语言：AI 中文（简体）
```

当前播放 cue 与随后最多 2 个 cue 使用独立抢占通道；如果后台请求占用播放位置，会立即取消
并让位，但正常播放进入下一个短 cue 时不会反复取消仍有用的当前请求。最多允许 2 个当前批
接力，首批显示后只保留 1 路后台请求，每批最多 7 个原生 cue，滚动预取播放位置后约 24 秒。
前后各 1 个 cue 仅作为上下文、不要求 API 返回译文。翻译请求使用 OpenAI-compatible
`chat/completions`，按文本量设置带安全下限的输出预算，并要求模型以严格 JSON 数组按原顺序
一一返回译文。DeepSeek V4 字幕请求会显式关闭默认的高强度思考模式，避免推理 token 截断
字幕 JSON 或把首批响应拖慢到超时。

字幕层默认相对字号 18、黑色背景不透明度 70%。字号按播放器画面短边相对缩放，详情页与
全屏保持相同的文字/画面比例。实际字幕保留原生 cue，较长译文可自然显示两行；如果在极窄
屏幕上使用很大的字号，只有放不下的 cue 会临时缩小而不会被裁掉。字幕
始终水平居中；在播放器中长按约 0.35 秒后只能上下移动，不会因手指横向偏移而离开中心。竖直
位置按横屏和竖屏分别保存，设置页可以恢复默认位置。

## 稳定性设计

这版与旧项目的最大区别是：**显示链路不再依赖完整 Timed Text 响应，也永远不会回到
YouTube 的服务端翻译轨道。**

- 只有原生 CC 状态或用户的字幕按钮/语言菜单操作明确要求开启字幕时，`tlang` 才会被改写到
  仅绑定 `127.0.0.1` 的空轨接收器；YouTube 在 CC 关闭时预取或恢复的旧 `tlang` 不会唤醒
  AI 字幕。GET、POST、HEAD 和异常分支都不包含 307/YouTube 翻译回退。
- 原字幕通过移除 `tlang` 获取，并优先复用 YouTube 自己的 CronetEngine；这不是服务端翻译
  路径。
- 使用 Morphe 同源的播放器时间指纹取得真实播放位置。无资源 `TextView` 只附加到稳定的
  Activity 内容根层，YouTube 播放器仅作为只读几何来源；字幕在短期过渡帧内读取播放器的
  屏幕矩形并同步位置和字号，绝不向正在动画的播放器 View 树增删或重新排序子 View。
- 使用 YouTube 自己的 PlayerType 回调识别内置迷你播放器；进入最小化、滑动关闭和画中画
  时仅隐藏稳定根层中的字幕，保留译文、目标语言和翻译会话；PlayerType 钩子先让 YouTube
  原回调启动动画，再异步更新字幕可见性。小窗返回详情页全程不修改播放器动画树，也不会
  切回英文。
- 与官方 Morphe `Captions` / `Caption Cookie` 补丁可以同时使用。
- 只接管自动翻译的 `tlang` 请求；原始字幕轨和关闭状态下的网络请求保持透明。
- 支持 JSON3、XML/SRV3、WebVTT、SRT。
- 字幕时间戳从不交给模型；每项译文一一对应原生 cue，前后相邻 cue 仅用于少量上下文；不再
  聚合长句或按译文长度重分配时间。
- 冷缓存先抢占提交当前与随后最多 2 个 cue；首批完成后以单路、每批最多 7 cue 持续补足
  后续 24 秒。当前请求不会因普通短 cue 推进而被逐段取消，避免取消—重发风暴。
- 默认语言会在视频原字幕出现时静默预热首条字幕；用户打开 AI 字幕时直接显示已经完成的
  结果，不再从点击时才开始下载与排队。公网模型本身超过一秒时仍取决于 API 首包延迟。
- 小批次的 `max_tokens` 按文本量计算并保留安全下限；DeepSeek 系列模型显式使用非思考模式。
  成功连接允许 Android 复用 HTTP keep-alive，减少重复 TLS 建连开销。
- 每 80 ms 检查一次当前 cue；播放位置追上未完成的后台批次时立即取消后台连接并由当前通道
  抢占，拖动进度条超过约 2.9 秒时也采用同一逻辑。
- 对 429、5xx、空输出和异常 JSON 做限次重试；当前 cue 最多 3 次、后台批次最多 2 次，后台
  失败不会提前把尚未播放的 cue 永久标成失败。
- 当前视频 ID 由播放器回调显式绑定；切换视频会在下一帧前断开旧 API 连接并移除旧字幕层。
- 视频、源字幕、目标语言和翻译配置共同组成会话/缓存身份，不会跨视频或跨语言串用译文。
- 配置或本机桥等终止性异常会显示明确状态；单个网络批次的暂时失败只进入诊断和限次重试，
  不再把“翻译失败/翻译中”当作字幕反复闪在画面上。任何情况下都不会显示原文冒充译文。
- 成功译文以“原字幕内容 + 目标语言 + 模型 + 翻译要求”作为缓存键，不依赖会变化的 YouTube URL
  签名；每完成一个微批就异步写入稀疏缓存，下次可以部分或全部立即命中，缓存上限约 150 MiB。

本项目以 1–2 秒作为正常网络和快速模型下的首批体验目标，但客户端无法对公网、API 排队或
第三方中转站作绝对时限保证。架构本身不再让后台调用阻塞当前播放段：原字幕缓存和译文缓存均
异步落盘；后续 cue 以低请求量提前预取，再次观看同一字幕时已缓存结果无需 API。

## 导入 Morphe

手机打开：

**https://morphe.software/add-source?github=YYDarlinker/youtube-ai-caption-translator**

或在 Morphe 远程补丁源中添加：

```text
https://github.com/YYDarlinker/youtube-ai-caption-translator
```

使用 **Expert mode**，正常选择官方 Morphe 补丁，再额外勾选：

```text
AI caption translator
```

旧的 `youtube-zh-hans-patch` 不需要选择。

## 补丁列表

<!-- PATCHES_START EXPANDED -->
> **[v2.3.0-dev.8](https://github.com/YYDarlinker/youtube-ai-caption-translator/releases/tag/v2.3.0-dev.8)**&nbsp;&nbsp;•&nbsp;&nbsp;`dev`&nbsp;&nbsp;•&nbsp;&nbsp;1 patches total
<details open>
<summary>📦 YouTube&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 🧪&nbsp;21.32.2 | 🧪&nbsp;21.31.523 | 🧪&nbsp;21.28.204 | 21.04.223 | 20.51.39 | 20.31.42 | 20.21.37 |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [AI caption translator](#ai-caption-translator) | Translates every YouTube Auto-translate language in real time through your OpenAI-compatible API. |  |

</details>

<!-- PATCHES_END -->

## 当前 YouTube 兼容目标

与 Morphe v1.39.1 的 YouTube 基线一致：

- 21.32.2（experimental）
- 21.31.523（experimental）
- 21.28.204（experimental）
- 21.04.223
- 20.51.39
- 20.31.42
- 20.21.37

## 构建

```bash
./gradlew buildAndroid
```

发布产物位于 `patches/build/libs/patches-*.mpp`。`dev` 分支用于真机预发布验证。

## License

[GNU General Public License v3.0](LICENSE)
