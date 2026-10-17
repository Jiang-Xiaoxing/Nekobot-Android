package com.nekobot.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class SingleRoleStringsTest {
    @Test fun everySupportedLocaleHasAllStringsAndTheSameFormatArguments() {
        val root = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        fun read(locale: String): Map<String, String> {
            val file = File(root, "$locale/strings_single_role_review.xml")
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            return buildMap {
                for (index in 0 until nodes.length) {
                    val node = nodes.item(index)
                    val name = node.attributes.getNamedItem("name").nodeValue
                    assertTrue("$locale duplicate $name", name !in this)
                    put(name, node.textContent)
                }
            }
        }
        val original = read("values")
        val argument = Regex("%(?:\\d+\\$)?[\\d.]*[a-zA-Z]")
        for (locale in listOf("values-en", "values-ja", "values-ko", "values-zh-rTW")) {
            val translated = read(locale)
            assertEquals("$locale keys", original.keys, translated.keys)
            original.forEach { (name, value) ->
                val text = translated.getValue(name)
                assertTrue("$locale empty $name", text.isNotBlank())
                assertEquals("$locale arguments for $name", argument.findAll(value).map { it.value }.sorted().toList(),
                    argument.findAll(text).map { it.value }.sorted().toList())
            }
        }
    }
}
