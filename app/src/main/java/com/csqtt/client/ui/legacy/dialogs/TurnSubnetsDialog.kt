package com.csqtt.client.ui.legacy.dialogs

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.luminance
import com.csqtt.client.ui.v2.design.CsqttV2Colors
import com.csqtt.client.ui.v2.dialogs.V2TurnSubnetsDialog

@Composable
internal fun TurnSubnetsDialog(
    selectedSubnetId: String,
    turnSubnetIpLimit: String = "ALL",
    onSelectSubnet: (String) -> Unit,
    onSelectIpLimit: (String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    // V2's sheet, in V2's colours for the classic theme's light or dark (without them it fell back to the dark palette)
    CsqttV2Colors(darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        V2TurnSubnetsDialog(
            selectedSubnetId = selectedSubnetId,
            turnSubnetIpLimit = turnSubnetIpLimit,
            onSelectSubnet = onSelectSubnet,
            onSelectIpLimit = onSelectIpLimit,
            onDismiss = onDismiss,
        )
    }
}
