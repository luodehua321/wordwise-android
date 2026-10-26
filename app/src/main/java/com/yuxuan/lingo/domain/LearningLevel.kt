package com.yuxuan.lingo.domain

import java.util.Locale
import kotlin.math.round

data class LevelDescriptor(val label: String, val stage: String, val description: String)

/** App-specific vocabulary bands. These are learning guides, not standardized exam results. */
object LearningLevel {
    fun describe(level: Double): LevelDescriptor {
        val safe = if (level.isFinite()) level.coerceIn(1.0, 10.0) else 1.0
        val displayed = round(safe * 10.0) / 10.0
        val (stage, description) = when (displayed.toInt().coerceIn(1, 10)) {
            1 -> "基础起步" to "正在建立人物、数字、时间和常见物品的基础词汇。先把单词放进简短句子中，反复熟悉最常用的意思。"
            2 -> "生活基础" to "已开始积累饮食、出行、购物等生活用词。接下来可练习简单问答，留意同一个词在不同搭配中的意思。"
            3 -> "日常应用" to "词汇开始覆盖较多日常场景，能够尝试读懂简短故事和实用信息。重点补充常见动词搭配、词形和一词多义。"
            4 -> "表达拓展" to "正在从具体事物扩展到情绪、观点和事件描述。可以练习短篇文章，比较近义词在语境中的区别。"
            5 -> "独立阅读" to "词汇已延伸到一般新闻、工作交流和常见社会话题。适合增加连续阅读，遇到熟词时也留意它的新义项。"
            6 -> "话题深化" to "正在积累更抽象的概念及较长文章中的用词。可围绕感兴趣的话题阅读，重点理解词族、固定搭配和转折关系。"
            7 -> "进阶阅读" to "词汇开始覆盖评论、论证和部分学术话题。接下来可加强精确用词，辨别褒贬色彩、正式程度及近义词差别。"
            8 -> "复杂语境" to "正在接触较低频的抽象词与复杂表达。适合结合原版长文巩固，避免只记中文释义而忽略句法和搭配。"
            9 -> "高阶拓展" to "词汇范围正向低频词和专业表达延伸。可选择具体领域深入阅读，区分专业含义、一般含义及不常用义项。"
            else -> "深入积累" to "已进入本词库的高难度学习带。后续重点是低频词的准确理解、语体差别和专业语境运用，而不只是认识更多词。"
        }
        return LevelDescriptor(String.format(Locale.ROOT, "%.1f 级", displayed), stage, description)
    }
}
