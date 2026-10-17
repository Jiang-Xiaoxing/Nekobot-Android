package com.nekobot.app.ui.screens.memory

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.nekobot.app.R

/** 图标与旁边的文字共同表达含义；彩虹只用于核心，普通操作使用各自的配色。 */
@Composable
internal fun CoreMemoryIcon(modifier: Modifier = Modifier.size(24.dp)) {
    Icon(painterResource(R.drawable.ic_companion_core_memory), contentDescription = null,
        modifier = modifier, tint = Color.Unspecified)
}

@Composable
internal fun EditMemoryIcon(modifier: Modifier = Modifier.size(24.dp)) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Icon(painterResource(R.drawable.ic_companion_memory_edit), contentDescription = null,
        modifier = modifier, tint = if (dark) Color(0xFFE4BA7B) else Color(0xFFB58132))
}

@Composable
internal fun MemoryHistoryIcon(modifier: Modifier = Modifier.size(24.dp)) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Icon(painterResource(R.drawable.ic_companion_memory_history), contentDescription = null,
        modifier = modifier, tint = if (dark) Color(0xFFA4C6E5) else Color(0xFF557EAC))
}
