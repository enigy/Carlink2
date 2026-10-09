package com.enigy.carlink2.bridge

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide counters and recent events, reported through ICarlinkBridge.getStatus().
 *
 * There is no adb on the head unit, so the bridge cannot be inspected directly. The display
 * app pulls this into its own file log, which is the only window into the bridge.
 */
internal object BridgeStats {
    val messagesIn = AtomicLong()
    val bytesIn = AtomicLong()
    val droppedHeaders = AtomicLong()
    val droppedPartial = AtomicLong()
    val writes = AtomicLong()
    val writeErrors = AtomicLong()

    val iconInserts = AtomicLong()
    val iconQueryHits = AtomicLong()
    val iconQueryMisses = AtomicLong()
    val iconOpens = AtomicLong()
    val iconOpenMisses = AtomicLong()

    private const val MAX_EVENTS = 8
    private val events = ArrayDeque<String>(MAX_EVENTS)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun event(text: String) {
        // Lock also covers timeFormat, which is not thread-safe.
        synchronized(events) {
            if (events.size == MAX_EVENTS) events.removeFirst()
            events.addLast("${timeFormat.format(Date())} $text")
        }
    }

    fun describe(): String {
        val recent = synchronized(events) { events.joinToString(" | ") }
        return "in=${messagesIn.get()}msg/${bytesIn.get()}B " +
            "drop(hdr=${droppedHeaders.get()} partial=${droppedPartial.get()}) " +
            "out=${writes.get()} outErr=${writeErrors.get()} " +
            "icons(insert=${iconInserts.get()} query=${iconQueryHits.get()}/${iconQueryMisses.get()}miss " +
            "open=${iconOpens.get()}/${iconOpenMisses.get()}miss) " +
            "events=[$recent]"
    }
}
