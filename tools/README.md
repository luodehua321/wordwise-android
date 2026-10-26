# 项目工具

当前应用 2.0.11，词库内容版本 20，共 28,167 词。以 `app/src/main/assets/vocabulary.json` 和 `dictionary_sources.json` 为当前权威状态。

## 日常入口

```sh
python3 tools/prepare_vocabulary.py          # 从当前词库生成数据库与二进制缓存
python3 tools/prepare_vocabulary.py --check  # 逐项核对缓存、schema 和哈希
python3 tools/check_prepared_binary.py       # 独立二进制解码校验
python3 tools/test_vocabulary.py             # 词库回归测试
```

以上步骤仅使用 Python 标准库，无需下载原始词典。Android 的 preBuild 自动执行缓存生成任务；Git 不提交可重建的数据库与二进制。

## 内容修订和审计

`authored_*`、`*_corrections*`、`internal_content_*` 和 `public_*` 是有来源、审核依据或历史哈希引用的输入，保留原名称，以免破坏重放和署名。

`apply_*`、`complete_*` 和 `build_vocabulary.py` 多为特定阶段的写入器；不要按文件版本号依次执行。旧写入器可能覆盖当前内容或元数据。优先维护当前权威 JSON，按需审查对应脚本再执行。完整历史说明见 [工具历史](../docs/history/vocabulary-tools-history.md)，其中旧版文件路径和统计仅用于追溯。

`audit_vocabulary.py`、`audit_levels.py`、`review_vocabulary.py` 提供专项审计；需要的外部源和参数以脚本帮助或文件头为准。原始输入的许可位于 `licenses/`，发布资产另有完整来源清单。

## 本地保留但不上传的源快照

`data/*.jsonl.gz`、`data/*.zip`、`data/public_ipa_v15/` 下的原始全量 IPA TXT/TSV、`data/open-dictionary-v2.0-matched.json` 和 `data/v209-*` / `v210-*` 以及 `data/public_ipa_v15/kaikki/` 的逐词下载缓存是大型原始快照、阶段备份或可再获取的缓存，保留本机且通过根 `.gitignore` 排除。当前应用构建不需要这些文件。从零重做上游抽取时，需要从 `dictionary_sources.json` 和相关脚本记录的固定来源重新获取，并核对哈希。

## 设计与商店素材

```sh
python3 -m pip install -r tools/requirements-design.txt
python3 tools/generate_app_icon.py
python3 tools/build_store_assets.py
```

Logo 输出在 `docs/brand/`，安卓图标同步写入 `app/src/main/res/`。商店截图取自 `store/screenshots/raw/`；生成工具需要 macOS 系统中文字体，不会重新采集功能截图。`capture_store_flow.py` 用于设备截图，`measure_android_runtime.py` 用于独立模拟器性能采样。
