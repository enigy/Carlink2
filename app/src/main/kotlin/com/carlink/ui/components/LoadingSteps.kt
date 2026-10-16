package com.carlink.ui.components

/** [229] The connecting screen's three steps. */
internal enum class LoadingStep { ADAPTER, PHONE, START }

/** What the connecting screen shows: the step, and a plain line when the driver has to act. */
internal data class LoadingStatus(
    val step: LoadingStep,
    val action: String? = null,
)

/**
 * [229] Folds CarlinkManager's status text (25+ messages) into three steps. Retries and errors
 * stay in their step; only the messages that ask the driver to do something (or explain a long
 * wait) come through, unchanged, as [LoadingStatus.action].
 */
internal object LoadingSteps {
    // Shown as they are, under the step. Matched by prefix, lowercase.
    private val ACTIONS =
        listOf(
            "approve the usb access prompt",
            "unplug and replug the adapter",
            "install the carlink2 usb bridge",
            "usb bridge isn't starting",
            "adapter not found",
            "adapter not responding — reboot adapter",
            "connection unstable — reboot adapter",
            "restarting the adapter — about a minute",
            "phone rejected configuration",
        )

    // The phone answered: CarPlay is starting. Checked before PHONE_WORDS ("phone found…").
    private val START_WORDS =
        listOf("phone found", "phone connect", "connecting to device", "didn't start", "phone rejected")

    private val PHONE_WORDS =
        listOf("waiting for phone", "scanning", "phone not", "phone disconnected", "switching device")

    fun classify(
        statusText: String,
        phoneConnected: Boolean,
    ): LoadingStatus {
        val text = statusText.lowercase()
        val action = statusText.takeIf { ACTIONS.any { text.startsWith(it) } }
        val step =
            when {
                phoneConnected || START_WORDS.any { it in text } -> LoadingStep.START
                PHONE_WORDS.any { it in text } -> LoadingStep.PHONE
                else -> LoadingStep.ADAPTER
            }
        return LoadingStatus(step, action)
    }

    fun label(
        step: LoadingStep,
        androidAuto: Boolean,
    ): String =
        when (step) {
            LoadingStep.ADAPTER -> "Waking the adapter"
            LoadingStep.PHONE -> "Finding your phone"
            LoadingStep.START -> if (androidAuto) "Starting Android Auto" else "Starting CarPlay"
        }
}
