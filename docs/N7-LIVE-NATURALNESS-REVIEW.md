# N7 当前切点实译与目检表

**结论：未达到 N7 自动验收。** 最终 live 轮的 10/10 块通过源词连续归属、精确引文、非空翻译契约；A02/A03/A05 报警为 0，A09 仍为 1，全部案例报警也为 1。四轮额度已到，未再追加调用。自然度及跨块连贯性须人工目检，字串报警不是自然度签字。

## 仪器与证据范围

`N7CurrentPlannerPayloadExportTest` 用当前 Java `RebuildPlanner.plan` 和 `RebuildProtocol.payload` 对冻结诊断覆盖的 **0–686 共 687 个源词**重建请求；逐词沿用源词、时间和 cue，逐块保留录制时的显示预算。新旧均为 **10 块**、每词恰好归属一次；这是 A01–A12 所在的有界记录范围，不是整部视频。A04 相关 b1/b2 从 `25–87 | 88–180` 变成 `25–97 | 98–180`；A08 相关 b5/b6 从 `314–392 | 393–474` 变成 `314–382 | 383–474`。其他块边界未变。旧 live 10 块实耗 13,455 token，起初按约 1.35 万 token/轮预估；修正 prompt 提取后，完整 prompt 的最终实耗为 **22,544 token/轮**，今后同范围应按约 2.2 万 token 估算。初估偏低的原因是 Python 提取器误把 Java 字符串内部的分号视为常量结尾，先前 live 请求漏带了后半段 prompt；已修正并加测试。

冻结层 `scoreboard/results/frozen-baseline.json` 记录 v1.3.5 真机那次会话及离线策略镜像，未用新译覆盖。下表旧译来自该冻结响应，新译仅来自当前配置模型与默认偏好的 live 生成层；旧手机的自定义偏好未知，新译没有真机 PRESENTED、字体或音频证据。

## 回放账本

| 轮次 | prompt/范围 | 四案报警 / 全案例报警 | 契约 | provider token（入+出=总） |
|---|---|---:|---|---:|
| 0 | 新切点 10 块；后来发现 Python 提取的 prompt 不完整，此轮不作当前 prompt 验收依据 | 1 / 1（A09） | 10/10 | 10,493+2,872=13,365 |
| 1 | 一处通用引导已写入 Java，但提取缺陷使其未发送；首块越界 `0–25`，拒收并停止，未重试 | 未能全量计分 | 0/1 | 621+115=736 |
| 2 | 修正提取器后的完整 prompt；每块仅请求一次 | 1 / 1（A09） | 9/10；b7 的 `and` 被挪入相邻事件引文 | 19,243+2,522=21,765 |
| 3 | 同一处通用引导收紧，最后一次完整回放 | **1 / 1（A09）** | **10/10**；全局 0–686 各一次 | **19,733+2,811=22,544** |

四轮共 31 次 API 尝试、58,410 token；没有对失败块偷偷重试。第 2 轮 b7 失败后只继续当轮未尝试的 b8/b9，因此每块仍最多尝试一次。原始生成层记录保存在本地 ignored 的 `scoreboard/results/live-n7-round{0,1,2,3}-*.json`；最终目检以 round 3 为准。prompt 只在 `FIDELITY_PROMPT` 一处加入并收紧通用词义、语气、修饰落点、解释性概念和精确引文引导，未设四句词表替换。

## 新旧译文对照（箭头为相邻事件）

| 案例 | 源文 | 冻结旧译 | 最终 live 新译 | 一句话说明 |
|---|---|---|---|---|
| A02 | the internet was practically drowning in articles and indexes all of which confidently put russia in second | 互联网上几乎充斥着文章和索引，它们都信誓旦旦地把俄罗斯排在第二位。 | 互联网上充斥着各种文章和排名，全都自信地将俄罗斯列为第二。 | “索引”消失，排名语境较顺；“几乎”弱化为“充斥”，请目检语气。 |
| A03 | heck there are even a few that put russia in first | 见鬼，甚至还有少数把俄罗斯排在第一位。 | 甚至有几篇把俄罗斯排在了第一。 | “见鬼”消失，递进保留；感叹强度降低。 |
| A05 | and all i could really think looking at these even long before the invasion was what about china | 而我看着这些文章时，真正想到的只有：甚至早在入侵之前，中国又如何呢？ | 看着这些文章，甚至在入侵发生很久之前，我真正在想的是：那中国呢？ | 时间短语已落在“我想”之前，旧错位串消失。 |
| A09 | well i've talked before about the fact that military strategy is built strategy | 嗯，我以前谈过，军事战略是构建出来的战略。 | 我之前说过，军事战略是构建出来的战略 | 核心同义反复未改善；报警 1，不能签自然度通过。 |
| A04 跨块 | after all who cares how many nuclear aircraft carriers or stealth bombers the americans have if ivan's got 10 000 t-62s rusting in siberia somewhere | 毕竟，谁在乎美国人有多少核动力航母或隐形轰炸机？ → 如果伊万在西伯利亚某处有一万辆锈蚀的T-62坦克 | 毕竟，谁在乎美国有多少核航母或隐形轰炸机， → 只要伊万在西伯利亚某处有10000辆T-62坦克在生锈就行了。 | 条件句已在同一请求内且前页逗号承接；事件仍分开，“只要…就行了”有别于原反问，需目检。 |
| A08 跨块 | come 2017 surface-to-air missiles based on mainland china could range out over taiwan itself so what am i going to be talking about today | 到了2017年，部署在中国大陆的地对空导弹射程可以…… → ……射程可达台湾本岛上空。所以，我今天要讲些什么呢？ | 到了2017年，部署在中国大陆的地对空导弹射程已能覆盖台湾本身 → 那么我今天要讲些什么呢 | 射程短语不再硬断且提纲独立成事件；“覆盖台湾本身”与缺少句末标点需目检。 |
| A06（旁检） | which leads you to ask the question if chinese defence spending is so minimal well then where are all these stealth fighters aircraft carriers hypersonic missiles and anti-ship ballistic missiles coming from | 这就让人不禁要问：如果中国国防开支如此之少，那么这些隐形战斗机、航空母舰、高超音速导弹和反舰弹道导弹都从何而来？ | 这让人不禁要问，如果中国国防开支如此微薄 → 那么这些隐形战机、航母、高超音速导弹和反舰弹道导弹从何而来 | 反问与四类装备均保留；两事件拆在条件之后，待查展示分页。 |
| A10（旁检） | firstly that the chinese defense budget is actually larger than you think it is not because they're trying to hide it but because of different accounting standards inclusions and exclusions | 第一，中国的国防预算实际上比你以为的更大；这不是因为他们想隐瞒，而是因为会计标准不同，以及纳入和排除的项目不同。 | 首先，中国国防预算实际上比你想的要大，并非因为他们刻意隐瞒 → 而是由于不同的会计标准、包含项和排除项 | 预算、非隐瞒及会计口径三层意思保留；实际窄屏排版未验。 |

局部报警对 A04/A08 只抓旧坏串，无法证明跨块句法自然；以上措辞判断是目检提示，不是自动通过。A09 源字幕本身写作 `built strategy`，未据无音频证据改动英文或臆造理论。最终结果未达到“四案报警为 0”的验收线。

本地校验：`python -m unittest discover -s scoreboard -p 'test_*.py'` 共 17 项通过；用完整的 Temurin JDK 21 运行 `gradlew test --console=plain` 构建成功。默认 JDK 25 会使旧 Robolectric/ASM 测试在类读取阶段批量失败，因此本次完整测试改用 JDK 21。`frozen-baseline.json` 的 Git 内容无改动。
