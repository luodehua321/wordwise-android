## v2.0.5 最终双语内容入口

当前28,167词，内容版本17。先运行 `python3 tools/complete_learning_content.py`，再运行 `python3 tools/prepare_vocabulary.py`；两者 `--check` 仅验证。v14输入包含原创译文、例句、明确英文修订和关联词读音表，不运行网络查询。已有英文的原署名保留，修订另作记录。不会用模板拼译填平无法确认的缺口。

详见根目录 `BILINGUAL-AND-UI-v2.0.5.md`。旧全源构建会访问配置的外部输入，本轮未运行该路径；只使用上述当前最终入口即可离线复现已确认的修订。

## v2.0.4 最终离线复核入口

当前28,167词，内容版本16，分级learning-order-v4。运行 `python3 tools/apply_complete_review.py` 应用内部原创补词/补项/纠错与显式等级复核，再运行 `python3 tools/prepare_vocabulary.py` 重建SQLite和二进制缓存。两者的 `--check` 仅验证，不写入。

输入为 `internal_content_v13.json`、`missing_words_v13.json`、`level_review_v13.json`；无下载、无外部词库导入。新词美音含离线项目人工复核，UK仅精确匹配已有内部来源。完整变更与真实缺口见根目录 `VOCABULARY-REVIEW-v2.0.4.md`。执行历史全源构建会访问其配置的外部输入；本轮未运行该路径。使用当前最终复核入口，避免单独运行旧版本写入器造成元数据版本回退。

# 当前词库内容版本15 / APP v2.0.3

本轮仅使用项目内置数据、内部互证和原创内容，未查询或导入外部词库。全量逐词报告、修订数字与剩余缺口见 [VOCABULARY-REVIEW-v2.0.3.md](../VOCABULARY-REVIEW-v2.0.3.md)。应用修订使用 `apply_internal_review.py`，原创输入和内部依据分别是 `internal_content_v12.json`、`confirmed_content_fixes_v12.json`。修改后必须重建并核对 SQLite / V04 二进制；当前 Room schema 为4。下文为旧版历史记录。

# 当前词库 v10 / APP v1.17

全量整理结果、重新分级依据、仍有缺口与复现命令见 [VOCABULARY-REVIEW-v1.17.md](../VOCABULARY-REVIEW-v1.17.md)。下方为历史记录。

# 当前词库 v9 / APP v1.16

仍为25,000词，新增50条复核英式音标，补充表共200词；英式覆盖23,490，缺口1,510。新增70组原创中英语境和69组搭配输入，双语覆盖6,004词、任意例句14,188词、搭配9,243词。原始输入为practical_enrichment_v9.json，SHA随manifest保存。新增句子为项目原创CC0，不是上游词典原句。

启动缓存为formatVersion3 / LINGOV03，新增独立校验命令`python3 tools/check_prepared_binary.py`。重建脚本、原始JSON、词库来源与Room v4保持。以下旧版章节作为历史保留，当前统计以manifest及v1.16审计为准。

---

# 当前版本：v1.14 / 词库内容 v8（2026-10-24）

保持25,000词，本轮新增100条英式音标，独立复核补充表总计150词；英国字段覆盖23,440词，缺口1,560词。新增61组原创中英例句和搭配，来源为项目原创CC0，不冒充上游词典内容。例句覆盖14,168词，双语例句5,934词，搭配9,215词。

显示层保留IPA源符号 `ɹ`、`ɫ`，不再强制转成 `r`、`l`。read从美国源中选取原形 `/ˈɹid/`，不另造读音。其他源中本身使用 `r` 的条目按源保留。

等级为项目词频与标签估计。新增 `levelMethod` 元数据、`level_corrections.json` 及逐词审计：

```sh
python3 tools/audit_levels.py --ecdict /path/to/ecdict.csv --output dist/validation-v1.14/level-audit.json
```

`mum`因美国语料低频误列10级，已按常见基础生活词调整至3级并保存依据。其余总体分档规则保持；58.324%的词仍在最高档，尚未用学习者数据实证标定，不等同CEFR。详情见 `LEVEL-AUDIT-v1.14.md`。

重建与缓存校验命令沿用下方；新增原始输入为 `practical_enrichment_v8.json` 和 `level_corrections.json`，哈希写入manifest。下方旧版章节保留作历史记录，当前统计以v1.14审计文件和manifest为准。

---

# 词库 v7 / APP v1.13 更新（2026-10-24）

当前词库为 **25,000 词**，以下旧版本章节作为历史记录保留；当前统计以 `app/src/main/assets/dictionary_sources.json` 和 `dist/validation-v1.13/vocabulary-audit.json` 为准。

