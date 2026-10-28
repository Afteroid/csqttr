package com.csqtt.client.ui.v2.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csqtt.client.R
import com.csqtt.client.ui.v2.design.V2DisplayFamily
import com.csqtt.client.ui.v2.design.V2VersionFamily
import com.csqtt.client.ui.v2.design.V2Shapes
import com.csqtt.client.ui.v2.design.V2Sizes
import com.csqtt.client.ui.v2.design.V2Theme

@Composable
fun V2TopBar(
    modifier: Modifier = Modifier,
    version: String? = null,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    onVersionClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = V2Theme.colors
    androidx.compose.foundation.layout.Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(if(title == null) 64.dp else 56.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) V2BackButton(onClick = onBack)
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text(title ?: "CSQTTR", color = if(title == null) colors.accent else colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = if(title == null) 25.sp else 22.sp)
                if(title == null) version?.let { Text(it.substringBefore('-'), color = colors.textSecondary, fontSize = 11.sp) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                actions()
                if (onSettings != null) V2CircleIconButton(iconPainter = painterResource(R.drawable.ic_v2_settings), contentDescription = "Настройки", onClick = onSettings)
            }
        }
    }
}

/** Version pill — exact match to the mockup: flat dark pill, TT Travels Next label. */
@Composable
fun V2VersionPill(
    version: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = V2Theme.colors
    Surface(
        modifier = modifier
            .defaultMinSize(minWidth = 88.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
        shape = V2Shapes.Pill,
        color = colors.bottomSurface,
        contentColor = colors.textPrimary,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 11.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = version,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = V2VersionFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    letterSpacing = 0.5.sp,
                ),
                color = colors.textPrimary,
                maxLines = 1,
            )
        }
    }
}
