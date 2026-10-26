# 安卓词库分级复核 v16

复核日期：2026-10-26。范围为现有 28,167 个安卓词条；没有修改 iOS，未直接写入安卓权威词库。等级是项目内部学习顺序判断，不是经实测标定的 CEFR 或考试成绩。

## 主要问题

原模型保留美国通用语料词频，并对长词和派生后缀叠加惩罚。这个骨架适合区分大部分长尾，但会产生三类明显偏差：

- 日常生活、学校学词场景常用词被美国新闻等通用语料的低频或词长推至高阶，例如 app 7级、grandpa 7级、policewoman 10级、pronunciation 10级。
- 英式写法独立承受低频惩罚，而意义相同的美式写法很低，例如 honour 10级、honor 2级；neighbourhood 10级、neighborhood 2级。
- 透明派生副词在原词频和长度基础上又受后缀惩罚，出现 conventionally 10级、conventional 3级等过大的学习顺序断层。

另有少量高频专业词被放得过早，例如 nuclear 2级、democratic 1级，以及若干3级法律术语。

## 处理原则

保留原始词频、NGSL 依据、基本词核心和敏感词最低等级；不重分等量桶，不批量按后缀猜同义关系。对504个具体拼写进行精确复核：492个下调，12个上调。每项包含原等级、新等级、类别、理由以及原词性、主释义、词频和已存在的对应词族/拼写证据。

只把已确认的普通意义用于分级。browser、microwave、tablet、jumper、boarding、booking、roundabout、canteen、tram、conditioner 需要与本轮现代常用义项内容补丁同时合并；这10项在审核表中标记 requiresContentPatch。

不会将 tire/tier、timbre/timber、sere/seer 等仅拼写接近的词当作英美变体，也不将 fatally 当作 fat 的规则副词。保留 august/August、dogged、moped 的独立义项安排。storey 按“楼层”调整至3级，不随 story 的“故事”义变为1级。kerb 按“路缘石”单独调整至4级，不机械继承 curb 的“抑制”动词主用法。

## 代表性调整

| 词 | 原等级 | 新等级 | 依据 |
| --- | ---: | ---: | --- |
| app | 7 | 2 | 手机应用，已有明确日常释义 |
| grandpa | 7 | 2 | 常见亲属称呼，与 grandfather 衔接 |
| stairs | 7 | 2 | 日常楼梯名词，与 stair 调整一致 |
| policewoman | 10 | 3 | 常见职业表达，与公共服务词衔接 |
| pronunciation | 10 | 3 | 语言学习中的基础概念 |
| dictionary | 7 | 2 | 基础学习工具 |
| password | 6 | 3 | 日常数字生活用词 |
| motorbike | 9 | 3 | 常见交通工具 |
| multiplication | 10 | 4 | 基础课堂数学，不是研究型长尾 |
| behaviour / behavior | 6 / 1 | 2 / 2 | 相同普通义，合并拼写难度 |
| honour / honor | 10 / 2 | 2 / 2 | 人工确认英美拼写对应 |
| aluminium / aluminum | 10 / 4 | 4 / 4 | 相同元素名的拼写差异 |
| conventionally | 10 | 5 | 与 conventional 的明确词族和普通义衔接 |
| nuclear | 2 | 4 | 需要原子/核能基础概念 |
| democratic | 1 | 4 | 政治制度词，应在生活基础带之后 |
| prosecutor | 3 | 5 | 法律程序中公诉人，不等同日常通用职业词 |

## 全库验证

- 全量运行原有分级函数，再加载精确审核表；审核表应用结果与新版分级函数对28,167词逐条一致。
- 除504个审核项以外，其他词等级不变；所有词的 ID、词频、词性、释义、例句等非 difficulty 字段不变。
- 审核表重复应用不再产生变化。
- 对十个同形/近形风险词设定显式保持检查：august、August、dogged、moped、timbre、timber、sere、seer、tire、tier。
- 对库内基本词的词形关系进行全量反向检查，未发现可在已有v13规则之外安全继续下调的“纯动词、释义反指原形、同时有明示屈折词形关系”的遗漏；因此没有新增后缀猜测降级。

| 等级 | 调整前 | 调整后 |
| --- | ---: | ---: |
| 1 | 1063 | 1062 |
| 2 | 896 | 939 |
| 3 | 1218 | 1370 |
| 4 | 1364 | 1474 |
| 5 | 1905 | 1881 |
| 6 | 2935 | 2891 |
| 7 | 3600 | 3548 |
| 8 | 4165 | 4112 |
| 9 | 4166 | 4107 |
| 10 | 6855 | 6783 |

数据与复现：`level_review_v16.json` 为精确审核表，`apply_level_review_v16.py` 为只读审计/合并辅助，`level_review_v16_report.json` 保留每个实际变化。`vocabulary_levels.py` 使用 learning-order-v5，仍沿用原有分数骨架，只新增精确审核表优先级。

```sh
python3 tools/apply_level_review_v16.py --report /private/tmp/level-review.json
```

最终合并由安卓内容流水线执行 `apply_review(words)` 或 `assign_levels(words)`，并同步 metadata 的分布、版本与词库哈希，重新构建预填充 SQLite 和 V04 二进制缓存。勿直接更改历史学习日志或用户等级。
