package com.csqtt.client.ui.v2.components

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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csqtt.client.ui.v2.design.V2Theme

@Composable
internal fun HappField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    password: Boolean = false, number: Boolean = false, enabled: Boolean = true,
    placeholder: String = fieldFormatHint(label), compact: Boolean = false) {
    val c = V2Theme.colors
    var showPassword by rememberSaveable(label) { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = c.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicTextField(value, onChange, modifier = Modifier.fillMaxWidth().height(if (compact) 38.dp else 44.dp).semantics { contentDescription = label },
            singleLine = true, enabled = enabled,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = c.textPrimary),
            visualTransformation = if (password && !showPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = when { password -> KeyboardType.Password; number -> KeyboardType.Number; else -> KeyboardType.Text }),
            decorationBox = { inner ->
                Surface(shape = RoundedCornerShape(14.dp), color = c.field, border = BorderStroke(1.dp, c.outline)) {
                    Row(Modifier.fillMaxSize().padding(start = 12.dp, end = if(password) 2.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if(value.isEmpty()) Text(placeholder, color = c.textMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            inner()
                        }
                        if(password) IconButton(onClick = { showPassword = !showPassword }, enabled = enabled, modifier = Modifier.size(36.dp)) {
                            Icon(if(showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                "${if(showPassword) "Скрыть" else "Показать"} ${label.lowercase()}", tint = c.textSecondary, modifier = Modifier.size(17.dp))
                        }
                    }
                }
            })
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
                    if(section.title.isNotBlank()) Text(section.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = V2Theme.colors.textPrimary)
                    section.points.forEach { point -> Text("• $point", fontSize = 13.sp, lineHeight = 19.sp, color = V2Theme.colors.textSecondary) }
                }
            }
        }
    },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Понятно") } })
}

@Composable
internal fun HappChoice(label: String, value: String, options: List<String>, onSelect: (String) -> Unit,
    modifier: Modifier = Modifier, tone: Color? = null, help: String? = null, enabled: Boolean = true, compact: Boolean = false) {
    val c = V2Theme.colors
    var expanded by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().heightIn(min = if (compact) 40.dp else 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = c.textPrimary, fontSize = 13.sp)
        if (help != null) HappHelp(label, help)
        Box(Modifier.widthIn(min = 128.dp, max = 158.dp)) {
            Surface(onClick = { if (enabled) expanded = true }, enabled = enabled, shape = RoundedCornerShape(14.dp),
                color = c.field, border = BorderStroke(1.dp, c.outline)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(value, modifier = Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, color = c.textPrimary)
                        Icon(Icons.Default.KeyboardArrowDown, null, tint = c.textSecondary, modifier = Modifier.size(18.dp))
                    }
                    if (tone != null) Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(3.dp).background(tone, RoundedCornerShape(2.dp)))
                }
            }
            DropdownMenu(expanded, { expanded = false }, containerColor = c.surface) {
                options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; onSelect(option) }) }
            }
        }
    }
}

@Composable
internal fun HappActionRow(title: String, detail: String = "", onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val c = V2Theme.colors
    Row(modifier.fillMaxWidth().heightIn(min = if (compact) 40.dp else 48.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), fontSize = 13.sp, color = c.textPrimary)
        Text(detail, color = c.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
        Text(" ›", color = c.textSecondary, fontSize = 22.sp)
    }
}
