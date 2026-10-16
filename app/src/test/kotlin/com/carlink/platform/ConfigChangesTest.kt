package com.carlink.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigChangesTest {
    @Test
    fun namesEachBitAndKeepsUnknownOnesAsHex() {
        assertEquals("none", ConfigChanges.describe(0))
        assertEquals("touchscreen|uiMode", ConfigChanges.describe(0x0008 or 0x0200))
        assertEquals("assetsPaths", ConfigChanges.describe(0x80000000.toInt()))
        assertEquals("colorMode|0x8000", ConfigChanges.describe(0x4000 or 0x8000))
    }
}
