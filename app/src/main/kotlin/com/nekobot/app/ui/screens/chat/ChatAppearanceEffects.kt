package com.nekobot.app.ui.screens.chat

import android.animation.ValueAnimator
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
internal fun DynamicThinkingSection(identity: String?, text: String, thinking: Boolean, label: String? = null) {
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
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).testTag("dynamic_thinking")
    ) {
        // The surface is transparent; the small label retains a full touch target.
        Row(
            Modifier.heightIn(min = 48.dp)
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ThinkingBreathingLabel(thinking, Modifier.weight(1f, fill = false), label)
            Spacer(Modifier.width(6.dp))
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                stringResource(if (expanded) R.string.common_collapse else R.string.common_expand),
                Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(expanded, enter = expandVertically(tween(180)), exit = shrinkVertically(tween(180))) {
            Row(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp).height(IntrinsicSize.Min)) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(accent.copy(alpha = 0.25f)))
                Spacer(Modifier.width(10.dp))
                SelectionContainer {
                    Text(text, Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(scroll),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ThinkingBreathingLabel(active: Boolean, modifier: Modifier = Modifier, label: String? = null) {
    val animate = active && appearanceMotionAllowed()
    val opacity = if (animate) {
        val transition = rememberInfiniteTransition(label = "thinking_text")
        transition.animateFloat(
            initialValue = 1f, targetValue = 0.68f,
            animationSpec = infiniteRepeatable(
                tween(1400, easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)),
                RepeatMode.Reverse
            ),
            label = "thinking_text_opacity"
        )
    } else null
    Text(
        text = label ?: stringResource(if (active) R.string.chat_dynamic_thinking else R.string.chat_reasoning_process),
        modifier = modifier.graphicsLayer { alpha = opacity?.value ?: 1f },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
