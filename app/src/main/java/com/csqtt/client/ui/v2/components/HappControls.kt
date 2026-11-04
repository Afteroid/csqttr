package com.csqtt.client.ui.v2.components

import com.csqtt.client.ui.v2.design.V2TextSizes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csqtt.client.ui.v2.design.V2Theme

@Composable
internal fun HappField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    password: Boolean = false, number: Boolean = false, enabled: Boolean = true,
    placeholder: String = fieldFormatHint(label), compact: Boolean = false, visibilityKey: Any? = null,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(14.dp), floatingLabel: Boolean = false, maxFieldHeight: Dp? = null) {
    val c = V2Theme.colors
    val layout = LocalV2AdaptiveMetrics.current
    val density = LocalDensity.current
    val labelInset = with(density) { 16.sp.toDp() } / 2
    val minimumFieldHeight = with(density) { 20.sp.toDp() } + 12.dp
    val fieldHeight = maxFieldHeight?.let { layout.fieldHeight.coerceAtMost(maxOf(it, minimumFieldHeight)) } ?: layout.fieldHeight
    var showPassword by remember(label, visibilityKey) { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) {
        showPassword = false
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (!floatingLabel) Text(label, color = c.textSecondary, fontSize = V2TextSizes.Secondary, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.padding(top = if (floatingLabel) labelInset else 0.dp)) {
        BasicTextField(value, onChange, modifier = Modifier.fillMaxWidth().height(fieldHeight + if (compact) 0.dp else 6.dp).semantics { contentDescription = label },
            singleLine = true, enabled = enabled,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = V2TextSizes.Body, color = c.textPrimary),
            visualTransformation = if (password && !showPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = when { password -> KeyboardType.Password; number -> KeyboardType.Number; else -> KeyboardType.Text }),
            decorationBox = { inner ->
                Surface(shape = shape, color = c.field, border = BorderStroke(1.dp, c.outline)) {
                    Row(Modifier.fillMaxSize().padding(start = 12.dp, end = if(password) 2.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if(value.isEmpty()) Text(placeholder, color = c.textMuted, fontSize = V2TextSizes.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            inner()
                        }
                        if(password) IconButton(onClick = { showPassword = !showPassword }, enabled = enabled, modifier = Modifier.size(36.dp)) {
                            Icon(if(showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                "${if(showPassword) "Скрыть" else "Показать"} ${label.lowercase()}", tint = c.textSecondary, modifier = Modifier.size(17.dp))
                        }
                    }
                }
            })
        if (floatingLabel) Text(label,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp).offset(y = -labelInset).background(c.surface).padding(horizontal = 4.dp),
            color = c.textSecondary, fontSize = V2TextSizes.Secondary, lineHeight = 16.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun fieldFormatHint(label: String): String = when {
    label == "IP сервера" || label == "Адрес сервера" -> "203.0.113.10 или vpn.example.com"
    label == "Порт SSH" -> "22 или ваш SSH-порт"
    label == "Порт PEER" -> "46000"
    label == "Порт WEB" -> "46002"
    label == "Логин SSH" -> "root"
    label == "Пароль SSH" -> "Пароль пользователя SSH"
    label.startsWith("Пароль туннеля ·") -> "16 символов: A–Z, a–z, 0–9"
    label == "Пароль туннеля" -> "Пароль из ссылки или деплоя"
    label == "Логин веб-панели" -> "Например, admin"
    label == "Пароль веб-панели" -> "Пароль доступа к панели"
    else -> label
}

@Composable
internal fun HappHelp(title: String, text: String) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp)) {
        Icon(Icons.AutoMirrored.Outlined.HelpOutline, "Подсказка: $title", tint = V2Theme.colors.textSecondary, modifier = Modifier.size(17.dp))
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            helpSections(title, text).forEach { section ->
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if(section.title.isNotBlank()) Text(section.title, fontSize = V2TextSizes.Body, fontWeight = FontWeight.Normal, color = V2Theme.colors.textPrimary)
                    section.points.forEach { point -> Text("• $point", fontSize = V2TextSizes.Body, lineHeight = 19.sp, color = V2Theme.colors.textSecondary) }
                }
            }
        }
    },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Понятно") } })
}

@Composable
internal fun HappChoice(label: String, value: String, options: List<String>, onSelect: (String) -> Unit,
    modifier: Modifier = Modifier, tone: Color? = null, help: String? = null, enabled: Boolean = true, compact: Boolean = false,
    valueMaxLines: Int = 1, labelContent: (@Composable () -> Unit)? = null,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(14.dp)) {
    val c = V2Theme.colors
    var expanded by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().heightIn(min = LocalV2AdaptiveMetrics.current.rowHeight + if (compact) 0.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (labelContent == null) Text(label, modifier = Modifier.weight(1f), color = c.textPrimary, fontSize = V2TextSizes.Body)
        else Box(Modifier.weight(1f)) { labelContent() }
        if (help != null) HappHelp(label, help)
        Box(Modifier.weight(1.15f)) {
            Surface(onClick = { if (enabled) expanded = true }, enabled = enabled, shape = shape,
                color = c.field, border = BorderStroke(1.dp, c.outline)) {
                Column(Modifier.heightIn(min = LocalV2AdaptiveMetrics.current.fieldHeight).padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(value, modifier = Modifier.weight(1f), fontSize = V2TextSizes.Body, maxLines = valueMaxLines, overflow = TextOverflow.Ellipsis, color = c.textPrimary)
                        Icon(Icons.Default.KeyboardArrowDown, null, tint = c.textSecondary, modifier = Modifier.size(18.dp))
                    }
                    if (tone != null) Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(3.dp).background(tone, RoundedCornerShape(2.dp)))
                }
            }
            DropdownMenu(expanded, { expanded = false }, containerColor = c.surface) {
                options.forEach { option -> DropdownMenuItem(text = { Text(option, fontSize = V2TextSizes.Body) }, onClick = { expanded = false; onSelect(option) }) }
            }
        }
    }
}

@Composable
internal fun HappActionRow(title: String, detail: String = "", onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val c = V2Theme.colors
    Row(modifier.fillMaxWidth().heightIn(min = LocalV2AdaptiveMetrics.current.rowHeight + if (compact) 0.dp else 8.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), fontSize = V2TextSizes.Body, color = c.textPrimary)
        Text(detail, color = c.textSecondary, fontSize = V2TextSizes.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
        Text(" ›", color = c.textSecondary, fontSize = 22.sp)
    }
}
