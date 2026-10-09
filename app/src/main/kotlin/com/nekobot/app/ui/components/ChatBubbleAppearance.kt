package com.nekobot.app.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.nekobot.app.ui.theme.BubbleUser
import com.nekobot.app.ui.theme.BubbleUserLight
import com.nekobot.app.ui.theme.parseHexColor

/** 聊天与原话定位共用外观；只读页面不引入发送、编辑或重生成行为。 */
internal data class ChatBubbleAppearance(
    val userColor: Color,
    val userBrush: Brush,
    val assistantColor: Color,
    val assistantBorder: Color,
    val assistantText: Color
) {
    fun textColor(isUser: Boolean): Color = if (isUser) Color.White else assistantText
}

@Composable
internal fun chatBubbleAppearance(colorOverride: String?): ChatBubbleAppearance {
    val userColor = parseHexColor(colorOverride)
        ?: if (isSystemInDarkTheme()) BubbleUser else BubbleUserLight
    return ChatBubbleAppearance(
        userColor = userColor,
        userBrush = Brush.linearGradient(listOf(userColor, deepenBubbleColor(userColor))),
        assistantColor = aiBubbleContainerColor(),
        assistantBorder = aiBubbleBorderColor(),
        assistantText = MaterialTheme.colorScheme.onSurface
    )
}

internal fun chatBubbleShape(isUser: Boolean, isLast: Boolean = true) = RoundedCornerShape(
    topStart = 20.dp,
    topEnd = 20.dp,
    bottomStart = if (isUser || isLast) 20.dp else 6.dp,
    bottomEnd = if (isUser && isLast) 6.dp else 20.dp
)

@Composable
internal fun aiBubbleContainerColor(): Color =
    if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
    else MaterialTheme.colorScheme.surface

@Composable
internal fun aiBubbleBorderColor(): Color =
    MaterialTheme.colorScheme.onSurface.copy(alpha = if (isSystemInDarkTheme()) 0.08f else 0.05f)

private fun deepenBubbleColor(color: Color): Color {
    val argb = color.toArgb()
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, hsv
    )
    hsv[1] = (hsv[1] * 1.06f).coerceAtMost(1f)
    hsv[2] = (hsv[2] * 0.78f).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}
