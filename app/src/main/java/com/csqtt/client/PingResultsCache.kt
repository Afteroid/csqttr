package com.csqtt.client

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Last completed measurements for this app session, independent of mounted screens. */
internal object PingResultsCache {
    private val profiles = MutableStateFlow<Map<Int, ProfilePingResult>>(emptyMap())
    private val subnets = MutableStateFlow<Map<String, Int>>(emptyMap())
    val profileResults = profiles.asStateFlow()
    val turnResults = subnets.asStateFlow()

    fun saveProfile(profile: Int, result: ProfilePingResult) { profiles.update { it + (profile to result) } }
    fun removeProfile(profile: Int) { profiles.update { it - profile } }
    fun saveTurn(subnet: String, milliseconds: Int) { subnets.update { it + (subnet to milliseconds) } }
    fun clearTurn() { subnets.value = emptyMap() }
}
