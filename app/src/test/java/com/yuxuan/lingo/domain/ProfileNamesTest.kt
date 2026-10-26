package com.yuxuan.lingo.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileNamesTest {
    @Test fun inputLimitKeepsCompleteSupplementaryCharactersAtTheBoundary() {
        val boundary = "文".repeat(29) + "😀"
        assertEquals(boundary, ProfileNames.limit(boundary + "末"))
        assertEquals("😀".repeat(30), ProfileNames.limit("😀".repeat(31)))
    }

    @Test fun inputLimitPreservesSpacesAndAlreadyValidNames() {
        assertEquals("  用户😀  ", ProfileNames.limit("  用户😀  "))
        assertEquals("", ProfileNames.limit(""))
    }

    @Test fun savingTrimsAndUsesTheSameLimitWithABlankNameFallback() {
        assertEquals("😀".repeat(30), ProfileNames.normalize(" \n" + "😀".repeat(31) + "\t "))
        assertEquals("中文名字", ProfileNames.normalize(" 中文名字 "))
        assertEquals("学习者", ProfileNames.normalize(" \t\n\u2003"))
    }

    @Test fun initialsUseAWholeCodePointAndHandleBlankNames() {
        assertEquals("😀", ProfileNames.initial(" 😀用户 "))
        assertEquals("学", ProfileNames.initial("学习者"))
        assertEquals("知", ProfileNames.initial(""))
        assertEquals("知", ProfileNames.initial(" \u2003"))
    }
}
