package com.csqtt.client

/** Interface presets are application-wide and independent of server profiles. */
object UiDesignPresets {
    const val NEW = "v2"
    const val CLASSIC = "classic"
    const val ORIGINAL_250 = "v2_classic_original"
    const val DEFAULT = ORIGINAL_250
    const val SOFT_250 = "v2_classic_soft"

    // Retained locally for reference; unavailable in the Android design selector.
    val archivedOptions = listOf(NEW to "Новый", CLASSIC to "Классический")

    val options = listOf(
        ORIGINAL_250 to "Новый · 2.5.0",
    )

    fun normalize(value: String?): String = if (value == SOFT_250) ORIGINAL_250
        else value?.takeIf { key -> options.any { it.first == key } } ?: DEFAULT
    fun label(value: String): String = options.first { it.first == normalize(value) }.second
    fun darkPalette(value: String): String = when (value) {
        ORIGINAL_250 -> "classic_original"
        SOFT_250 -> "classic_original"
        else -> "nordic_slate"
    }
}
