package com.carlink.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect

/**
 * One-line descriptions of audio routing and voice effects, for the echo diagnostics: which
 * device a call stream actually lands on, and which echo canceller (GM/Harman's or AOSP's) the
 * platform puts on our recording.
 */
internal object AudioRouteInfo {
    fun device(d: AudioDeviceInfo?): String {
        if (d == null) return "none"
        val address = d.address.takeIf { it.isNotEmpty() }?.let { "($it)" } ?: ""
        return "${typeName(d.type)}$address"
    }

    /** Everything the device can record from. */
    fun inputs(am: AudioManager): String = am.getDevices(AudioManager.GET_DEVICES_INPUTS).joinToString(", ") { device(it) }

    /** GM's processed voice uplink input (Voice_Uplink / mic_for_vc, what GM's own CarPlay records), if listed. */
    fun uplinkBus(am: AudioManager): AudioDeviceInfo? =
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { d ->
            d.type == AudioDeviceInfo.TYPE_BUS && d.address.lowercase().let { "uplink" in it || "mic_for" in it }
        }

    /** The voice pre-processing effects the platform offers (AEC, NS, AGC), with implementor. */
    fun voiceEffects(): String =
        try {
            AudioEffect
                .queryEffects()
                ?.filter { it.type in VOICE_TYPES }
                ?.joinToString(", ") { effect(it) }
                ?: "none"
        } catch (e: Exception) {
            "query failed: ${e.message}"
        }

    fun effect(d: AudioEffect.Descriptor): String = "${d.name}/${d.implementor}{${d.uuid}}"

    /**
     * What the platform reports for the recording on [sessionId]: its device, whether it is
     * silenced, the effects the platform itself applies (pre-processing from audio_effects.xml)
     * and those the app attached.
     */
    fun recording(
        am: AudioManager?,
        sessionId: Int,
    ): String {
        val config =
            am?.activeRecordingConfigurations?.firstOrNull { it.clientAudioSessionId == sessionId }
                ?: return "recConfig=none"
        return "recDevice=${device(config.audioDevice)} silenced=${config.isClientSilenced} " +
            "platformFx=[${config.effects.joinToString { effect(it) }}] " +
            "clientFx=[${config.clientEffects.joinToString { effect(it) }}]"
    }

    private fun typeName(type: Int): String =
        when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "EARPIECE"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
            AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
            AudioDeviceInfo.TYPE_BUS -> "BUS"
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
            TYPE_ECHO_REFERENCE -> "ECHO_REFERENCE"
            else -> "type$type"
        }

    private val VOICE_TYPES = setOf(AudioEffect.EFFECT_TYPE_AEC, AudioEffect.EFFECT_TYPE_NS, AudioEffect.EFFECT_TYPE_AGC)

    // AudioDeviceInfo.TYPE_ECHO_REFERENCE is a system API.
    private const val TYPE_ECHO_REFERENCE = 28
}
