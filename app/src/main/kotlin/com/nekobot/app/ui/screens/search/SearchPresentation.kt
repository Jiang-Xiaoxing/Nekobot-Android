package com.nekobot.app.ui.screens.search

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

internal fun searchSnippet(text: String, query: String, length: Int = 100): String {
    val singleLine = text.replace(Regex("\\s+"), " ").trim()
    val index = singleLine.indexOf(query.trim(), ignoreCase = true)
    val start = if (index < 0) 0 else (index - length / 4).coerceAtLeast(0)
    return (if (start > 0) "…" else "") + singleLine.drop(start).take(length) +
        if (singleLine.length > start + length) "…" else ""
}

internal fun highlightedSearchText(text: String, query: String, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    val needle = query.trim()
    if (needle.isEmpty()) { append(text); return@buildAnnotatedString }
    var cursor = 0
    while (cursor < text.length) {
        val index = text.indexOf(needle, cursor, ignoreCase = true)
        if (index < 0) { append(text.substring(cursor)); break }
        append(text.substring(cursor, index))
        withStyle(style) { append(text.substring(index, index + needle.length)) }
        cursor = index + needle.length
    }
}
