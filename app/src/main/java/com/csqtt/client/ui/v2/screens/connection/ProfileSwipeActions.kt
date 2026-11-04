package com.csqtt.client.ui.v2.screens.connection

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csqtt.client.ui.v2.design.V2Theme
import kotlin.math.roundToInt

/** Reveal actions only; a swipe never deletes or changes the selected profile. */
@Composable
internal fun ProfileSwipeActions(
    expanded: Boolean,
    enabled: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val colors = V2Theme.colors
    val width = 176.dp
    val widthPx = with(LocalDensity.current) { width.toPx() }
    var dragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val offset by animateFloatAsState(
        targetValue = if (dragging) dragOffset else if (expanded && enabled) -widthPx else 0f,
        animationSpec = if (dragging) snap() else spring(dampingRatio = 1f, stiffness = 600f),
        label = "profile-actions",
    )
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))) {
        if (offset < -1f) {
            Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.End) {
                Surface(
                    onClick = onEdit, enabled = enabled,
                    color = colors.accent, contentColor = colors.onAccent,
                    modifier = Modifier.width(width / 2).fillMaxHeight(),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text("Изменить", fontSize = 12.sp)
                    }
                }
                Surface(
                    onClick = onDelete, enabled = enabled,
                    color = colors.danger, contentColor = colors.onDanger,
                    modifier = Modifier.width(width / 2).fillMaxHeight(),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text("Удалить", fontSize = 12.sp)
                    }
                }
            }
        }
        content(
            Modifier.offset { IntOffset(offset.roundToInt(), 0) }.draggable(
                orientation = Orientation.Horizontal,
                enabled = enabled,
                state = rememberDraggableState { delta ->
                    dragOffset = (dragOffset + delta).coerceIn(-widthPx, 0f)
                },
                onDragStarted = { dragOffset = offset; dragging = true },
                onDragStopped = { velocity ->
                    val reveal = when {
                        velocity < -600f -> true
                        velocity > 600f -> false
                        else -> dragOffset < -widthPx / 2
                    }
                    onExpandedChange(reveal)
                    dragging = false
                },
            ),
        )
    }
}
