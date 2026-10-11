package com.nekobot.app.ui.screens.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import com.nekobot.app.data.model.Message

/** Same current-conversation scope and 80-result cap as the original search. */
internal fun companionSearchResults(messages: List<Message>, query: String, onlyMine: Boolean): List<Message> {
    val normalized = query.trim().lowercase()
    if (normalized.isBlank() && !onlyMine) return emptyList()
    return messages.filter {
        it.role != "system" && (!onlyMine || it.role == "user") && it.displayContent.lowercase().contains(normalized)
    }.take(80)
}

@Composable
internal fun CompanionMessageSearch(
    messages: List<Message>, query: String, onQueryChange: (String) -> Unit,
    characterName: String?, onDismiss: () -> Unit, onResultClick: (Message) -> Unit
) {
    var onlyMine by remember { mutableStateOf(false) }
    val results = remember(messages, query, onlyMine) { companionSearchResults(messages, query, onlyMine) }
    CompanionReadingDialog("查找聊天记录", onDismiss) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(query, onQueryChange, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("搜索当前会话") },
                leadingIcon = { Icon(Icons.Outlined.Search, null, tint = MaterialTheme.colorScheme.primary) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Outlined.Cancel, "清空搜索") } },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(!onlyMine, { onlyMine = false }, label = { Text("全部") }, colors = companionChoiceColors(), modifier = Modifier.weight(1f).heightIn(min = 44.dp))
                FilterChip(onlyMine, { onlyMine = true }, label = { Text("只看我发送的") }, colors = companionChoiceColors(), modifier = Modifier.weight(1f).heightIn(min = 44.dp))
            }
            Text("当前会话 · ${results.size} 条结果", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            when {
                query.isBlank() && !onlyMine -> item { CompanionReadingHint("输入关键词，查找这个会话里的原话。") }
                results.isEmpty() -> item { CompanionReadingHint("没有找到匹配的消息。试试其他关键词。") }
                else -> {
                    itemsIndexed(results, key = { index, message -> chatMessageItemKey(index, message) }) { _, message ->
                        CompanionMessageExcerpt(if (message.isUser) "我" else characterName?.takeIf(String::isNotBlank) ?: "AI",
                            message.timestamp, searchPreview(message.displayContent, query), query, onClick = {
                                onResultClick(message)
                                onDismiss()
                            })
                    }
                    item { CompanionReadingHint("点开结果，回到对应的原话。") }
                }
            }
        }
    }
}

private fun searchPreview(content: String, query: String): String {
    val keyword = query.trim()
    val index = content.indexOf(keyword, ignoreCase = true)
    if (keyword.isBlank() || index < 0) return content.take(160)
    val start = maxOf(0, index - 36)
    val end = minOf(content.length, index + keyword.length + 100)
    return (if (start > 0) "…" else "") + content.substring(start, end).replace("\n", " ").trim() + (if (end < content.length) "…" else "")
}

@Composable
internal fun CompanionReadingHint(text: String) {
    Text(text, Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CompanionMessageExcerpt(
    sender: String, timestamp: String?, content: String, keyword: String = "", onClick: (() -> Unit)? = null
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        Text(sender.take(1), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(sender, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        timestamp?.takeIf(String::isNotBlank)?.let {
                            Text(readingTimestamp(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    val normalized = keyword.trim()
                    val highlight = SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    val annotated = buildAnnotatedString {
                        var offset = 0
                        while (normalized.isNotEmpty()) {
                            val index = content.indexOf(normalized, offset, ignoreCase = true)
                            if (index < 0) break
                            append(content.substring(offset, index))
                            withStyle(highlight) { append(content.substring(index, index + normalized.length)) }
                            offset = index + normalized.length
                        }
                        append(content.substring(offset))
                    }
                    if (onClick == null) SelectionContainer { Text(annotated, style = MaterialTheme.typography.bodyLarge) }
                    else Text(annotated, style = MaterialTheme.typography.bodyLarge)
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}

private fun readingTimestamp(raw: String): String {
    val text = raw.replace('T', ' ')
    return if (text.matches(Regex("^\\d{4}-\\d{2}-\\d{2} .*"))) text.drop(5).take(11) else text.take(19)
}

internal fun JsonObject.favoriteText(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.let {
    runCatching { it.asString }.getOrElse { _ -> it.toString() }
}

@Composable
internal fun CompanionFavoritesReading(
    favorites: List<JsonObject>, loading: Boolean, selected: JsonObject?, characterName: String?,
    onBack: () -> Unit, onSelect: (JsonObject) -> Unit, onDelete: (JsonObject) -> Unit
) {
    val messages = selected?.getAsJsonArray("messages")?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }.orEmpty()
    CompanionReadingDialog(selected?.favoriteText("title") ?: selected?.favoriteText("name") ?: "收藏的消息",
        onBack = onBack, subtitle = if (selected != null) "${messages.size} 条收藏消息" else null) {
        if (loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (selected == null) {
                item { CompanionReadingHint("当前会话的收藏夹") }
                if (favorites.isEmpty()) item { CompanionReadingHint("还没有收藏的消息。长按聊天消息可添加收藏。") }
                itemsIndexed(favorites, key = { index, item -> item.favoriteText("id")?.takeIf(String::isNotBlank)
                    ?: item.favoriteText("collection_id")?.takeIf(String::isNotBlank) ?: "favorite:$index" }) { _, collection ->
                    CompanionFavoriteFolder(collection, { onSelect(collection) }, { onDelete(collection) })
                }
                if (favorites.isNotEmpty()) item { CompanionReadingHint("点开收藏夹，查看完整消息。") }
            } else {
                if (messages.isEmpty()) item { CompanionReadingHint("这个收藏夹暂时没有消息内容。") }
                itemsIndexed(messages, key = { index, item -> "${item.favoriteText("message_id") ?: item.favoriteText("id")}:$index" }) { _, msg ->
                    val role = msg.favoriteText("role").orEmpty().lowercase()
                    val sender = when (role) {
                        "user", "human" -> "我"
                        "assistant", "ai" -> characterName?.takeIf(String::isNotBlank) ?: "AI"
                        "system" -> "系统"
                        else -> msg.favoriteText("sender") ?: role.ifBlank { "消息" }
                    }
                    CompanionMessageExcerpt(sender, msg.favoriteText("timestamp"), msg.favoriteText("content").orEmpty())
                }
                if (messages.isNotEmpty()) item { CompanionReadingHint("长按文字可选择复制。") }
            }
        }
    }
}

@Composable
private fun CompanionFavoriteFolder(collection: JsonObject, onClick: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val title = collection.favoriteText("title") ?: collection.favoriteText("name") ?: "未命名收藏"
    val messages = collection.getAsJsonArray("messages")?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }.orEmpty()
    val count = if (messages.isNotEmpty()) messages.size else collection.getAsJsonArray("message_ids")?.size() ?: 0
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CompanionPreferenceIcon(Icons.Outlined.FolderOpen)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(collection.favoriteText("created_at")?.take(10).orEmpty(), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(messages.firstOrNull()?.favoriteText("content").orEmpty(), modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("$count 条消息", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "$title 的更多操作") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("删除收藏", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onDelete() })
                    }
                }
            }
            HorizontalDivider(Modifier.padding(start = 70.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}
