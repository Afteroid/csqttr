@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.csqtt.client.ui.v2.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.graphics.shapes.toPath
import com.csqtt.client.ui.v2.design.V2Palette
import com.csqtt.client.ui.v2.design.V2Sizes
import com.csqtt.client.ui.v2.design.V2Theme

@Composable
fun V2FlowerButton(
    connected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = V2Sizes.ConnectButton,
    enabled: Boolean = true,
) {
    val colors = V2Theme.colors
    // Цвет цветка всегда обычный (как в неактивном состоянии)
    val petalColor = colors.flower

    val density = LocalDensity.current

    // Render the flower into a bitmap once: scaling a texture is a transform instead of
    // re-tessellating the vector path every frame, which leaked hundreds of MB of GL memory.
    val flowerBitmap = remember(size, density, petalColor) {
        val px = with(density) { size.toPx() }.toInt().coerceAtLeast(1)
        val bitmap = android.graphics.Bitmap.createBitmap(
            px,
            px,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(bitmap)
        val path = android.graphics.Path()
        MaterialShapes.SoftBurst.toPath(path)
        val matrix = android.graphics.Matrix()
        matrix.setScale(px.toFloat(), px.toFloat())
        path.transform(matrix)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = petalColor.toArgb()
        canvas.drawPath(path, paint)
        bitmap.asImageBitmap()
    }

    Box(
        modifier = modifier
            .size(size)
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                } else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = flowerBitmap,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(modifier = Modifier.size(size * 0.26f)) {
            drawCenterIcon(connected)
        }
    }
}

/** Center play / stop glyph */
private fun DrawScope.drawCenterIcon(connected: Boolean) {
    val iconColor = V2Palette.OnInkDark
    val w = size.width
    val h = size.height

    if (connected) {
        val boxSide = w * 0.55f
        val offset = (w - boxSide) / 2f
        drawRoundRect(
            color = iconColor,
            topLeft = Offset(offset, offset),
            size = Size(boxSide, boxSide),
            cornerRadius = CornerRadius(w * 0.12f, w * 0.12f),
        )
        return
    }

    // Centered rounded play triangle
    val playWidth = w * 0.58f
    val playHeight = h * 0.65f
    val left = (w - playWidth) / 2f + w * 0.05f // slight visual centering
    val right = left + playWidth
    val top = (h - playHeight) / 2f
    val bottom = top + playHeight
    val midY = h / 2f

    val playPath = Path().apply {
        moveTo(left, top)
        lineTo(left, bottom)
        lineTo(right, midY)
        close()
    }
    drawPath(playPath, iconColor)
    drawPath(
        playPath,
        iconColor,
        style = Stroke(
            width = w * 0.16f,
            join = StrokeJoin.Round,
            cap = StrokeCap.Round,
        ),
    )
}