- 新增 5,000 词，25,000 词均有美国源 IPA、中文释义、词性、词频与难度。
- 英式音标：23,290 个精确源词 + `british_ipa_supplement.json` 中 50 个项目复核词 = 23,340；仍缺 1,660。补充表参考固定 ECDICT，包含常见词性/时态异读；与英国原始 GPL 数据分别保留，不修改上游原文。
- 例句覆盖 14,156 词，搭配 9,189，词族 13,106。内容并非逐句人工审核，源匹配不等于绝无语言错误。
- 词形现在保留与原词同形的过去式/分词，并合并同一拼写的多个角色。`word_form_corrections.json` 校订 sheep / deer / moose，保存依据及原因。
- read 在学习卡优先显示原形读音 /riːd/，美国源原文及过去式异读保留在词库中。
- 内容版本 7，Room schema 仍为 4；更新通过原子 upsert 保留学习资料。修改后必须重建 SQLite 和二进制缓存。

重建默认选择 25,000 词，其余固定源和哈希不变：

```sh
python3 tools/build_vocabulary.py
python3 tools/british_pronunciations.py --check
python3 tools/prepare_vocabulary.py
python3 tools/prepare_vocabulary.py --check
python3 tools/test_vocabulary.py
python3 tools/audit_vocabulary.py --ipa /path/to/en_US.txt --output dist/validation-v1.13/vocabulary-audit.json
```

审计报告逐项列出英式音标、例句、搭配、词族、易混词和词形的缺口；没有派生词的词可以正常留空，不以模板填充。随包 `british_ipa/supplement.json` 与更新后的抽取脚本支持离线复核全部英国字段。

---

# 离线英语词库 v6

`app/src/main/assets/vocabulary.json` 是 APP 导入本地数据库的顶层 JSON 数组。学习和资料查询无需联网或登录。

## 实际内容与边界

基础词由 6,000 增至 **20,000**，全部有来自美国英语源的 IPA、中文释义、词性与公开词频；难度覆盖 1–10 级。按 COCA 词频、中高考标签与基础生活词人工修正估计难度，不冒充经过标定的 CEFR 测验。

增强内容包括原有的核心原创内容、134 词的进阶原创例句、v3 的 102 条实用原创中英例句与搭配、本轮 v4 的 335 条原创中英语境与 459 条实用搭配、Tatoeba / ManyThings 公开中英句对、更大档案中的已校对英文句子、Open English WordNet 2025 原始英文例句、词典短语以及明确的派生与词族关系。v3 人工整理了 35 组常见词族，本轮另复核并补充 111 组词族，中文释义与 ECDICT 对照。每个词最多展示 2 条例句、4 组搭配和 4 个相关词；原创内容和中英句对优先。最终 20,000 词中，例句覆盖 **12,324** 词（中英 5,707、仅英文 6,617），搭配覆盖 **8,352** 词，派生与词族覆盖 **10,892** 词，ECDICT 词形变化覆盖 **13,059** 词，人工易混辨析覆盖 **378** 词。共 21,428 条例句、21,274 条搭配、16,658 条派生与词族项、23,982 条词形项。完整四类增强内容的词为 263 个。v1.5 另外增加 5,000 个按 ECDICT 公开词频排序的词，并从 ECDICT `exchange` 字段补充过去式、过去分词、现在分词、第三人称单数、复数、比较级和最高级。新增词条使用 ECDICT、ipa-dict、Tatoeba/ManyThings 与 Princeton WordNet 补全；原有词条的 Open English WordNet 2025 内容保留。所有数字由 `dictionary_sources.json` 自动统计，缺口留空，不填模板凑数。

**搭配**包含词典短语、复合词与原创实用组合，不是完整的基于语料频率统计的搭配词典。**派生与词族**来自 WordNet 的 `+`、OEWN 的明确 `derivation` / `pertainym` 词汇关系及人工词族。`pertainym` 仅保留词典中明确记录、有 -ly 形态证据且中文词性对应的形容词/副词对，排除 idea/psychomotor 等语义所属关系；其中文栏标注“词族关联”；不把单复数和动词时态当派生，也不靠后缀规则造词。许多功能词、数字和实物名词本来就没有适合展示的常见派生词，不保证每个词都有派生。**易混词**为原创人工辨析，不把反义词或拼写近似词冒充易混词。

