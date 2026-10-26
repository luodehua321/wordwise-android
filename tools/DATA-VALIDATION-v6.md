# 英式音标数据与缓存验证

## 来源及覆盖

使用 [ipa-dict 固定提交说明](https://github.com/open-dict-data/ipa-dict/blob/972cd67dd5f96bc5660e28070cde26e3928abcd7/README.md) 中明确标为 English (Received Pronunciation) 的 `data/en_UK.txt`。原始来源为 [ipacards](https://github.com/melissaboiko/ipacards)，遵循 GPL-3.0，与现有美国英语数据的 MIT/BSD 来源分别保留。固定版本原始文件有 65,119 个不同拼写，SHA-256：

`221394caef0cf723b4f2df81a98ac33191293257b88aed5b1fb89466d3a0dc77`

现有 20,000 词全部保留，精确匹配覆盖 **18,877 词（94.385%）**。其余 **1,123 词**的 `pronunciationUk` 为空字符串。上游主动省略一些异读词，也不包含所有美式拼写，缺失词包括 read、a、color 等；不通过删词、机械转换、复制美式数据或重写拼写掩盖缺口。音标原文不改写，显示层可以另选第一读法。

| 单词 | 美国源原文 | 英国源原文 |
| --- | --- | --- |
| car | /ˈkɑɹ/ | /kˈɑː/ |
| bath | /ˈbæθ/ | /bˈɑːθ/ |
| schedule | /ˈskɛdʒuɫ/, /ˈskɛdʒʊɫ/ | /ʃˈɛdjuːl/ |
| the | /ˈðə/, /ðə/, /ði/ | /ðə, ði/ |
| read | /ˈɹɛd/, /ˈɹid/ | 空字符串 |

完整未修改原始数据、上游固定 README、GPL-3.0 原文和可复现的项目抽取代码都随包放在 `app/src/main/assets/british_ipa/`。词库总许可 `dictionary_licenses.txt` 与来源 `dictionary_sources.json` 同步更新。英国数据对应抽取代码以 GPL-3.0 提供。

## 已执行校验

- `python3 tools/british_pronunciations.py --check`：20,000 词的英国字段逐个对照固定原始来源；覆盖统计、源文件和词库 SHA-256、随包源代码一致。
- `python3 tools/prepare_vocabulary.py --check`：全部源词条、SQLite 完整 payload、Room schema/identity hash、数据库完整性、无用户记录及派生文件 SHA-256 一致。
- 独立 Python 二进制读取器：按缓存协议逐字段读取全部 20,000 词，包含每条例句、搭配、派生、易混词、屈折词形和新英国字段，逐项对照权威 JSON，流末没有额外字节。
- 与本次修改前的 Android 合并 asset 全量对比：剔除 `pronunciationUk` 后，每个词及所有既有字段完全相同。
- Python 3.9 编译检查通过，缓存写入临时目录。
- 随包抽取脚本直接读取随包原始文件，全量英国字段验证通过。

最终权威词库 SHA-256：`ddc72a4c01ae9d26215301844009862254bbadf80f9a412cd7faf893713d7e24`。

## 升级兼容

`Word.pronunciationUk` 在位置参数末尾增加，默认值为空字符串；旧调用无需改动。旧数据库 JSON 缺失新字段时 `optString` 返回空字符串。JSON schemaVersion 仍为 1，内容版本为 6。Room schema 仍为 4，没有新 SQL 列或结构迁移；新 JSON 哈希触发现有事务 `@Upsert` 路径更新词条，用户资料、答题、复习进度、学习时长和校准表不重建。

二进制缓存升为 formatVersion 2、魔数 `LINGOV02`，每个词在原字段末尾追加英国字符串；旧缓存格式不会被误读，会回退数据库 payload。新安装使用同步更新的完整预编译 SQLite 和二进制缓存。Android 构建、读写和实际升级回归由主任务统一执行，本报告不代替运行时验证。
