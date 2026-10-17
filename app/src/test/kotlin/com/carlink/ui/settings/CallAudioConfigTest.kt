package com.carlink.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallAudioConfigTest {
    @Test
    fun callMicReadsLegacyBuiltInAsAutomaticAndDefaultsToAutomatic() {
        assertEquals(CallMicConfig.AUTOMATIC, CallMicConfig.fromCode("built_in"))
        assertEquals(CallMicConfig.AUTOMATIC, CallMicConfig.fromCode("automatic"))
        assertEquals(CallMicConfig.VOICE_UPLINK, CallMicConfig.fromCode("voice_uplink"))
        assertEquals(CallMicConfig.AUTOMATIC, CallMicConfig.fromCode(null))
        assertEquals(CallMicConfig.AUTOMATIC, CallMicConfig.fromCode("unknown"))
    }

    @Test
    fun echoDelaySendsNothingByDefaultAndTheChosenValueOtherwise() {
        assertEquals(EchoDelayConfig.NOT_SENT, EchoDelayConfig.DEFAULT)
        assertNull(EchoDelayConfig.NOT_SENT.sentMs)
        assertEquals(320, EchoDelayConfig.MS_320.sentMs)
        assertEquals(740, EchoDelayConfig.MS_740.sentMs)
    }
}
