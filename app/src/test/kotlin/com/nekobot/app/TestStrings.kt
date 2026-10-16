package com.nekobot.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Reads production text for pure JVM formatting tests without an Android runtime. */
private val defaultStrings: Map<Int, String> by lazy {
    val root = listOf(File("src/main/res/values"), File("app/src/main/res/values")).first { it.isDirectory }
    val ids = R.string::class.java.fields.associate { it.name to it.getInt(null) }
    buildMap {
        root.listFiles { file -> file.extension == "xml" }.orEmpty().forEach { file ->
            val entries = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            for (index in 0 until entries.length) {
                val entry = entries.item(index)
                val id = ids[entry.attributes.getNamedItem("name").nodeValue] ?: continue
                put(id, entry.textContent.replace("\\n", "\n").replace("\\'", "'").replace("\\\"", "\""))
            }
        }
    }
}

internal fun testString(id: Int): String = defaultStrings.getValue(id)
