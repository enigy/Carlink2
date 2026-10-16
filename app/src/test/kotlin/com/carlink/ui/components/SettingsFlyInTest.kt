package com.carlink.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsFlyInTest {
    @Test
    fun awayThePageIsOversizedClearAndBlurredAndTheGlassIsAbsent() {
        assertEquals(1.35f, SettingsFlyIn.contentScale(0f), 1e-4f)
        assertEquals(0f, SettingsFlyIn.contentAlpha(0f), 1e-4f)
        assertEquals(18f, SettingsFlyIn.contentBlurDp(0f), 1e-4f)
        assertEquals(0f, SettingsFlyIn.glassAlpha(0f), 1e-4f)
        assertEquals(8f, SettingsFlyIn.glassBlurDp(0f, 28f), 1e-4f)
    }

    @Test
    fun inPlaceThePageIsSharpAndTheGlassFullyFrosted() {
        assertEquals(1f, SettingsFlyIn.contentScale(1f), 1e-4f)
        assertEquals(1f, SettingsFlyIn.contentAlpha(1f), 1e-4f)
        assertEquals(0f, SettingsFlyIn.contentBlurDp(1f), 1e-4f)
        assertEquals(1f, SettingsFlyIn.glassAlpha(1f), 1e-4f)
        assertEquals(28f, SettingsFlyIn.glassBlurDp(1f, 28f), 1e-4f)
    }

    @Test
    fun theGlassArrivesBeforeThePage() {
        assertEquals(0f, SettingsFlyIn.contentAlpha(0.1f), 1e-4f)
        assertTrue(SettingsFlyIn.glassAlpha(0.1f) > 0f)
        assertEquals(1f, SettingsFlyIn.glassAlpha(0.6f), 1e-4f)
        assertTrue(SettingsFlyIn.contentAlpha(0.6f) < 1f)
    }
}
