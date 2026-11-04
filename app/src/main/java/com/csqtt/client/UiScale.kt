package com.csqtt.client

/** Scale controls and text together, retaining Android's accessibility font scale. */
object UiScale {
    val choices = listOf(90, 100, 110, 120)
    fun normalize(percent: Int?) = percent?.takeIf { it in choices } ?: 100
}
