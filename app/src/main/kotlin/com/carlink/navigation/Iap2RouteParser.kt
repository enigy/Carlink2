package com.carlink.navigation

import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logNavi
import com.carlink.logging.logWarn
import java.util.concurrent.ConcurrentHashMap

/**
 * Parses an `_iap2m` hex string from a NaviJSON message into a structured [Iap2RouteData].
 *
 * The `_iap2m` field is the raw concatenated payload of multiple iAP2 0x5202 messages, one
 * per maneuver in the route. The patched ARMiPhoneIAP2 v5 binary forwards this directly
 * (the factory binary stripped most fields). Wire format documented in [Iap2ManeuverData]'s
 * class KDoc and in mempalace drawer_carlink_re_documention_00e5b4564ce1112402c1191c.
 *
 * Parser is pure / stateless / side-effect free apart from warning logs on malformed input.
 * Suitable for use from any thread.
 *
 * Validation strategy without live iPhone access: feed captured hex strings from
 * /tmp/carlink_navi_*.log "[NAVI_JSON_RX:_iap2m len=N chunk=...]" lines into [parse],
 * cross-reference the produced maneuvers against the human-readable per-step NAVI_JSON_RX
 * events from the same log.
 */
object Iap2RouteParser {
    /** Frame sync header for each per-maneuver 0x5202 message. */
    private const val FRAME_SYNC_BYTE0 = 0x40
    private const val FRAME_SYNC_BYTE1 = 0x40

    /** Type bytes 5202 mark a ManeuverList entry. */
    private const val MSG_TYPE_HI = 0x52
    private const val MSG_TYPE_LO = 0x02

    /** Field IDs (see Iap2ManeuverData KDoc for full reference). */
    private const val FIELD_INSTRUCTION_TEXT = 0x0002
    private const val FIELD_CP_MANEUVER_TYPE = 0x0003
    private const val FIELD_POST_ROAD_NAME = 0x0004
    private const val FIELD_DISTANCE_METERS = 0x0005
    private const val FIELD_DISTANCE_DISPLAY = 0x0006
    private const val FIELD_DISTANCE_UNIT = 0x0007
    private const val FIELD_FLAG_8 = 0x0008
    private const val FIELD_FLAG_9 = 0x0009
    private const val FIELD_JUNCTION_ANGLE = 0x000a
    private const val FIELD_EXIT_ANGLE = 0x000b
    private const val FIELD_LINKED_LANE_GUIDANCE = 0x000c // [236] uint16, recognized but unused
    private const val FIELD_EXIT_INFO = 0x000d // [236] utf8 exit label; see Iap2ManeuverData

    /**
     * cpManeuverType for `rampOff` — a highway/interstate exit. See ManeuverMapper's `8 ->` branch.
     *
     * [198] diagnostic: we want the cluster cue to read "Take exit 273" instead of the hardcoded
     * "Take the exit", but the exit NUMBER is absent from everything we currently decode. Across
     * every captured drive, cpType-8 maneuvers carried only destination text in instructionText —
     * "41 and Northside Dr", "92 to Woodstock", "Wade Green Road", "US-41" — all real numbered
     * exits (Wade Green Rd is I-75 exit 273, "92 to Woodstock" is I-575 exit 7) with no number.
     *
     * That is NOT proof the number is off the wire: this parser decodes 0x0002-0x000b and silently
     * skips everything else, and the unknown-field log below is logNavi = debug-only, so release
     * builds have never shown us an unrecognized TLV. [dumpRampTlvs] closes that blind spot.
     */
    private const val CP_TYPE_RAMP_OFF = 8

    /** Cap the per-field hex in [dumpRampTlvs] so a long string field can't produce a huge line. */
    private const val MAX_DUMP_BYTES = 48

    /**
     * Field IDs already reported by [logUnknownFieldOnce], so an unrecognized TLV appearing on
     * every maneuver of every route update logs once per process instead of thousands of times.
     */
    private val reportedUnknownFields = ConcurrentHashMap.newKeySet<Int>()

