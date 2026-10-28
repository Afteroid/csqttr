package com.csqtt.client.ui.v2.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csqtt.client.R
import com.csqtt.client.ui.v2.design.V2Theme

enum class V2Tab(val title: String, val iconRes: Int) {
    Connection("Подкл.", R.drawable.ic_v2_house),
    Tunnel("Туннель", R.drawable.ic_v2_house),
    Deploy("Деплой", R.drawable.ic_v2_cloud),
    Logs("Логи", R.drawable.ic_v2_terminal),
    Routing("Маршрут", R.drawable.ic_v2_routing),
}

@Composable
fun V2BottomBar(selected: V2Tab, onSelect: (V2Tab) -> Unit, modifier: Modifier = Modifier, tabs: List<V2Tab> = V2Tab.entries) {
    val c = V2Theme.colors
    Surface(modifier.fillMaxWidth(), color = c.background, border = BorderStroke(1.dp, c.outline), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEach { tab ->
                val tint = if(selected == tab) c.accent else c.textSecondary
                TextButton(onClick = { onSelect(tab) }, modifier = Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(0.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        when(tab) {
                            V2Tab.Tunnel -> Icon(Icons.Outlined.VpnKey, null, tint = tint, modifier = Modifier.size(22.dp))
                            V2Tab.Connection -> Icon(Icons.Outlined.PowerSettingsNew, null, tint = tint, modifier = Modifier.size(22.dp))
                            else -> Icon(painterResource(tab.iconRes), null, tint = tint, modifier = Modifier.size(22.dp))
                        }
                        Text(tab.title, color = tint, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}
