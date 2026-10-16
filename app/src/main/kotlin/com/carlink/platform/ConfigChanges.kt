package com.carlink.platform

/**
 * [228] Names the bits of an `ActivityInfo.CONFIG_*` mask (from `Configuration.diff` or
 * `Activity.changingConfigurations`), for the lifecycle log. The values are the framework's
 * constants, two of them hidden (window configuration and assets paths: a resource overlay
 * change), so an unexpected recreation names its cause.
 */
internal object ConfigChanges {
    private val NAMES =
        listOf(
            0x0001 to "mcc",
            0x0002 to "mnc",
            0x0004 to "locale",
            0x0008 to "touchscreen",
            0x0010 to "keyboard",
            0x0020 to "keyboardHidden",
            0x0040 to "navigation",
            0x0080 to "orientation",
            0x0100 to "screenLayout",
            0x0200 to "uiMode",
            0x0400 to "screenSize",
            0x0800 to "smallestScreenSize",
            0x1000 to "density",
            0x2000 to "layoutDirection",
            0x4000 to "colorMode",
            0x10000000 to "fontWeightAdjustment",
            0x20000000 to "windowConfiguration",
            0x40000000 to "fontScale",
            0x80000000.toInt() to "assetsPaths",
        )

    fun describe(mask: Int): String {
        if (mask == 0) return "none"
        val named = NAMES.filter { (bit, _) -> mask and bit != 0 }
        val rest = NAMES.fold(mask) { left, (bit, _) -> left and bit.inv() }
        val parts = named.map { it.second } + if (rest != 0) listOf("0x${Integer.toHexString(rest)}") else emptyList()
        return parts.joinToString("|")
    }
}