    /**
     * Parse a hex string (lowercase or upper, no separators) representing one or more
     * concatenated 0x5202 maneuver messages into a [Iap2RouteData].
     *
     * Returns null on irrecoverable malformed input (truncation, lost sync). Per-field
     * parse errors within a recoverable message are logged and that field is skipped.
     */
    fun parse(iap2mHex: String): Iap2RouteData? {
        val bytes = hexDecode(iap2mHex) ?: run {
            logWarn("[NAVI_PARSER] Invalid hex string (odd length or non-hex chars)", tag = Logger.Tags.NAVI)
            return null
        }
        if (bytes.isEmpty()) {
            logWarn("[NAVI_PARSER] Empty payload", tag = Logger.Tags.NAVI)
            return null
        }

        val maneuvers = mutableListOf<Iap2ManeuverData>()
        var offset = 0
        while (offset < bytes.size) {
            val (parsed, consumed) = parseOneManeuver(bytes, offset) ?: run {
                logWarn(
                    "[NAVI_PARSER] Lost frame sync at byte offset $offset of ${bytes.size}; " +
                        "parsed ${maneuvers.size} maneuvers so far",
                    tag = Logger.Tags.NAVI,
                )
                return@parse Iap2RouteData(maneuvers).takeIf { maneuvers.isNotEmpty() }
            }
            maneuvers.add(parsed)
            offset += consumed
        }

        logNavi {
            "[NAVI_PARSER] Parsed ${maneuvers.size} maneuvers from ${bytes.size} bytes " +
                "(${maneuvers.count { it.hasJunctionGeometry }} with junction geometry, " +
                "${maneuvers.count { it.isRoundabout }} roundabout-typed)"
        }
        return Iap2RouteData(maneuvers)
    }

