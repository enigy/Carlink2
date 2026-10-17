package com.carlink.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [236] ExitInfo (0x5202 field 0x000d) through the maneuver parser and into the step cue. */
@RunWith(RobolectricTestRunner::class)
class ExitInfoTest {
    private fun tlv(
        id: Int,
        valueHex: String,
    ): String {
        val len = 4 + valueHex.length / 2
        return "%04x%04x%s".format(len, id, valueHex)
    }

    private fun utf8z(s: String): String = s.toByteArray().joinToString("") { "%02x".format(it) } + "00"

    /** One 0x5202 frame: `4040 <len> 5202 0006 0000002a 0006 0001 <index> TLVs… 00`. */
    private fun frame(
        index: Int,
        tlvs: String,
    ): String {
        val payloadLen = 2 + 2 + 2 + 10 + 2 + tlvs.length / 2
        return "4040%04x5202" .format(payloadLen) + "00060000002a00060001" + "%04x".format(index) + tlvs + "00"
    }

    @Test
    fun parsesExitInfoWhenPresentAndLeavesItNullOtherwise() {
        val withExit =
            frame(
                13,
                tlv(0x0002, utf8z("Wade Green Rd")) + tlv(0x0003, "08") + tlv(0x000d, utf8z("273")),
            )
        val without = frame(14, tlv(0x0002, utf8z("I-75 N")) + tlv(0x0003, "09"))

        val route = Iap2RouteParser.parse(withExit + without)
        assertNotNull(route)
        val (exit, merge) = route!!.maneuvers
        assertEquals(13, exit.index)
        assertEquals(8, exit.cpManeuverType)
        assertEquals("273", exit.exitInfo)
        assertEquals("Wade Green Rd", exit.instructionText)
        assertEquals(14, merge.index)
        assertNull(merge.exitInfo)
    }

    @Test
    fun stepCuePutsTheExitFirst() {
        assertEquals("Exit 273 · Wade Green Rd", NavigationState.exitCue("273", "Wade Green Rd"))
        assertEquals("Exit 12B", NavigationState.exitCue("12B", null))
        assertEquals("Exit 273 · Wade Green Rd", NavigationState.exitCue("Exit 273", "Wade Green Rd"))
        assertEquals("Exit 273", NavigationState.exitCue(" 273 ", "exit 273"))
        assertEquals("Wade Green Rd", NavigationState.exitCue(null, "Wade Green Rd"))
        assertEquals("Wade Green Rd", NavigationState.exitCue("", "Wade Green Rd"))
        assertNull(NavigationState.exitCue(null, null))
        assertEquals("Exit 7 · 92 to Woodstock", NavigationState(exitInfo = "7", roadName = "92 to Woodstock").stepCue)
    }
}
