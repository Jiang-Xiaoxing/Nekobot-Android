package com.nekobot.app.ui.screens.chat

import com.nekobot.app.data.local.ai.ContextUsagePart
import com.nekobot.app.data.local.ai.ContextUsagePartTokens
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.hypot

internal data class CompanionUsageArc(val value: ContextUsagePartTokens, val start: Float, val sweep: Float, val fraction: Double) {
    // Spacing never consumes tiny categories.
    val gap: Float get() = (sweep * 0.18f).coerceAtMost(2.5f)
}

/** Composition uses its own sum, never provider input or model capacity. */
internal fun companionUsageArcs(parts: List<ContextUsagePartTokens>): List<CompanionUsageArc> {
    val visible = parts.filter { it.tokens > 0 }.sortedByDescending { it.tokens }
    val total = visible.sumOf { it.tokens.toLong() }
    if (total == 0L) return emptyList()
    var start = -90f
    return visible.mapIndexed { index, value ->
        val fraction = value.tokens.toDouble() / total
        val sweep = if (index == visible.lastIndex) 270f - start else (360 * fraction).toFloat()
        CompanionUsageArc(value, start, sweep, fraction).also { start += sweep }
    }
}

/** Empty centre and space outside the ring aren't buttons. */
internal fun companionUsageHit(arcs: List<CompanionUsageArc>, x: Float, y: Float, innerRadius: Float, outerRadius: Float): ContextUsagePart? {
    if (arcs.isEmpty() || outerRadius <= innerRadius || hypot(x, y) !in innerRadius..outerRadius) return null
    val angle = ((Math.toDegrees(atan2(y.toDouble(), x.toDouble())) + 450) % 360).toFloat()
    return arcs.firstOrNull { angle >= it.start + 90f && angle < it.start + 90f + it.sweep }?.value?.part
}

internal fun companionUsagePercent(fraction: Double?): String =
    fraction?.takeIf { it.isFinite() }?.let { String.format(Locale.getDefault(), "%.1f%%", it.coerceIn(0.0, 1.0) * 100) } ?: "—"

internal fun companionUsageNumber(value: Long): String = String.format(Locale.getDefault(), "%,d", value)
