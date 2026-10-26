# 当前启动缓存：V03（v1.16）

原始JSON和dictionary_sources.json仍为权威输入，Room仍为v4。V03魔数LINGOV03，包含原始来源哈希、词条数、唯一UTF-8字符串表及全部字段的整数引用；相同字符串在运行时共享。原格式V02缓存不会误读，使用现有Room JSON路径回退。所有资产必须一起重建：

```sh
python3 tools/prepare_vocabulary.py
python3 tools/prepare_vocabulary.py --check
python3 tools/check_prepared_binary.py
```

独立解码器逐字段核对所有源词条的运行时字段。文件SHA、schema身份和全部payload校验保持。完整词库数量不缩减。

以下为旧版本历史说明，格式数字以本节为准。

---

# 启动词库预编译

`vocabulary.json` 与 `dictionary_sources.json` 仍是权威输入，数据来源、许可和词库哈希的含义没有改变。新增文件均由脚本派生，不包含用户资料、学习记录或已生成的校准题目。

每次生成词库或修改 Room schema 后运行：

```sh
python3 tools/prepare_vocabulary.py
python3 tools/prepare_vocabulary.py --check
```

脚本仅使用 Python 标准库，不访问网络。它校验原始词库 SHA-256，使用最新 Room 导出 schema 生成 `prepared_vocabulary.db`，并将全部 Word 字段编码为 `prepared_vocabulary.bin`。独立的 `prepared_vocabulary.json` 保存来源哈希、Room 版本/identity hash、词条数量及两个派生文件的 SHA-256。`--check` 对照全部 20,000 个源词条与 SQLite 内容、二进制内容逐一验证，不修改文件。

v1.9 的缓存格式为 `formatVersion: 2`、魔数 `LINGOV02`，每个词条在既有列表字段后追加 `pronunciationUk` 字符串。旧格式缓存不再直接读取，会从权威 Room JSON 回退；旧 JSON 没有英国字段时按空字符串解析。Room schema 仍为 4，不需要迁移数据库结构。`Word` 新字段位于末尾并提供空字符串默认值，原有位置参数保持兼容。英国数据使权威 JSON 哈希变化，覆盖安装时仍按既有事务 upsert 更新词条，保留用户、答题、复习、学习时长和校准资料。

首次安装时，Room 在 IO 线程复制已预填充的数据库，并在读取流结束时校验其 SHA-256；不再执行 20,000 次 JSON 解码和数据库 upsert。用户资料和校准仍按当前设备时钟和随机种子创建。已有数据库继续使用既有迁移，不覆盖已有记录，不使用 destructive migration。原始词库哈希变化时仍执行原来的校验和原子更新流程，`UPDATE` 不会触发父词条删除和外键级联。

首次和后续打开时，都使用完整二进制缓存直接构造全部词条；校准和随机抽样始终可见完整词库，没有通过缩减候选词数量来换取速度。缓存读取同时校验来源哈希、条数和完整文件 SHA-256。缓存缺失、过期、损坏，或旧数据库保留额外词条时，回退到数据库中权威 JSON payload；采用单次流式 Cursor，避免重复 OFFSET 排序和一次性装入所有 JSON 字符串。

代价是安装包增加一份压缩 SQLite 数据库和一份压缩二进制词库，解压后合计约 34 MB；安装时需相应存储空间。运行时仍会为完整词库分配 Word 对象，首次启动后的校准、学习调度与已有版本保持相同的完整题库范围。

验证：数据仪器测试 `preparedVocabularyContainsEveryCompleteSourceRecord` 对照每个数据库 payload 和缓存里的完整 Word，并记录 `LingoStartup` 首次初始化用时；`dictionaryHashUpdatePreservesAllLearningAndCalibrationState` 检查旧哈希更新后的用户资料、答题、复习进度、学习时长与校准会话完全保留，同时记录慢速 JSON 更新对照耗时。测试使用随机命名数据库，不读取真实用户数据库。可在测试后查看：

```sh
adb logcat -d -s LingoStartup:I
```

该耗时为仓储首次准备完整词库的时间；不包含安装 APK、进程启动和 Compose 首帧。
