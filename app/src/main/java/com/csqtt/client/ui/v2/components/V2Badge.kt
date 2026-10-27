@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.csqtt.client.ui.v2.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.toPath
import com.csqtt.client.ui.v2.design.V2Palette
import com.csqtt.client.ui.v2.design.V2Sizes
import com.csqtt.client.ui.v2.design.V2Theme
import com.csqtt.client.ui.v2.design.V2VersionFamily

/** Rotating SoftBurst badge with the version text kept horizontal. */
@Composable
fun V2VersionBadge(
    version: String,
    modifier: Modifier = Modifier,
    size: Dp = V2Sizes.VersionBadge,
    background: Color = Color(0xFF0D0D10),
    contentColor: Color = Color.White,
    rotating: Boolean = true,
) {
    val badgeShape = remember {
        val path = android.graphics.Path()
        MaterialShapes.Cookie9Sided.toPath(path)
        path.asComposePath()
    }
    val rotation = if (rotating) {
        val infiniteTransition = rememberInfiniteTransition(label = "v2-version-badge")
        val value by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 24000, easing = LinearEasing),
            ),
            label = "v2-version-badge-rotation",
        )
        value
    } else {
        0f
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = rotation },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val scaleX = this.size.width
                val scaleY = this.size.height
                scale(scaleX, scaleY, pivot = Offset.Zero) {
                    drawPath(badgeShape, background)
                }
            }
        }
        Text(
            text = version,
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = V2VersionFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 13.sp,
                letterSpacing = 0.5.sp,
            ),
            color = contentColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
fun V2Avatar(
    initials: String,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = V2Sizes.Avatar,
    initialsColor: Color = V2Palette.OnInkDark,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            color = initialsColor,
        )
    }
}
