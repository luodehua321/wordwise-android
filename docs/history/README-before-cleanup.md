# 知词（Android）

本项目只维护 Android 版本。按用户要求，2026-10-26 已删除 iOS 工程、Swift 源码、iOS 截图、测试与安装脚本，不再构建 iOS。

最新安卓安装包：`dist/知词-2.0.10-arm64-v8a-release.apk`。使用原有证书签名，覆盖更新时保留学习记录。不要删除或重新生成 `keystore/wordwise-debug.keystore`。

词库权威数据在 `app/src/main/assets/vocabulary.json`，来源及许可保留在配套资产中。修改词库后运行 `python3 tools/prepare_vocabulary.py`，再用 `python3 tools/prepare_vocabulary.py --check` 校验预编译数据库和二进制缓存。单词 ID 与学习记录关联，不要随意变更。

本轮词库补充的实际数量及剩余空缺见 `VALIDATION-v2.0.10.md`；性能结果及后续优化方法见 `PERFORMANCE-v2.0.10.md`。未将长尾词空缺标记为全部完成。

Android 构建使用 JDK 17；正式构建执行 `./gradlew :app:assembleRelease`。平台最低版本为 Android 8（API 26），安装包架构为 arm64-v8a。
