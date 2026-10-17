package com.nekobot.app.ui.screens.chat

import androidx.compose.ui.platform.LocalResources

import com.nekobot.app.R

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** 角色正在翻记忆：柔和胶囊＋缓慢翻页的书＋三个渐隐小点，颜色跟随主题。 */
@Composable
internal fun CompanionRecallingPill(modifier: Modifier = Modifier) {
    val textResources = LocalResources.current
    val scheme = MaterialTheme.colorScheme
    val tint = scheme.primary
    val motion = rememberInfiniteTransition(label = "recalling")
    val flip by motion.animateFloat(0f, 1f, infiniteRepeatable(
        keyframes { durationMillis = 2400; 0f at 0; 1f at 1700 using FastOutSlowInEasing; 1f at 2400 }), label = "page")
    val dots by motion.animateFloat(0f, 3f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "dots")
    Row(modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
        .semantics { contentDescription = textResources.getString(R.string.single_role_recalling) }.testTag("companion-recalling")) {
        Surface(shape = RoundedCornerShape(50), color = lerp(scheme.surface, tint, 0.13f)) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FlippingBook(flip, tint, Modifier.size(20.dp))
                Text(textResources.getString(R.string.single_role_recalling), style = MaterialTheme.typography.bodyMedium, color = tint)
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    repeat(3) { i ->
                        val phase = ((dots - i) % 3f + 3f) % 3f
                        Box(Modifier.size(4.dp).alpha(1f - phase / 3f * 0.8f).background(tint, CircleShape))
                    }
                }
            }
        }
    }
}

@Composable
private fun FlippingBook(progress: Float, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val stroke = 1.5.dp.toPx()
        val spine = w / 2f; val top = h * 0.2f; val bottom = h * 0.86f; val edge = w * 0.06f
        fun page(outer: Float) = Path().apply {
            moveTo(spine, top + h * 0.05f)
            quadraticTo((spine + outer) / 2f, top - h * 0.06f, outer, top + h * 0.02f)
            lineTo(outer, bottom - h * 0.02f)
            quadraticTo((spine + outer) / 2f, bottom - h * 0.12f, spine, bottom)
            close()
        }
        drawPath(page(edge), color, style = Stroke(stroke, join = StrokeJoin.Round))
        drawPath(page(w - edge), color, style = Stroke(stroke, join = StrokeJoin.Round))
        // 翻动的一页：宽度按余弦从右侧翻到左侧，中途微微抬起。
        val turn = cos(PI * progress).toFloat()
        val lift = abs(sin(PI * progress)).toFloat() * h * 0.08f
        val x = spine + (w / 2f - edge) * turn
        val flipping = Path().apply {
            moveTo(spine, top + h * 0.05f)
            lineTo(x, top + h * 0.02f - lift)
            lineTo(x, bottom - h * 0.02f - lift)
            lineTo(spine, bottom)
            close()
        }
        drawPath(flipping, color.copy(alpha = 0.2f))
        drawPath(flipping, color, style = Stroke(stroke * 0.8f, join = StrokeJoin.Round))
    }
}
