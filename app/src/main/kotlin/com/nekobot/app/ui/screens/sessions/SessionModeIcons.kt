package com.nekobot.app.ui.screens.sessions

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** 三种模式在筛选、列表和新建菜单中共用的图形；颜色由模式及当前主题提供。 */
internal object SessionModeIcons {
    // 人物与小气泡作为一个整体，缩小到列表标签时仍保留相同轮廓。
    val Character: ImageVector by lazy {
        icon("SessionCharacter").apply {
            path(fill = SolidColor(Color.Black)) {
                circle(7.3f, 7.1f, 3.2f)
                moveTo(1.5f, 20.3f)
                curveTo(1.5f, 15.7f, 3.6f, 13f, 7.3f, 13f)
                curveTo(11f, 13f, 13.1f, 15.7f, 13.1f, 20.3f)
                verticalLineTo(20.8f)
                quadTo(13.1f, 21.8f, 12.1f, 21.8f)
                horizontalLineTo(2.5f)
                quadTo(1.5f, 21.8f, 1.5f, 20.8f)
                close()
            }
            // 省略号使用透明镂空，避免深色主题下出现白色补丁。
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(14.2f, 3.2f)
                horizontalLineTo(20.7f)
                quadTo(22.6f, 3.2f, 22.6f, 5.1f)
                verticalLineTo(10.2f)
                quadTo(22.6f, 12.1f, 20.7f, 12.1f)
                horizontalLineTo(17.5f)
                lineTo(14.3f, 14.8f)
                verticalLineTo(12.1f)
                horizontalLineTo(14.2f)
                quadTo(12.3f, 12.1f, 12.3f, 10.2f)
                verticalLineTo(5.1f)
                quadTo(12.3f, 3.2f, 14.2f, 3.2f)
                close()
                circle(14.9f, 7.6f, 0.7f)
                circle(17.45f, 7.6f, 0.7f)
                circle(20f, 7.6f, 0.7f)
            }
        }.build()
    }

    val Agent: ImageVector by lazy {
        icon("SessionAgent").apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                // 一个入口连接两个工具节点，连线先绘制在节点下方。
                moveTo(12f, 7.8f)
                verticalLineTo(16.7f)
                quadTo(12f, 18.5f, 10.2f, 18.5f)
                horizontalLineTo(7.8f)
                moveTo(12f, 11.8f)
                horizontalLineTo(17.5f)
                quadTo(19.2f, 11.8f, 19.2f, 13.5f)
                verticalLineTo(15.5f)
                roundedBox(9f, 1.8f, 15f, 7.8f, 1.3f)
                roundedBox(1.8f, 15.5f, 7.8f, 21.5f, 1.3f)
                roundedBox(16.2f, 15.5f, 22.2f, 21.5f, 1.3f)
            }
        }.build()
    }

    val Companion: ImageVector by lazy {
        icon("SessionCompanion").apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                // 单个气泡表达固定会话，爱心表达持续陪伴。
                moveTo(7.3f, 3f)
                horizontalLineTo(16.7f)
                curveTo(19.8f, 3f, 22f, 5.2f, 22f, 8.3f)
                verticalLineTo(12.5f)
                curveTo(22f, 15.6f, 19.8f, 17.8f, 16.7f, 17.8f)
                horizontalLineTo(9f)
                lineTo(3f, 21.2f)
                lineTo(4f, 16.1f)
                curveTo(2.7f, 15.1f, 2f, 13.9f, 2f, 12.3f)
                verticalLineTo(8.3f)
                curveTo(2f, 5.2f, 4.2f, 3f, 7.3f, 3f)
                close()
                moveTo(12f, 14f)
                curveTo(10.8f, 13.1f, 7.8f, 11.2f, 7.8f, 8.7f)
                curveTo(7.8f, 6.2f, 10.6f, 6.1f, 12f, 8.1f)
                curveTo(13.4f, 6.1f, 16.2f, 6.2f, 16.2f, 8.7f)
                curveTo(16.2f, 11.2f, 13.2f, 13.1f, 12f, 14f)
                close()
            }
        }.build()
    }

    private fun icon(name: String) = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    )

    private fun PathBuilder.roundedBox(left: Float, top: Float, right: Float, bottom: Float, radius: Float) {
        moveTo(left + radius, top)
        horizontalLineTo(right - radius)
        quadTo(right, top, right, top + radius)
        verticalLineTo(bottom - radius)
        quadTo(right, bottom, right - radius, bottom)
        horizontalLineTo(left + radius)
        quadTo(left, bottom, left, bottom - radius)
        verticalLineTo(top + radius)
        quadTo(left, top, left + radius, top)
        close()
    }

    private fun PathBuilder.circle(centerX: Float, centerY: Float, radius: Float) {
        val control = radius * 0.5522848f
        moveTo(centerX + radius, centerY)
        curveTo(centerX + radius, centerY + control, centerX + control, centerY + radius, centerX, centerY + radius)
        curveTo(centerX - control, centerY + radius, centerX - radius, centerY + control, centerX - radius, centerY)
        curveTo(centerX - radius, centerY - control, centerX - control, centerY - radius, centerX, centerY - radius)
        curveTo(centerX + control, centerY - radius, centerX + radius, centerY - control, centerX + radius, centerY)
        close()
    }
}
