# 当前项目状态

2026-10-29（Asia/Shanghai）。**集中工程治理阶段正式结束，项目进入正常功能开发和长期维护。** 用户已接受仓库治理及生产简化实验结论：保留现有实现，不合入分页实验，暂停以减行作为独立目标的整体重构。后续优化由真实问题、功能需求、测量瓶颈或明确维护障碍驱动。

## 当前固定基线

- 源码冻结基线codex/maint1-baseline-20261028 / 36fbb45cfab6f69ff6068c52890eb9ee050a3c5a，424文件；正常工程源码仍为maint1。正常维护分支和本次交付SHA以validation/runs/20261029-normal-maintenance-01/delivery.json为准。
- 已建立完整Git bundle及增量包，并在独立对象库逐字节核验恢复；私有诊断/历史包/验证原件继续在忽略目录。参见validation/runs/20261028-baseline-and-simplification-01/baseline.json、closure.json。普通维护生产代码不含实验b6808cf/2925c69；实验仅保留供历史复查。
- 固定手机验收候选1.5.8 / local-1.5.8-maint1：build/delivery/20261028-maint1/AI-Captions-1.5.8-maint1-test.mpp，592865 B，SHA-256 1282b7904c93890d87c626a134c3589b36c8644b618f3908502cea8e3301e815。候选及原发布1.5.8未替换，没有为阶段收尾新建产品包。
- maint1原936项/125 XML、同包两组合与治理末尾工具检查有效。此前普通工程输入397项/da56ab2ff0ad167c51351742710f1219556c5fb815b25e6a5418b3f0bed32bf5；本次仅改三个CI触发/分支门和当前文档，应用/构建/测试/资源输入保持，新的全工程指纹与复用差异另记本次evidence，不冒充旧全工程指纹不变。

## 阶段性判断

没有新增的源码、构建、目录或Git可恢复性阻碍需要再开展集中审计；正常功能/缺陷开发可以继续，使用普通分支和针对性验证，保持本次固定候选不混入新功能。

1. **手机有限验收待用户完成**：覆盖误判代码已修复，自动/离线结果有效，实际手机恢复尚未确认。验收说明在build/delivery/20261028-maint1/DEVICE-CHECK.md，先复测原约1:16–1:40片段，再检查基本开关/原文/普通与Shorts/切视频及设置。代理只判读反馈和诊断，不安装、清数据或调用真实模型。
2. **明确产品问题按缺陷处理**：若原停句仍复现或出现持续缺字幕/串视频/崩溃/配置丢失，保存复现与诊断，局部修复并验证。D3提示/术语策略差异依据实际样本判断，不默认完全等价，也不将所有未知质量变成新一轮治理前置。
3. **远端交付待授权与真实CI**：读取main/dev仍为db8948c9880fa6951306fe84bd113ffebcc74bc5。本地已准备安全配置：main/dev/功能分支push仅验证；Release仅main显式手动触发，dev→main PR助手仅dev显式手动触发。远端尚未收到这些门，不能按本地配置推断旧远端已安全。

## 已准备的交付与授权边界

先交付冻结的codex/maint1-delivery-20261029分支供真实CI，验证后再另行授权main（及需要时dev）受控快进；不是把维护分支存在当成main交付完成。交付集合排除分页实验、私有日志/数据、历史二进制和工具链，具体SHA/差异/恢复与workflow副作用见本次delivery.json及DELIVERY.md。

本次没有推送、创建远端PR/tag、运行远端workflow、发布或替换候选。源码同步、手机接受、公开新Release分开决定；发布前须有候选接受和明确发布授权，不因集中治理结束而自动发布。

当前日常入口：[开发](DEVELOPMENT.md)、[维护](MAINTAINING.md)、[架构合同](ARCHITECTURE.md)、[发布](RELEASING.md)。本次结论/检查：validation/runs/20261029-normal-maintenance-01/RESULT.md。状态镜像.verification/state-mirror/PROJECT-STATE.md与本文件同字节；不再维护平行路线/验收任务卡。
