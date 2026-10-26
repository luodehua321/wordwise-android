package com.yuxuan.lingo.domain

import org.junit.Assert.*
import org.junit.Test

class LearningLevelTest {
    @Test fun labelAlwaysUsesOneDecimalAndBandsMatchTheDisplayedValue() {
        assertEquals("1.0 级", LearningLevel.describe(1.0).label)
        assertEquals("5.4 级", LearningLevel.describe(5.41).label)
        assertEquals("10.0 级", LearningLevel.describe(10.0).label)
        val aroundBoundary = LearningLevel.describe(4.48)
        assertEquals("4.5 级", aroundBoundary.label)
        assertEquals("表达拓展", aroundBoundary.stage)
        assertEquals(LearningLevel.describe(4.5).stage, aroundBoundary.stage)
    }

    @Test fun learningStagesExplainConcreteVocabularyScopeWithoutUnvalidatedExamMappings() {
        val stages = (1..10).map { LearningLevel.describe(it.toDouble()) }
        assertEquals(10, stages.map { it.stage }.distinct().size)
        assertEquals(10, stages.map { it.description }.distinct().size)
        assertTrue(stages[0].description.contains("简短句子"))
        assertTrue(stages[4].description.contains("新闻"))
        assertTrue(stages[8].description.contains("专业表达"))
        assertTrue(stages.none { "CEFR" in it.description || "大学" in it.description || "雅思" in it.description })
    }

    @Test fun invalidAndOutOfRangeValuesHaveSafeConsistentDescriptions() {
        assertEquals("1.0 级", LearningLevel.describe(Double.NaN).label)
        assertEquals("1.0 级", LearningLevel.describe(-2.0).label)
        assertEquals("10.0 级", LearningLevel.describe(12.0).label)
    }
}
