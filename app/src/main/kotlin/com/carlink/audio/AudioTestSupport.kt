@file:Suppress("MatchingDeclarationName") // the lock plus the helpers both tests share

package com.carlink.audio

import android.media.AudioAttributes
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps the parked audio tests ([EchoTest], [VoiceTest]) from running over each other. */
internal object AudioTestLock {
    private val busy = AtomicBoolean(false)

    fun tryLock(): Boolean = busy.compareAndSet(false, true)

    fun unlock() = busy.set(false)
}

/** Attributes of the call track: USAGE_VOICE_COMMUNICATION speech. */
internal fun voiceCallAttributes(): AudioAttributes =
    AudioAttributes
        .Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

/**
 * Attach AEC, NS and AGC to [sessionId] like the call mic does, adding each created effect to
 * [into] (the caller releases them). Returns one "name=state:implementation" entry per effect.
 */
internal fun attachVoiceEffects(
    sessionId: Int,
    into: MutableList<AudioEffect>,
): String {
    val parts = mutableListOf<String>()

    fun add(
        name: String,
        available: Boolean,
        create: () -> AudioEffect?,
    ) {
        if (!available) {
            parts += "$name=unavailable"
            return
        }
        try {
            val e = create()
            if (e == null) {
                parts += "$name=null"
            } else {
                e.enabled = true
                into += e
                parts += "$name=${if (e.enabled) "on" else "OFF"}:${AudioRouteInfo.effect(e.descriptor)}"
            }
        } catch (ex: Exception) {
            parts += "$name=failed(${ex.message})"
        }
    }
    add("aec", AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler.create(sessionId) }
    add("ns", NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(sessionId) }
    add("agc", AutomaticGainControl.isAvailable()) { AutomaticGainControl.create(sessionId) }
    return parts.joinToString(" ")
}