    /**
     * Parse a single 0x5202 maneuver frame starting at [offset].
     * Returns (parsed, bytesConsumed) or null on sync loss.
     *
     * Fixed-offset iAP2 maneuver-record decode; length/branches mirror the binary field layout.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun parseOneManeuver(
        bytes: ByteArray,
        offset: Int,
    ): Pair<Iap2ManeuverData, Int>? {
        // Frame: 4040 <len_be:2> 5202 0006 0000002a 0006 0001 <index_be:2> <KVPs...>
        if (offset + 16 > bytes.size) return null
        if (bytes.u8(offset) != FRAME_SYNC_BYTE0 || bytes.u8(offset + 1) != FRAME_SYNC_BYTE1) return null

        val payloadLen = bytes.u16BE(offset + 2)
        // Frame total = payloadLen + 1 (single 0x00 padding byte appears AFTER each frame's
        // last TLV). Empirically verified 2026-05-26 across 19 consecutive frames of a Carmel
        // route 0x5202 burst — delta between consecutive 4040 sync positions is consistently
        // payloadLen + 1. Note: payloadLen itself measures the COMPLETE frame contents
        // including the length-field bytes themselves, the type bytes, the transport header,
        // the index, and all TLVs. Apple's iAP2 transport layer adds the trailing pad.
        val frameTotal = payloadLen + 1
        if (offset + frameTotal > bytes.size) {
            // Last frame may not have the trailing pad (end of message) — also accept exact fit.
            if (offset + payloadLen > bytes.size) {
                logWarn(
                    "[NAVI_PARSER] Truncated frame at offset $offset: declared payload $payloadLen " +
                        "needs $frameTotal bytes, only ${bytes.size - offset} remaining",
                    tag = Logger.Tags.NAVI,
                )
                return null
            }
        }

        // Verify type 5202
        if (bytes.u8(offset + 4) != MSG_TYPE_HI || bytes.u8(offset + 5) != MSG_TYPE_LO) {
            // Not a 0x5202 frame — could be lost sync or a different message type interleaved.
            return null
        }

        // Skip the fixed 10-byte transport header that follows the type bytes:
        // 0006 0000002a 0006 0001 — session-id / msg-id / capabilities? — opaque per
        // mempalace re_documention notes. Same for every 0x5202 we've decoded.
        // (Note: prior version of this parser had transportHeader=12 bytes which mis-read
        // the index field 2 bytes too late, picked up TLV1's length-field bytes as "index".)
        val indexOffset = offset + 6 + 10 // sync(2) + len(2) + type(2) + transportHeader(10)
        if (indexOffset + 2 > offset + payloadLen + 2) {
            logWarn("[NAVI_PARSER] Frame too short for index field at offset $offset", tag = Logger.Tags.NAVI)
            return null
        }
        val index = bytes.u16BE(indexOffset)

        // TLVs start right after the index. Each TLV layout:
        //   [tlv_total_len: 2 bytes BE][field_id: 2 bytes BE][value: tlv_total_len - 4 bytes]
        // tlv_total_len includes the 4 header bytes (length-field + field-id) PLUS the value.
        // Live-verified 2026-05-26 against captured Carmel route hex.
        val kvpStart = indexOffset + 2
        val kvpEnd = offset + payloadLen + 1 // include the trailing pad byte; we'll stop before it via tlv-len walk

        // Accumulators
        var instructionText = ""
        var cpManeuverType = 0
        var postRoad = ""
        var distanceMeters = 0
        var distanceDisplay = ""
        var distanceUnit = 0
        var flag8 = 0
        var flag9 = 0
        val entryAngles = mutableListOf<Int>()
        var exitAngle: Int? = null
        var exitInfo: String? = null

        // [198] Every TLV's (fieldId, valueStart, valueEnd), recorded as offsets into `bytes` —
        // no copying, so this costs nothing on the maneuvers we don't dump. cpManeuverType can
        // arrive at any position in the walk, so we can't decide "is this a ramp?" until the walk
        // finishes; hence collect-then-decide rather than dumping inline. See [dumpRampTlvs].
        val rawTlvs = mutableListOf<Triple<Int, Int, Int>>()

        var p = kvpStart
        while (p + 4 <= kvpEnd) {
            val tlvLen = bytes.u16BE(p)
            if (tlvLen < 4 || p + tlvLen > kvpEnd) {
                // tlvLen < 4 means malformed (header alone is 4 bytes); over-length means
                // we hit the trailing pad byte or a parser misalignment — stop cleanly.
                break
            }
            val fieldId = bytes.u16BE(p + 2)
            val valueStart = p + 4
            val valueEnd = p + tlvLen
            val valueLen = valueEnd - valueStart

            rawTlvs.add(Triple(fieldId, valueStart, valueEnd))

            when (fieldId) {
                FIELD_INSTRUCTION_TEXT -> instructionText = readNulString(bytes, valueStart, valueEnd)
                FIELD_POST_ROAD_NAME -> postRoad = readNulString(bytes, valueStart, valueEnd)
                FIELD_DISTANCE_DISPLAY -> distanceDisplay = readNulString(bytes, valueStart, valueEnd)
                FIELD_CP_MANEUVER_TYPE -> if (valueLen >= 1) cpManeuverType = bytes.u8(valueStart)
                FIELD_DISTANCE_METERS -> if (valueLen >= 4) distanceMeters = bytes.u32BE(valueStart).toInt()
                FIELD_DISTANCE_UNIT -> if (valueLen >= 1) distanceUnit = bytes.u8(valueStart)
                FIELD_FLAG_8 -> if (valueLen >= 1) flag8 = bytes.u8(valueStart)
                FIELD_FLAG_9 -> if (valueLen >= 1) flag9 = bytes.u8(valueStart)
                FIELD_JUNCTION_ANGLE -> if (valueLen >= 2) entryAngles.add(bytes.i16BE(valueStart))
                FIELD_EXIT_ANGLE -> if (valueLen >= 2) exitAngle = bytes.i16BE(valueStart)
                FIELD_LINKED_LANE_GUIDANCE -> Unit // [236] known; nothing reads it yet
                FIELD_EXIT_INFO -> exitInfo = readNulString(bytes, valueStart, valueEnd).takeIf { it.isNotEmpty() }
                else -> {
                    logNavi {
                        "[NAVI_PARSER] Unknown field 0x${fieldId.toString(16).padStart(4, '0')} " +
                            "len=$valueLen in maneuver index=$index cpType=$cpManeuverType — skipping"
                    }
                    // [198] The logNavi above is debug-only, so release builds — the ones actually
                    // driven — have never surfaced an unrecognized TLV. Report each new field id
                    // once at INFO so a field carrying, say, the exit number can't hide.
                    logUnknownFieldOnce(bytes, fieldId, valueStart, valueEnd)
                }
            }
            p = valueEnd
        }

        if (cpManeuverType == CP_TYPE_RAMP_OFF) dumpRampTlvs(bytes, index, instructionText, rawTlvs)
        if (exitInfo != null) {
            // [236] INFO so the first drive that gets ExitInfo shows it in a release log.
            logInfo(
                "[EXIT_INFO] maneuver idx=$index cpType=$cpManeuverType exit='$exitInfo' instruction='$instructionText'",
                tag = Logger.Tags.NAVI,
            )
        }

        return Iap2ManeuverData(
            index = index,
            cpManeuverType = cpManeuverType,
            instructionText = instructionText,
            postManeuverRoadName = postRoad,
            distanceMeters = distanceMeters,
            distanceDisplayText = distanceDisplay,
            distanceUnitFlag = distanceUnit,
            flag8 = flag8,
            flag9 = flag9,
            entryAngles = entryAngles.toList(),
            exitAngle = exitAngle,
            exitInfo = exitInfo,
        ) to frameTotal
    }

    /**
     * [198] Dump every TLV of a highway-exit maneuver so we can find out whether the exit NUMBER
     * is on the wire at all.
     *
     * Emitted at INFO (not logNavi) because it has to be visible in the release builds that
     * actually get driven. Fires only for cpType 8, which is a handful of maneuvers per route,
     * and only when a route arrives — not on the per-second guidance updates.
     *
     * Reading the output: each entry is `0xFFFF[len]=hex('ascii')`. Known fields 0x0002-0x000b are
     * there for context; anything OUTSIDE that range is the interesting part. A short numeric field
     * (1-2 bytes) or a string like "273" next to an unfamiliar id is the exit number we need. If
     * every ramp dumps only 0x0002-0x000b, Apple isn't sending it and the cue can't state it.
     */
    private fun dumpRampTlvs(
        bytes: ByteArray,
        index: Int,
        instructionText: String,
        tlvs: List<Triple<Int, Int, Int>>,
    ) {
        val rendered =
            tlvs.joinToString(" | ") { (fieldId, start, end) ->
                "0x${fieldId.toString(16).padStart(4, '0')}[${end - start}]=" +
                    hexPreview(bytes, start, end) + (printableOrNull(bytes, start, end)?.let { "('$it')" } ?: "")
            }
        logInfo(
            "[RAMP_TLV] idx=$index instruction='$instructionText' fields=${tlvs.size} :: $rendered",
            tag = Logger.Tags.NAVI,
        )
    }

