package com.nekobot.app.data.local

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken

/** 按指定表读取单行；跳过其他表，调用方决定恢复顺序，不能依赖归档的 JSON 字段顺序。 */
internal fun forEachPortableRow(raw: ByteArray, table: String, consume: (JsonObject) -> Unit) {
    JsonReader(raw.inputStream().reader(Charsets.UTF_8)).use { reader ->
        var found = false
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() != "tables") { reader.skipValue(); continue }
            require(!found) { "类别数据含重复 tables" }
            found = true
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() != table) { reader.skipValue(); continue }
                reader.beginArray()
                while (reader.hasNext()) consume(JsonParser.parseReader(reader).asJsonObject)
                reader.endArray()
            }
            reader.endObject()
        }
        reader.endObject()
        require(found && reader.peek() == JsonToken.END_DOCUMENT) { "类别数据格式无效" }
    }
}