`practical_enrichment_v4.json` 的 335 条原创例句中，223 条补足此前完全没有例句的词，112 条为此前只有英文的高频词增加原创中文语境；不会把新写的中文冒充上游句子的翻译。459 条原创搭配优先补齐高频词和新增语境词。111 组人工词族以固定 ECDICT 的真实词条为依据，记录为 `curated_word_family`，含常见派生及复合构词；不把一般语义相近词当成派生。50 组易混辨析均为原创，包含词性、用法或拼写差异。`meaning_corrections_v4.json` 保存 23 词原创释义修正及原因，更新后 `vocabulary.json` 的主释义使用修正内容。

Tatoeba 是社区例句语料，ManyThings 已筛选母语者与校对内容，但不能保证所有译文和词义匹配都正确。保留原始简繁中文，不自动翻译或重写；按精确单词或 ECDICT 记录的屈折词形关联例句，尚未逐条进行义项人工审核。`advanced_enrichment.json` 补充 analysis、reflect、reluctant 等常用中高阶词的原创语境。`sentence_exclusions.json` 排除人工抽查发现的明确病句，保留公开源快照原文不改写。`phrase_corrections.json` 记录抽查发现的短语译文修正，修正文字为项目原创。

新增英文侧语料取自 ManyThings 俄英档案中的英文列，仅使用英文作者与句子 ID，APP 词库不包含俄语译文（源码附有完整原始档案）。缺少中文译文时 `zh` 为空字符串，界面明确提示，不把“暂未收录中文译文”塞入翻译字段。OEWN 也包含短语式用例，构建时要求 4–24 词、具备分句线索，并排除无主语过去式开头的片段；原词或词典记录的屈折形必须实际出现。源文本不重写；英文-only 例句的逐条来源在 `english_examples_attribution.tsv`。这类自动筛选不能替代逐句人工审核。

## 重新构建

v1.9 增加独立英国 Received Pronunciation 数据。现有 20,000 词中 **18,877 词（94.385%）**与固定 `ipa-dict/data/en_UK.txt` 精确匹配；1,123 词缺失，`pronunciationUk` 保持空字符串。原始英国数据主动省略部分异读词，也不覆盖所有美式拼写；不复制美式发音、不改拼写、不自动转换。美式数据及已有释义、例句、顺序不变。英国原始快照 `tools/data/en_UK-972cd67.txt` 随源码保存，上游固定 README、GPL-3.0 原文和可复现抽取代码随 APK 保存在 `app/src/main/assets/british_ipa/`，与美国数据许可分别标注。

仅给现有词库增加或校验英国字段，无需重新运行耗时的例句整理：

```sh
python3 tools/british_pronunciations.py
python3 tools/british_pronunciations.py --check
python3 tools/prepare_vocabulary.py
python3 tools/prepare_vocabulary.py --check
```

需要 Python 3.9 及以上，只用标准库。首次执行会下载固定版本的 ECDICT、IPA、WordNet 3.0 和 OEWN 2025；后续使用 `tools/.cache/`。ManyThings 2026-02-13 的中英及俄英快照随源码保存在 `tools/data/`，避免当前 URL 更新导致旧版本无法重建。

```sh
python3 tools/build_vocabulary.py
```

提供输入文件即可完全离线重建：

```sh
python3 tools/build_vocabulary.py \
  --ecdict /path/to/ecdict.csv \
  --ipa /path/to/en_US.txt \
  --ipa-uk /path/to/en_UK.txt \
  --wordnet /path/to/WNdb-3.0.tar.gz \
  --oewn /path/to/english-wordnet-2025.xml.gz
```

可用 `--tatoeba` / `--tatoeba-english` 提供相同校验值的句对快照。脚本固定 ECDICT 与 IPA Git 提交、WordNet / OEWN 发行包及 ManyThings 快照，对全部输入核对 SHA-256；不匹配即停止。输出没有生成时间，可字节复现。生成资产包含词库、来源统计、完整许可证和逐句归属表。`contentVersion: 6` 标识新增英国音标内容；覆盖安装使用完整词库 SHA-256 触发原子更新，JSON 结构的 `schemaVersion` 仍为 1，Room schema 仍为 4。

## 来源、许可与归属

