package com.nekobot.app.ui.screens.chat

import com.nekobot.app.data.local.ai.ContextUsagePart
import com.nekobot.app.data.local.ai.ContextUsagePartTokens
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class CompanionUsagePresentationTest {
    private fun part(type: ContextUsagePart, tokens: Int) = ContextUsagePartTokens(type, tokens, 1)

    @Test fun compositionKeepsOwnDenominatorAndIncludesTinyMemory() {
        val arcs = companionUsageArcs(listOf(part(ContextUsagePart.USER_MESSAGES, 9999), part(ContextUsagePart.CORE_MEMORY, 1)))
        assertEquals(1.0, arcs.sumOf { it.fraction }, 0.000001)
        assertEquals(360f, arcs.sumOf { it.sweep.toDouble() }.toFloat(), 0.0001f)
        assertTrue(arcs.last().sweep > arcs.last().gap)
        assertEquals(ContextUsagePart.CORE_MEMORY, arcs.last().value.part)
    }

    @Test fun centreAndOutsideAreNotSlicesAndTinySliceIsSelectable() {
        val arcs = companionUsageArcs(listOf(part(ContextUsagePart.USER_MESSAGES, 999), part(ContextUsagePart.CORE_MEMORY, 1)))
        assertNull(companionUsageHit(arcs, 0f, 0f, 60f, 100f))
        assertNull(companionUsageHit(arcs, 120f, 0f, 60f, 100f))
        val angle = Math.toRadians((arcs.last().start + arcs.last().sweep / 2).toDouble())
        assertEquals(ContextUsagePart.CORE_MEMORY, companionUsageHit(arcs, (80 * cos(angle)).toFloat(), (80 * sin(angle)).toFloat(), 60f, 100f))
    }

    @Test fun emptyAndZeroCannotFabricateAnOccupiedRing() {
        assertTrue(companionUsageArcs(emptyList()).isEmpty())
        assertTrue(companionUsageArcs(listOf(part(ContextUsagePart.USER_MESSAGES, 0))).isEmpty())
        assertNull(companionUsageHit(emptyList(), 90f, 0f, 60f, 100f))
        assertEquals("—", companionUsagePercent(null))
        assertEquals("—", companionUsagePercent(Double.NaN))
    }

    @Test fun oneCategoryRemainsACompleteSelectableRing() {
        val arcs = companionUsageArcs(listOf(part(ContextUsagePart.USER_MESSAGES, 32)))
        assertEquals(360f, arcs.single().sweep, 0f)
        assertEquals(ContextUsagePart.USER_MESSAGES, companionUsageHit(arcs, -80f, 0f, 60f, 100f))
    }

    @Test fun largePartSumsDoNotOverflowTheChartDenominator() {
        val arcs = companionUsageArcs(listOf(part(ContextUsagePart.USER_MESSAGES, Int.MAX_VALUE), part(ContextUsagePart.TOOL_DEFINITIONS, Int.MAX_VALUE)))
        assertEquals(0.5, arcs.first().fraction, 0.0)
        assertEquals(360f, arcs.sumOf { it.sweep.toDouble() }.toFloat(), 0.001f)
    }
}
