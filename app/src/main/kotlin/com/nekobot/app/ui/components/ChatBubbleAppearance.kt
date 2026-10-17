package com.nekobot.app.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.nekobot.app.ui.theme.BubbleUser
import com.nekobot.app.ui.theme.BubbleUserLight
import com.nekobot.app.ui.theme.parseHexColor
import com.nekobot.app.ui.theme.Secondary
import com.nekobot.app.ui.theme.LinkBlueLight

/** 聊天与原话定位共用外观；只读页面不引入发送、编辑或重生成行为。 */
internal data class ChatBubbleAppearance(
    val userColor: Color,
    val userBrush: Brush,
    val userText: Color,
    val assistantColor: Color,
    val assistantBorder: Color,
    val assistantText: Color,
    val assistantLink: Color
) {
    fun textColor(isUser: Boolean): Color = if (isUser) userText else assistantText
}

@Composable
internal fun chatBubbleAppearance(colorOverride: String?, assistantColorOverride: String? = null): ChatBubbleAppearance {
    val customUserColor = parseHexColor(colorOverride)
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val userColor = customUserColor
        ?: if (dark) BubbleUser else BubbleUserLight
    val endColor = userBubbleEndColor(userColor)
    val customAssistantColor = parseHexColor(assistantColorOverride)
    val assistantColor = customAssistantColor ?: aiBubbleContainerColor()
    return ChatBubbleAppearance(
        userColor = userColor,
        userBrush = Brush.linearGradient(listOf(userColor, endColor)),
        userText = if (customUserColor == null) Color.White else bubbleContentColor(userColor, endColor),
        assistantColor = assistantColor,
        assistantBorder = if (customAssistantColor == null) aiBubbleBorderColor() else Color.Transparent,
        assistantText = if (customAssistantColor == null) MaterialTheme.colorScheme.onSurface else bubbleContentColor(assistantColor),
        assistantLink = if (customAssistantColor == null) { if (dark) Secondary else LinkBlueLight } else bubbleContentColor(assistantColor)
    )
}

/** Choose the foreground with the best minimum contrast across both gradient endpoints. */
internal fun bubbleContentColor(start: Color, end: Color = start): Color {
    val low = minOf(start.luminance(), end.luminance())
    val high = maxOf(start.luminance(), end.luminance())
    return if ((low + 0.05f) / 0.05f >= 1.05f / (high + 0.05f)) Color.Black else Color.White
}

/** 默认气泡沿用官方发行版的渐变配色。 */
private fun userBubbleEndColor(color: Color): Color {
    val argb = color.toArgb()
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, hsv
    )
    hsv[1] = (hsv[1] * 1.06f).coerceAtMost(1f)
    hsv[2] = (hsv[2] * 0.78f).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

internal fun chatBubbleShape(isUser: Boolean, isLast: Boolean = true) = RoundedCornerShape(
    topStart = 20.dp,
    topEnd = 20.dp,
    bottomStart = if (isUser || isLast) 20.dp else 6.dp,
    bottomEnd = if (isUser && isLast) 6.dp else 20.dp
)

@Composable
internal fun aiBubbleContainerColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
    else MaterialTheme.colorScheme.surface

@Composable
internal fun aiBubbleBorderColor(): Color =
    MaterialTheme.colorScheme.onSurface.copy(alpha = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) 0.08f else 0.05f)
