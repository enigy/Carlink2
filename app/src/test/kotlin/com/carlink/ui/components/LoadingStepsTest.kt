package com.carlink.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoadingStepsTest {
    private fun step(text: String) = LoadingSteps.classify(text, phoneConnected = false).step

    private fun action(text: String) = LoadingSteps.classify(text, phoneConnected = false).action

    @Test
    fun everyCarlinkManagerStatusLandsInItsStep() {
        val adapter =
            listOf(
                "Connect Adapter",
                "Searching for adapter...",
                "Adapter found — opening...",
                "Initializing adapter...",
                "Restarting...",
                "Reconnecting (2/5)...",
                "Waiting for head unit…",
                "Waiting for USB bridge…",
                "Waiting for adapter — retrying…",
                "Session terminated — restarting...",
                "Adapter not responding — reconnecting...",
                "Adapter not responding — retrying…",
                "Connection unstable — retrying…",
                "Adapter not found",
                "Approve the USB access prompt",
                "Unplug and replug the adapter",
                "Install the Carlink2 USB bridge",
                "USB bridge isn't starting",
                "Adapter not responding — reboot adapter",
                "Connection unstable — reboot adapter",
                "Restarting the adapter — about a minute…",
            )
        val phone =
            listOf(
                "Waiting for phone...",
                "Scanning for phone...",
                "Phone not found — retrying…",
                "Phone not reconnecting — retrying…",
                "Phone disconnected — waiting…",
                "Adapter scanning — phone not reconnecting",
                "Switching device…",
            )
        val start =
            listOf(
                "Phone found — connecting...",
                "Phone connecting...",
                "Phone connected — starting session...",
                "Connecting to device...",
                "CarPlay didn't start — retrying…",
                "CarPlay didn't start — restarting the adapter…",
                "Phone rejected configuration",
            )
        adapter.forEach { assertEquals(it, LoadingStep.ADAPTER, step(it)) }
        phone.forEach { assertEquals(it, LoadingStep.PHONE, step(it)) }
        start.forEach { assertEquals(it, LoadingStep.START, step(it)) }
    }

    @Test
    fun onlyMessagesThatAskTheDriverToActComeThrough() {
        assertEquals("Approve the USB access prompt", action("Approve the USB access prompt"))
        assertEquals("Restarting the adapter — about a minute…", action("Restarting the adapter — about a minute…"))
        assertEquals("Phone rejected configuration", action("Phone rejected configuration"))
        assertNull(action("Phone not found — retrying…"))
        assertNull(action("Reconnecting (2/5)..."))
        assertNull(action("Adapter not responding — reconnecting..."))
    }

    @Test
    fun aConnectedPhoneIsTheLastStepWhateverTheText() {
        assertEquals(LoadingStep.START, LoadingSteps.classify("Waiting for phone...", phoneConnected = true).step)
        assertEquals("Starting Android Auto", LoadingSteps.label(LoadingStep.START, androidAuto = true))
        assertEquals("Finding your phone", LoadingSteps.label(LoadingStep.PHONE, androidAuto = false))
    }
}
