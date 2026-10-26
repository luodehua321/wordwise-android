package com.yuxuan.lingo.domain

/** Name limits count Unicode code points, so a supplementary character is never split. */
object ProfileNames {
    private const val MAX_CODE_POINTS = 30

    /** Preserve spaces while editing; normalization happens when the profile is saved. */
    fun limit(value: String): String {
        if (value.codePointCount(0, value.length) <= MAX_CODE_POINTS) return value
        return value.substring(0, value.offsetByCodePoints(0, MAX_CODE_POINTS))
    }

    fun normalize(value: String): String = limit(value.trim()).ifBlank { "学习者" }

    fun initial(value: String): String {
        val name = value.trim()
        if (name.isEmpty()) return "知"
        return name.substring(0, name.offsetByCodePoints(0, 1))
    }
}