    /** Report an unrecognized field id once per process, with enough bytes to identify it. */
    private fun logUnknownFieldOnce(
        bytes: ByteArray,
        fieldId: Int,
        valueStart: Int,
        valueEnd: Int,
    ) {
        if (!reportedUnknownFields.add(fieldId)) return
        logInfo(
            "[NAVI_TLV_NEW] Unrecognized iAP2 maneuver field " +
                "0x${fieldId.toString(16).padStart(4, '0')} len=${valueEnd - valueStart} " +
                "value=${hexPreview(bytes, valueStart, valueEnd)}" +
                (printableOrNull(bytes, valueStart, valueEnd)?.let { " ('$it')" } ?: "") +
                " — not decoded (first occurrence only)",
            tag = Logger.Tags.NAVI,
        )
    }

    /** Hex of the value, truncated to [MAX_DUMP_BYTES] with a trailing marker when clipped. */
    private fun hexPreview(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): String {
        val stop = minOf(end, start + MAX_DUMP_BYTES)
        val sb = StringBuilder((stop - start) * 2 + 1)
        for (i in start until stop) sb.append("%02x".format(bytes[i].toInt() and 0xFF))
        if (stop < end) sb.append('…')
        return sb.toString()
    }

    /**
     * The value as text, or null when it doesn't look like a string. Binary fields (angles, flags,
     * distances) would otherwise render as control-character noise and bury the readable ones.
     */
    private fun printableOrNull(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): String? {
        val text = readNulString(bytes, start, end)
        if (text.isEmpty()) return null
        return text.takeIf { candidate -> candidate.all { it.code in 32..126 } }
    }

    /** Read a UTF-8 string with optional trailing NUL stripped. */
    private fun readNulString(bytes: ByteArray, start: Int, end: Int): String {
        var realEnd = end
        // Strip trailing zero bytes
        while (realEnd > start && bytes[realEnd - 1] == 0.toByte()) realEnd--
        return String(bytes, start, realEnd - start, Charsets.UTF_8)
    }

    // ── Byte-helper extensions (private to keep public surface clean) ──
    private fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xff
    private fun ByteArray.u16BE(i: Int): Int = (u8(i) shl 8) or u8(i + 1)
    private fun ByteArray.i16BE(i: Int): Int {
        val raw = u16BE(i)
        // Sign-extend 16-bit
        return if (raw and 0x8000 != 0) raw - 0x10000 else raw
    }
    private fun ByteArray.u32BE(i: Int): Long =
        ((u8(i).toLong() shl 24) or
            (u8(i + 1).toLong() shl 16) or
            (u8(i + 2).toLong() shl 8) or
            u8(i + 3).toLong()) and 0xffffffffL

    /** Hex-decode a string with no separators; returns null on malformed input. */
    private fun hexDecode(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        var i = 0
        while (i < hex.length) {
            val hi = hexDigit(hex[i])
            val lo = hexDigit(hex[i + 1])
            if (hi < 0 || lo < 0) return null
            out[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }

    private fun hexDigit(c: Char): Int =
        when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }
}
