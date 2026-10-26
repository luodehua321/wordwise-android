package com.yuxuan.lingo.ui

/** The source dictionary keeps variants; each learning surface shows one primary reading per accent. */
object PronunciationDisplay {
    /** A fallback always names its real accent, never masquerading as the selected one. */
    fun related(us: String, uk: String, accent: PronunciationAccent): String {
        val preferred = if (accent == PronunciationAccent.US) us else uk
        if (preferred.isNotBlank()) return primary(preferred)
        val fallback = if (accent == PronunciationAccent.US) uk else us
        if (fallback.isBlank()) return ""
        return (if (accent == PronunciationAccent.US) "英 " else "美 ") + primary(fallback)
    }
    private val delimited = Regex("/([^/]+)/")
    fun primary(source: String, spelling: String = ""): String {
        // CMU lists the past tense first; the learning card is the dictionary headword.
        val preferred = when (spelling) {
            "read" -> "/ˈɹid/"
            "wind" -> "/ˈwɪnd/"
            else -> null
        }
        if (preferred != null && delimited.findAll(source).any { it.value == preferred }) return displaySymbols(preferred)
        val ipa = delimited.findAll(source).map { it.groupValues[1].split(',', ';', '，').firstOrNull { value -> value.isNotBlank() }.orEmpty().trim() }.firstOrNull { it.isNotEmpty() }
        val reading = if (ipa != null) "/$ipa/" else
            source.split(',', ';', '，', '\n').firstOrNull { it.isNotBlank() }.orEmpty().trim()
        return displaySymbols(reading)
    }

    private fun displaySymbols(reading: String): String = reading
        .replace('ɹ', 'r').replace('ɫ', 'l')
        .replace("ɚ", "ər").replace("ɝ", "ɜːr")
}