- [ECDICT](https://github.com/skywind3000/ECDICT)，固定 `bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b`：词条、词性、中文释义、COCA 词频、学段标签以及已标注词典的短语。MIT 许可。没有把其主要为英式的 phonetic 字段当作美式发音。
- [ipa-dict](https://github.com/open-dict-data/ipa-dict)，固定 `972cd67dd5f96bc5660e28070cde26e3928abcd7`：`data/en_US.txt` General American IPA，保留多种合法读音。MIT 许可。其来源链 `cmudict-ipa`、`syllabify` 和 CMUdict 的许可一并保留。
- [ipa-dict en_UK](https://github.com/open-dict-data/ipa-dict/blob/972cd67dd5f96bc5660e28070cde26e3928abcd7/README.md)，相同固定提交，独立 `data/en_UK.txt` 英国 Received Pronunciation IPA，来源 [ipacards](https://github.com/melissaboiko/ipacards)。英国数据继承 **GPL-3.0**，不是美国数据的 MIT/BSD；完整 GPL 原文、原始快照、固定上游 README 和项目抽取源代码均随包提供。原始 65,119 个拼写及音标不改写，只做精确拼写匹配。独立校验原文件 SHA-256 为 `221394caef0cf723b4f2df81a98ac33191293257b88aed5b1fb89466d3a0dc77`。
- [Princeton WordNet 3.0](https://wordnet.princeton.edu/)：[固定发行包](https://wordnetcode.princeton.edu/3.0/WNdb-3.0.tar.gz) 提供词典短语和派生关系，中文释义与 ECDICT 精确词条交集。使用 [WordNet 3.0 许可](https://wordnet.princeton.edu/license-and-commercial-use)。参考：Fellbaum, Christiane (ed.). 1998. *WordNet: An Electronic Lexical Database*. MIT Press。
- [Open English WordNet 2025](https://github.com/globalwordnet/english-wordnet/releases/tag/2025-edition)：原始英文例句、明确派生与词族关系，保留 synset ID。Open English WordNet team，基于 Princeton WordNet 3.1。[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)；OEWN 与底层 Princeton 两份许可全部保留。选择与建立索引由本项目执行，不更改上游例句文本。
- [Tatoeba](https://tatoeba.org/) 经 [ManyThings / Charles Kelly](https://www.manythings.org/anki/) 筛选的中英句对，2026-02-13 快照原始 32,028 对。[CC BY 2.0 France](https://creativecommons.org/licenses/by/2.0/fr/)。例句英文、中文不改写；每个使用句对均保存英文及中文句子 ID、作者名、原句链接与许可链接，完整清单为 `app/src/main/assets/tatoeba_attribution.tsv`，APP 例句中也保留归属。筛选与建立词条索引由本项目执行。
- [ManyThings 俄英档案](https://www.manythings.org/anki/rus-eng.zip)，同为 2026-02-13 快照，原始 536,124 对；只使用已校对英文侧。[CC BY 2.0 France](https://creativecommons.org/licenses/by/2.0/fr/)。逐句英文作者和 ID、原句链接、许可保存于词条及 `english_examples_attribution.tsv`，APP 词库不包含俄文译句（源码附有完整原始档案）。
- `core_enrichment.json`、`advanced_enrichment.json`、`practical_enrichment_v3.json`、`practical_enrichment_v4.json`、`meaning_corrections_v4.json` 和 `phrase_corrections.json`：项目原创句子、搭配、辨析、词族整理及主释义修正，CC0 1.0；人工词族条目的中文释义仍注明 ECDICT（MIT）。

许可原文与上游归属说明保存在 `tools/licenses/`，整体说明在 `tools/attribution_header.txt`。构建后的 `dictionary_licenses.txt` 保留全部必需许可。

## JSON 约定

每个词包含 `id`（与 `word` 相同）、`word`、`pronunciationUs`、`pronunciationUk`（缺失为空）、`partOfSpeech`、`meaningZh`、`difficulty`（1–10）、`rank`（公开源词频排名），以及始终存在的数组：

- `examples` / `collocations`：必需 `{ "en": "...", "zh": "..." }`。英文-only 例句的 `zh` 为空字符串（不是 null）；必须有真实归属及来源链接。搭配的 `zh` 不得为空。
- `derivatives`：必需 `{ "word": "...", "meaningZh": "..." }`。
- `confusables`：`{ "word": "...", "meaningZh": "...", "note": "..." }`。
- `wordForms`：`{ "word": "ran", "meaningZh": "过去式" }`，直接来自 ECDICT `exchange` 的明确屈折关系，最多 6 条。

增强项允许增加 `source`、`sourceUrl`、`relation`。Tatoeba 中英例句另有 `attribution`、`translationSourceUrl` 和 `licenseUrl`；英文-only 例句有 `attribution` 与 `licenseUrl`，OEWN 另保留 `synsetId`。只读取必需字段的旧解析器依然兼容。字段不写 null，词条 ID 唯一。构建校验必需字段、音标来源、核心最低规模和逐句归属。
