package com.nekobot.app.ui.screens.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nekobot.app.data.local.ai.ContextUsagePart
import kotlin.math.min

/** Neutral surfaces preserve chosen typography and text colours. */
internal fun companionUsageScheme(scheme: ColorScheme): ColorScheme = if (scheme.background.luminance() < 0.5f) {
    scheme.copy(background = Color(0xFF191A21), surface = Color(0xFF22232C), surfaceVariant = Color(0xFF2C2D37))
} else {
    scheme.copy(background = Color(0xFFF7F8FA), surface = Color.White, surfaceVariant = Color(0xFFF0F2F6))
}

/** One palette for face and legend prevents mismatched dots after a theme change. */
internal fun companionUsagePartColor(part: ContextUsagePart, primary: Color): Color = when (part) {
    ContextUsagePart.USER_MESSAGES -> primary
    ContextUsagePart.TOOL_DEFINITIONS -> lerp(primary, Color.White, 0.35f)
    ContextUsagePart.ASSISTANT_MESSAGES -> Color(0xFF5CC6C4)
    ContextUsagePart.RECALLED_MEMORY -> Color(0xFF79C786)
    ContextUsagePart.AUXILIARY_CONTEXT -> Color(0xFFD7BB7C)
    ContextUsagePart.SYSTEM_PROMPT -> Color(0xFFA78AE3)
    ContextUsagePart.CORE_MEMORY -> Color(0xFFDF86AB)
    ContextUsagePart.SUMMARY -> Color(0xFF94A0B9)
    ContextUsagePart.TOOL_TRAJECTORY -> Color(0xFFD99172)
    ContextUsagePart.OTHER_MESSAGES -> Color(0xFF8EACA7)
}

@Composable
internal fun CompanionUsageRing(arcs: List<CompanionUsageArc>, selected: ContextUsagePart?, onSelect: (ContextUsagePart) -> Unit,
    usedTokens: Long, noSnapshot: Boolean, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val track = MaterialTheme.colorScheme.surfaceVariant
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(arcs) { reveal.snapTo(0f); reveal.animateTo(1f, tween(620)) }
    val selectionLift = remember { Animatable(0f) }
    LaunchedEffect(selected) { selectionLift.snapTo(0f); selectionLift.animateTo(1f, tween(180)) }
    val density = LocalDensity.current
    val depth = with(density) { 4.dp.toPx() }
    val descriptions = arcs.associate { it.value.part to companionUsageLabel(it.value.part) }
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val diameter = minOf(maxWidth, 284.dp)
        Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().testTag("usage-ring")
                .semantics {
                    contentDescription = "上下文组成圆环，本地估算 ${companionUsageNumber(usedTokens)} Token"
                    customActions = arcs.map { arc -> CustomAccessibilityAction("选择${descriptions[arc.value.part]}") { onSelect(arc.value.part); true } }
                }
                .pointerInput(arcs, onSelect) {
                    detectTapGestures { point ->
                        val radius = min(size.width, size.height) * 0.45f
                        companionUsageHit(arcs, point.x - size.width / 2f,
                            point.y - (size.height / 2f - depth), radius * 0.68f - depth, radius + depth)?.let(onSelect)
                    }
                }) {
                val radius = size.minDimension * 0.45f
                val centre = Offset(size.width / 2f, size.height / 2f - depth)
                if (arcs.isEmpty()) drawCircle(track, radius * 0.84f, centre, style = Stroke(radius * 0.32f))
                else arcs.forEach { arc ->
                    val sweep = arc.sweep * reveal.value
                    if (sweep > 0f) {
                        val gap = if (arcs.size == 1) 0f else arc.gap
                        val face = ringSector(centre, radius, radius * 0.68f, arc.start + gap / 2, (sweep - gap).coerceAtLeast(0.01f))
                        val colour = companionUsagePartColor(arc.value.part, primary)
                        val elevation = if (arc.value.part == selected) depth * (1f + selectionLift.value) else depth
                        translate(top = elevation + depth * 0.8f) { drawPath(face, Color.Black.copy(alpha = if (dark) 0.13f else 0.035f)) }
                        translate(top = elevation) { drawPath(face, lerp(colour, Color.Black, if (dark) 0.28f else 0.17f)) }
                        translate(top = if (arc.value.part == selected) -depth * 0.45f * selectionLift.value else 0f) {
                            drawPath(face, Brush.linearGradient(listOf(lerp(colour, Color.White, if (dark) 0.07f else 0.16f), colour, lerp(colour, Color.Black, 0.04f)),
                                start = Offset(centre.x - radius, centre.y - radius), end = Offset(centre.x + radius, centre.y + radius)))
                            drawPath(face, Color.White.copy(alpha = if (arc.value.part == selected) 0.55f else 0.2f), style = Stroke(1.dp.toPx()))
                        }
                    }
                }
            }
            val total = companionUsageNumber(usedTokens)
            val fittedSize = min(MaterialTheme.typography.headlineLarge.fontSize.value,
                diameter.value * 0.59f / (total.length.coerceAtLeast(1) * 0.62f) / density.fontScale).sp
            Column(Modifier.width(diameter * 0.61f).offset(y = (-4).dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(if (noSnapshot) "暂无请求记录" else "本地估算", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (noSnapshot) "—" else total, style = MaterialTheme.typography.headlineLarge.copy(fontSize = fittedSize),
                    fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.testTag("usage-total"))
                Text("Token", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun DrawScope.ringSector(centre: Offset, radius: Float, inner: Float, start: Float, sweep: Float): Path = Path().apply {
    arcTo(Rect(centre.x - radius, centre.y - radius, centre.x + radius, centre.y + radius), start, sweep, true)
    arcTo(Rect(centre.x - inner, centre.y - inner, centre.x + inner, centre.y + inner), start + sweep, -sweep, false)
    close()
}
