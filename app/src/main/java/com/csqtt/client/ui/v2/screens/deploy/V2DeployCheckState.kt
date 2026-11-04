package com.csqtt.client.ui.v2.screens.deploy

import androidx.compose.runtime.mutableStateOf
import com.csqtt.client.ui.legacy.DeployActionMode
import com.csqtt.client.ui.legacy.DeployPlan
import com.csqtt.client.ui.legacy.PlanAction

/** Owned by navigation, so a check finishing while Logs is open still has a recipient. */
internal class V2DeployCheckState {
    val plan = mutableStateOf<Pair<DeployActionMode, DeployPlan>?>(null)
    val replacement = mutableStateOf<Pair<DeployActionMode, PlanAction>?>(null)
}
