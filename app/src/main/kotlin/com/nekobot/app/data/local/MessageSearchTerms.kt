package com.nekobot.app.data.local

import java.util.Locale

/** 用字符二/三元组保留中文和词中子串匹配，FTS 查询只包含安全的 ASCII token。 */
internal object MessageSearchTerms {
    private fun points(text: String) = text.lowercase(Locale.ROOT).codePoints().toArray()
    private fun token(points: IntArray, start: Int, size: Int) =
        (if (size == 2) "b" else "t") + (start until start + size).joinToString("x") { points[it].toString(16) }

    fun document(text: String): String {
        val points = points(text)
        val tokens = linkedSetOf<String>()
        for (size in 2..3) for (index in 0..points.size - size) tokens.add(token(points, index, size))
        return tokens.joinToString(" ")
    }

    fun query(text: String): String? {
        val points = points(text)
        if (points.size < 2) return null
        val size = if (points.size == 2) 2 else 3
        return (0..points.size - size).map { token(points, it, size) }
            .distinct().take(64).joinToString(" ")
    }
}
