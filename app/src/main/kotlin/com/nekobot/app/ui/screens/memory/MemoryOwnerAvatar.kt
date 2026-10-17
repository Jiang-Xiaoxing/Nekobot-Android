package com.nekobot.app.ui.screens.memory

import androidx.compose.ui.platform.LocalResources

import com.nekobot.app.R

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nekobot.app.ui.components.resolveAvatarUrl

@Composable
internal fun MemoryOwnerAvatar(name: String, avatar: String?, size: Dp = 52.dp) {
    val textResources = LocalResources.current
    val color = memoryCategoryColor(if (name.hashCode() and 1 == 0) "episode" else "promise")
    val imageUrl = resolveAvatarUrl(avatar)
    Surface(shape = RoundedCornerShape(14.dp), color = color.copy(alpha = 0.12f)) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            if (imageUrl != null) AsyncImage(imageUrl, null,
                Modifier.size(size).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
            else Text(name.takeIf(String::isNotBlank)?.let { it.substring(0, it.offsetByCodePoints(0, 1)) } ?: textResources.getString(R.string.single_role_c),
                style = MaterialTheme.typography.titleLarge, color = color)
        }
    }
}
