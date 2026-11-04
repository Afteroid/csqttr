package com.csqtt.client.ui.legacy

import androidx.compose.runtime.mutableStateOf

/** Kept above tab content so results arriving while Logs is open are not discarded. */
internal class LegacyDeployCheckState {
    val plan = mutableStateOf<DeployPlan?>(null)
    val replacement = mutableStateOf<PlanAction?>(null)
}
