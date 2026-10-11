package com.nekobot.app.ui.screens.chat

import android.animation.ValueAnimator
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nekobot.app.R

internal fun appearanceMotionAllowed(): Boolean =
    Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()

private data class ChatTailLayout(val last: Int, val visibleLast: Int?, val bottom: Int,
    val viewportEnd: Int, val atBottom: Boolean, val scrolling: Boolean)

/** Growing bubbles follow the viewport only while the reader stays at the bottom. */
@Composable
internal fun dynamicChatFollowModifier(list: LazyListState, enabled: Boolean): Modifier {
    var following by remember(list, enabled) { mutableStateOf(!list.canScrollForward) }
    val connection = remember(list, enabled) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (enabled && source == NestedScrollSource.UserInput && available.y > 0f) following = false
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(list, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow {
            val layout = list.layoutInfo
            val tail = layout.visibleItemsInfo.lastOrNull()
            ChatTailLayout(layout.totalItemsCount - 1, tail?.index,
                (tail?.offset ?: 0) + (tail?.size ?: 0) + layout.afterContentPadding,
                layout.viewportEndOffset, !list.canScrollForward, list.isScrollInProgress)
        }.collect { tail ->
            if (tail.atBottom && !tail.scrolling) following = true
            if (following && !tail.scrolling && tail.visibleLast == tail.last) {
                val overflow = tail.bottom - tail.viewportEnd
                if (overflow > 1) runCatching { list.scrollBy(overflow.toFloat()) }
            }
        }
    }
    return if (enabled) Modifier.nestedScroll(connection) else Modifier
}

@Composable
internal fun messageEntranceModifier(
    registry: MessageMotionRegistry?, key: String?, segment: Int,
    timestamp: String?, enabled: Boolean, isUser: Boolean
): Modifier {
    val motion = enabled && appearanceMotionAllowed()
    val animate = remember(registry, key, segment) {
        if (key == null) false else registry?.claim(key, segment, timestamp, motion) == true
    }
    val progress = remember(registry, key, segment) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(registry, key, segment) {
        if (animate) progress.animateTo(1f, tween(200, easing = FastOutSlowInEasing))
    }
    if (!motion) return Modifier
    val push = with(LocalDensity.current) { 10.dp.toPx() }
    return Modifier.graphicsLayer {
        val value = progress.value
        scaleX = 0.86f + value * 0.14f
        scaleY = 0.86f + value * 0.14f
        translationY = (1f - value) * push
        alpha = 0.6f + value * 0.4f
        transformOrigin = TransformOrigin(if (isUser) 1f else 0f, 1f)
    }.animateContentSize(tween(140))
}

/** Alternate renderer only. The original reasoning components remain intact. */
@Composable
internal fun DynamicThinkingSection(identity: String?, text: String, thinking: Boolean) {
    var expanded by remember(identity) { mutableStateOf(thinking) }
    LaunchedEffect(identity, thinking) { expanded = thinking }
    val scroll = rememberScrollState()
    val slop = with(LocalDensity.current) { 8.dp.toPx().toInt() }
    LaunchedEffect(identity, expanded, thinking) {
        if (!expanded || !thinking) return@LaunchedEffect
        withFrameNanos { }
        runCatching { scroll.scrollTo(scroll.maxValue) }
        var following = true
        var previousValue = scroll.value
        var previousMax = scroll.maxValue
        snapshotFlow { scroll.value to scroll.maxValue }.collect { (value, max) ->
            if (value < previousValue && max >= previousMax) following = false
            if (value > previousValue && max - value <= slop) following = true
            if (following && max > previousMax) runCatching { scroll.scrollTo(max) }
            previousValue = scroll.value
            previousMax = max
        }
    }
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).testTag("dynamic_thinking"),
        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ThinkingMotionDots(thinking, accent)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(if (thinking) R.string.chat_thinking else R.string.chat_reasoning_process),
                    Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    stringResource(if (expanded) R.string.common_collapse else R.string.common_expand),
                    Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(expanded, enter = expandVertically(tween(180)), exit = shrinkVertically(tween(180))) {
                Row(Modifier.padding(start = 16.dp, end = 12.dp, bottom = 12.dp).height(IntrinsicSize.Min)) {
                    Box(Modifier.width(2.dp).fillMaxHeight().background(accent.copy(alpha = 0.25f)))
                    Spacer(Modifier.width(12.dp))
                    SelectionContainer {
                        Text(text, Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(scroll),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingMotionDots(active: Boolean, color: Color) {
    val animate = active && appearanceMotionAllowed()
    val transition = if (animate) rememberInfiniteTransition(label = "thinking_dots") else null
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val opacity = if (transition != null) {
                val value by transition.animateFloat(0.3f, 1f,
                    infiniteRepeatable(tween(480, easing = LinearEasing), RepeatMode.Reverse, StartOffset(index * 160)),
                    label = "thinking_dot_$index")
                value
            } else 0.65f
            Box(Modifier.size(4.dp).background(color.copy(alpha = opacity), CircleShape))
        }
    }
}
