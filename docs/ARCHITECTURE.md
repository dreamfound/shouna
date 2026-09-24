# 收纳助手（Shouna）系统设计说明书 · 总览

> 版本：v1.2 ｜ 类型：系统设计与任务分解 ｜ 语言：中文
> 架构师：高见远 ｜ 交付总监：齐活林 ｜ 上游输入：[shouna/docs/PRD.md](shouna/docs/PRD.md) v1.4（产品经理：许清楚）
> 平台：Android 原生（唯一平台） ｜ 包名：`com.dream.shouna` ｜ 本文档只做设计与任务分解，不含任何代码文件；文中 Gradle / 清单改动均为**待工程师写入的草稿**。
> **本页为总览页**；正文已按章节拆入 `shouna/docs/arch/`，共 13 个文件（见下表）。
> **路径基准**：本页路径一律以**工作区根**为基准，写作 `shouna/docs/arch/…`，与本页所在位置无关。

**一句话结论**：本地优先（Room 2.8.4 + KSP）的 Android 原生收纳记录 App——**一件物品只归属一个位置**（`item.locationId` 单一归属），**同名物品是彼此独立的实体、身份一律以 ID 判定**，用「内存归一化索引」实现中文子串 + 拼音搜索，零权限、零联网，由 5 个实现任务闭环。

## 版本变更

| 版本 | 日期 | 变更摘要 |
|---|---|---|
| v1.0 | 2026-09-23 | 首版：§0 结论速览 + §1–§10 + 附录 A |
| v1.1 | 2026-09-23 | ① 修正拼音依赖坐标（`tinypinyin` 404 → `com.github.houbb:pinyin:0.4.0`）并移除 JitPack 仓库；② 记录 KSP、hilt-navigation-compose 版本 pin 与核验证据；③ **按章节拆为 `shouna/docs/arch/` 12 个文件，本页降为总览** |
| v1.2 | 2026-09-23 至 2026-09-24 | ① 物品归属改为「一物一处」（`item.locationId` 单一归属）：改写了本页第 9 行一句话结论与 `arch/01`、`arch/02`；**`arch/03`、`arch/04` 等尚未同步（仍为「多对多 Placement」），两套模型并存冲突，待裁决**；② 新增 `shouna/docs/arch/13-V2预留接口契约.md`（登记 V2-SEAM-01~06 等预留位）；③ 本页文件导览表由 12 个文件增至 13 个 |

## 文件导览

| 编号 | 文件（全局路径） | 读完能回答什么问题 | 适合谁看 | 原章节 |
|---|---|---|---|---|
| 01 | [shouna/docs/arch/01-结论速览.md](shouna/docs/arch/01-结论速览.md) | 这份设计最终拍板了哪些技术与数据决策 | 所有人（先看这个） | §0 |
| 02 | [shouna/docs/arch/02-实现方案总述.md](shouna/docs/arch/02-实现方案总述.md) | 整体分几层、数据怎么流、每个技术点选了什么/否决了什么 | 所有人、工程师 | §1 |
| 03 | [shouna/docs/arch/03-关键架构判断.md](shouna/docs/arch/03-关键架构判断.md) | 每个架构决策为什么这么定、否决了哪些备选 | 架构师、评审、工程师 | §2.1–2.14 |
| 04 | [shouna/docs/arch/04-数据结构与接口.md](shouna/docs/arch/04-数据结构与接口.md) | 有哪些实体/DAO/仓库/领域模型，字段与方法签名是什么 | 工程师（照抄签名） | §3 |
| 05 | [shouna/docs/arch/05-调用流程时序图.md](shouna/docs/arch/05-调用流程时序图.md) | 录入/搜索/移动/备份/启动这五条关键链路怎么走 | 工程师 | §4.1–4.5 |
| 06 | [shouna/docs/arch/06-文件列表.md](shouna/docs/arch/06-文件列表.md) | 一共要写哪些文件、每个干什么、归哪个任务 | 工程师、项目经理 | §5 |
| 07 | [shouna/docs/arch/07-依赖包清单.md](shouna/docs/arch/07-依赖包清单.md) | 引哪些依赖、版本为什么这么定、它们真的能拉到吗 | 工程师、构建/风险评审 | §6 |
| 08 | [shouna/docs/arch/08-共享知识.md](shouna/docs/arch/08-共享知识.md) | 跨文件的命名/状态/时间/ID/测试统一约定 | 全部实现者 | §7 |
| 09 | [shouna/docs/arch/09-待明确事项.md](shouna/docs/arch/09-待明确事项.md) | 对 PRD 的架构约束（A-1～A-5）与未决问题默认值 | 产品、架构师 | §8 |
| 10 | [shouna/docs/arch/10-风险与验证点.md](shouna/docs/arch/10-风险与验证点.md) | 最容易做错的是哪几处、怎么验证做对了 | 工程师、测试 | §9 |
| 11 | [shouna/docs/arch/11-任务列表.md](shouna/docs/arch/11-任务列表.md) | 按什么顺序、依赖谁、由哪个任务产出哪些文件 | 交付总监、工程师 | §10 |
| 12 | [shouna/docs/arch/12-附录A-字段映射.md](shouna/docs/arch/12-附录A-字段映射.md) | PRD 的每个业务字段具体落到哪张表哪个列 | 产品、工程师 | 附录 A |
| 13 | [shouna/docs/arch/13-V2预留接口契约.md](shouna/docs/arch/13-V2预留接口契约.md) | V2（AI 接入版）要预留哪些接口、每条缝的签名与 V1 最小落地成本是什么 | 架构师、V2 立项者、工程师 | —（v1.2 新增） |

---

*本文档为架构设计与任务分解产出，不含代码文件。若与 `shouna/docs/PRD.md` 存在语义冲突，已在 [shouna/docs/arch/09-待明确事项.md](shouna/docs/arch/09-待明确事项.md) 以「架构约束」名义列出，未修改 PRD 原文。*
