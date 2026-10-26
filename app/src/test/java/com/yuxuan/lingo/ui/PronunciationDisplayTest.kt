package com.yuxuan.lingo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PronunciationDisplayTest {
    @Test fun relatedReadingsFollowAccentAndExplicitlyLabelFallbacks() {
        assertEquals("/kɑr/", PronunciationDisplay.related("/kɑr/", "/kɑː/", PronunciationAccent.US))
        assertEquals("/kɑː/", PronunciationDisplay.related("/kɑr/", "/kɑː/", PronunciationAccent.UK))
        assertEquals("英 /kɑː/", PronunciationDisplay.related("", "/kɑː/", PronunciationAccent.US))
        assertEquals("美 /kɑr/", PronunciationDisplay.related("/kɑr/", "", PronunciationAccent.UK))
        assertEquals("", PronunciationDisplay.related("", "", PronunciationAccent.US))
    }
    @Test fun readHeadwordDoesNotShowThePastTenseAsItsPrimaryReading() {
        assertEquals("/ˈrid/", PronunciationDisplay.primary("/ˈɹɛd/, /ˈɹid/", "read"))
        assertEquals("/ˈrɛd/", PronunciationDisplay.primary("/ˈɹɛd/"))
    }

    @Test fun windHeadwordSelectsTheCommonNounReadingWithoutAlteringSourceSymbols() {
        assertEquals("/ˈwɪnd/", PronunciationDisplay.primary("/ˈwaɪnd/, /ˈwɪnd/", "wind"))
        assertEquals("/ˈwaɪnd/", PronunciationDisplay.primary("/ˈwaɪnd/", "wind"))
    }

    @Test fun mainlandDisplayUsesConventionalRAndLWithoutChangingVowelsOrStress() {
        assertEquals("/rɪˈtɜrn/", PronunciationDisplay.primary("/ɹɪˈtɜɹn/"))
        assertEquals("/kɑr/", PronunciationDisplay.primary("/kɑr/"))
        assertEquals("/fɪl/", PronunciationDisplay.primary("/fɪɫ/"))
    }

    @Test fun rhoticVowelsUseFamiliarDictionaryRSpellingWithoutDroppingTheSound() {
        assertEquals("/ˈtiːtʃər/", PronunciationDisplay.primary("/ˈtiːtʃɚ/"))
        assertEquals("/ˈwɜːrd/", PronunciationDisplay.primary("/ˈwɝd/"))
        assertEquals("/ˌsaɪbərsɪˈkjʊrəti/", PronunciationDisplay.primary("/ˌsaɪbɚsɪˈkjʊrəti/"))
        assertEquals("/kɑː/", PronunciationDisplay.primary("/kɑː/"))
    }

    @Test fun dictionaryAlternativesDisplayOnlyThePrimaryReading() {
        assertEquals("/ˈrili/", PronunciationDisplay.primary("/ˈɹiɫi/, /ˈɹɪɫi/"))
        assertEquals("/həˈloʊ/", PronunciationDisplay.primary(" /həˈɫoʊ/, /hɛˈɫoʊ/ "))
    }
    @Test fun britishVariantsInsideOnePairOfSlashesAlsoDisplayOnlyOneReading() {
        assertEquals("/ðə/", PronunciationDisplay.primary("/ðə, ði/"))
        assertEquals("/ænd/", PronunciationDisplay.primary("/ænd, ənd, ən/"))
    }
    @Test fun singlePronunciationsKeepTheirStressAndVowelSymbols() {
        assertEquals("/ˌkærəktəˈrɪstɪkli/", PronunciationDisplay.primary("/ˌkæɹəktəˈɹɪstɪkli/"))
    }
    @Test fun legacyUnwrappedReadingsAndMissingValuesAreSafe() {
        assertEquals("rɛd", PronunciationDisplay.primary("ɹɛd; rɛd"))
        assertEquals("", PronunciationDisplay.primary("  "))
    }
}
