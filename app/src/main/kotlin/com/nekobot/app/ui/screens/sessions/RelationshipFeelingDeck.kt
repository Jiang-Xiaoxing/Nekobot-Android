package com.nekobot.app.ui.screens.sessions

import androidx.compose.ui.platform.LocalResources

import com.nekobot.app.R

import android.animation.ValueAnimator
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

internal data class JournalFeeling(val label: String, val text: String, val onDetail: () -> Unit)

@Composable
internal fun RelationshipFeelingDeck(identity: String, values: List<JournalFeeling>) {
    val textResources = LocalResources.current
    var selected by rememberSaveable(identity) { mutableIntStateOf(0) }
    val current = selected.coerceIn(values.indices)
    val shift = remember { Animatable(0f) }
    var previous by remember(identity) { mutableIntStateOf(current) }
    val threshold = with(LocalDensity.current) { 40.dp.toPx() }
    val density = LocalDensity.current.density
    val selectLatest by rememberUpdatedState<(Int) -> Unit> { direction -> selected = (selected + direction + values.size) % values.size }
    LaunchedEffect(current) {
        if (previous != current && ValueAnimator.areAnimatorsEnabled()) {
            shift.snapTo(if (current > previous) 14f else -14f)
            previous = current
            shift.animateTo(0f, tween(430))
        } else { previous = current; shift.snapTo(0f) }
    }
    val skin = LocalJournalSkin.current
    val accent = skin.accent
    val paper = journalPaperColor()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp)
            .pointerInput(identity, threshold) {
                var distance = 0f
                detectHorizontalDragGestures(onDragStart = { distance = 0f }, onHorizontalDrag = { _, delta -> distance += delta },
                    onDragCancel = { distance = 0f }, onDragEnd = {
                        if (distance < -threshold) selectLatest(1) else if (distance > threshold) selectLatest(-1)
                        distance = 0f
                    })
            }) {
            Box(Modifier.matchParentSize().graphicsLayer { rotationZ = -4f; translationX = -5.dp.toPx(); translationY = -5.dp.toPx() }
                .background(lerp(paper, accent, .32f), RoundedCornerShape(6.dp)))
            Box(Modifier.matchParentSize().graphicsLayer { rotationZ = 3f; translationX = 5.dp.toPx(); translationY = -3.dp.toPx() }
                .background(lerp(paper, accent, .16f), RoundedCornerShape(6.dp)))
            Surface(onClick = values[current].onDetail, shape = RoundedCornerShape(topStart = 8.dp, topEnd = 18.dp, bottomEnd = 8.dp, bottomStart = 8.dp),
                color = paper, shadowElevation = 5.dp,
                modifier = Modifier.fillMaxWidth().animateContentSize(tween(250)).testTag("relationship_feeling_card")
                    .graphicsLayer { translationX = shift.value * density; rotationZ = shift.value * .16f }) {
                Column(Modifier.fillMaxWidth().heightIn(min = 172.dp).journalFinish(skin, paper = true)
                    .padding(horizontal = 22.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Surface(shape = RoundedCornerShape(3.dp), color = lerp(paper, accent, .13f), shadowElevation = 1.dp) {
                        Text(values[current].label, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
                    }
                    Text(values[current].text, style = MaterialTheme.typography.titleLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(textResources.getString(R.string.single_role_feelings_at_the_time_of_assessment), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        JournalEmblem(6, Modifier.size(36.dp).rotate(-9f))
                    }
                }
            }
            Icon(Icons.Filled.AttachFile, null, Modifier.align(Alignment.TopStart).offset(5.dp, (-13).dp).rotate(27f).size(36.dp), tint = skin.metal)
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val icons = listOf(Icons.Filled.Favorite, Icons.Filled.Waves, Icons.Filled.Bolt)
            values.forEachIndexed { index, value ->
                val active = index == current
                val fill = if (active) accent else paper
                Surface(modifier = Modifier.weight(1f).fillMaxHeight(), color = fill,
                    contentColor = if (active) readableOn(accent) else MaterialTheme.colorScheme.onSurface,
                    shape = skin.buttonShape, border = BorderStroke(1.dp, skin.rim),
                    shadowElevation = if (active) 4.dp else 1.dp) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(active, role = Role.Tab, onClick = { selected = index })
                        .journalFinish(skin, selected = active)
                        .padding(horizontal = 9.dp, vertical = 12.dp).testTag("relationship_feeling_$index"),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(icons[index], null, Modifier.size(21.dp))
                        Text(value.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
