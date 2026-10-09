package com.carlink.usb

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.carlink.ipc.NaviVideoSingleton
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.protocol.HEADER_SIZE
import com.carlink.protocol.HeaderParseException
import com.carlink.protocol.MessageParser
import com.carlink.protocol.MessageType
import com.enigy.carlink2.bridge.BridgeContract
import com.enigy.carlink2.bridge.ICarlinkBridge
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [UsbTransport] backed by the sideloaded bridge, which owns the USB device.
 *
 * Inbound: the bridge forwards complete adapter messages (16-byte header + payload) into a
 * socket; this class reads them and hands them to AdapterDriver exactly as UsbDeviceWrapper
 * does — same video demux, same buffer reuse, same silence detection. Outbound: one Binder
 * call per message.
 */
class BridgeUsbTransport private constructor(
    private val bridge: ICarlinkBridge,
    private val stream: ParcelFileDescriptor,
    override val deviceName: String,
) : UsbTransport {
    sealed interface OpenResult {
        class Opened(
            val transport: BridgeUsbTransport,
        ) : OpenResult

        data object NotInstalled : OpenResult

        /** The bridge is up but the platform hasn't granted it this device instance. */
        data object NoPermission : OpenResult

        class Failed(
            val reason: String,
        ) : OpenResult
    }

    private val opened = AtomicBoolean(true)
    private val readingLoopActive = AtomicBoolean(false)
    private val input = FileInputStream(stream.fileDescriptor)

    @Volatile private var readLoopThread: Thread? = null

    /** Why the stream ended, for the error reported to AdapterDriver. */
    @Volatile private var streamFailure = ""

    override val isOpened: Boolean get() = opened.get()

    override fun write(
        data: ByteArray,
        timeout: Int,
    ): Int {
        if (!opened.get()) return -1
        if (data.size > BridgeContract.MAX_WRITE_BYTES) {
            log("Write of ${data.size} bytes exceeds the bridge limit (${BridgeContract.MAX_WRITE_BYTES})")
            return -1
        }
        return try {
            bridge.write(data, timeout)
        } catch (e: Exception) {
            // DeadObjectException when the bridge died; the stream EOF reports it to the driver.
            log("Bridge write failed: ${e.javaClass.simpleName}: ${e.message}")
            -1
        }
    }

    override fun startReadingLoop(
        callback: UsbTransport.ReadingLoopCallback,
        timeout: Int,
        videoProcessor: UsbTransport.VideoDataProcessor?,
    ) {
        if (readingLoopActive.getAndSet(true)) {
            log("Reading loop already active")
            return
        }
        readLoopThread =
            Thread({ readLoop(callback, timeout, videoProcessor) }, "Bridge-ReadLoop").apply {
                isDaemon = true
                start()
            }
    }

    override fun stopReadingLoop() {
        if (!readingLoopActive.getAndSet(false)) return
        try {
            // The loop polls in POLL_SLICE_MS steps, so it notices the flag promptly.
            readLoopThread?.join(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        readLoopThread = null
    }

    override fun close() {
        stopReadingLoop()
        if (!opened.getAndSet(false)) return
        try {
            bridge.close()
        } catch (e: Exception) {
            log("Bridge close failed (bridge already gone?): ${e.message}")
        }
        try {
            stream.close()
        } catch (_: IOException) {
        }
        log("Bridge transport closed for $deviceName")
    }

    private fun readLoop(
        callback: UsbTransport.ReadingLoopCallback,
        timeout: Int,
        videoProcessor: UsbTransport.VideoDataProcessor?,
    ) {
        // Same priority as UsbDeviceWrapper's loop: this thread feeds BOTH audio and video.
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY - 2)
        log("Reading loop started (bridge)")

        val header = ByteArray(HEADER_SIZE)
        // Reused buffers, same sizes and growth rules as UsbDeviceWrapper.
        var videoBuffer = ByteArray(256 * 1024)
        // REUSE CONTRACT (as in UsbDeviceWrapper): AUDIO_DATA payloads delivered via
        // onMessage are this buffer; consumers must copy before the next AUDIO_DATA.
        var audioBuffer = ByteArray(16 * 1024)

        var hasReceivedData = false
        val startedMs = SystemClock.elapsedRealtime()
        var lastDataMs = startedMs

        try {
            while (readingLoopActive.get() && opened.get()) {
                if (!awaitReadable(POLL_SLICE_MS)) {
                    // Silence rules from UsbDeviceWrapper, measured on the socket instead of
                    // the endpoint: the bridge forwards nothing while the adapter is quiet.
                    val now = SystemClock.elapsedRealtime()
                    if (!hasReceivedData && now - startedMs >= INITIAL_RESPONSE_TIMEOUT_MS) {
                        log("Adapter not responding: no data within ${INITIAL_RESPONSE_TIMEOUT_MS / 1000}s of connection")
                        callback.onError("USB read timeout — no initial response from adapter")
                        break
                    }
                    if (hasReceivedData && now - lastDataMs >= MID_SESSION_SILENCE_MS) {
                        log("Adapter silent: ${(now - lastDataMs) / 1000}s of no data after data was flowing")
                        callback.onError("USB read timeout — adapter not responding")
                        break
                    }
                    continue
                }

                if (!readFully(header, 0, HEADER_SIZE, timeout)) {
                    reportStreamEnd(callback)
                    break
                }
                lastDataMs = SystemClock.elapsedRealtime()
                hasReceivedData = true

                val parsed =
                    try {
                        MessageParser.parseHeader(header)
                    } catch (e: HeaderParseException) {
                        // The bridge already checked magic and length, so this is a type-check
                        // mismatch. Unlike raw USB we know the payload length: skip it and stay aligned.
                        val rawLength = le32(header, 4)
                        log("Header parse error: ${e.message} — skipping $rawLength payload bytes")
                        if (!skipFully(rawLength, timeout)) {
                            reportStreamEnd(callback)
                            break
                        }
                        continue
                    }

                if (parsed.length !in 0..MAX_PAYLOAD_SIZE) {
                    streamFailure = "implausible payload length ${parsed.length}"
                    reportStreamEnd(callback)
                    break
                }

                // 0x06 → main video pipeline; 0x2C → ClusterHomeDisplay forwarder (gated).
                // See UsbDeviceWrapper.startReadingLoop for the full demux rationale.
                val isMainVideo = parsed.type == MessageType.VIDEO_DATA && videoProcessor != null
                val isNaviVideo = parsed.type == MessageType.NAVI_VIDEO_DATA && NaviVideoSingleton.enabled
                if ((isMainVideo || isNaviVideo) && parsed.length > 0) {
                    if (videoBuffer.size < parsed.length) {
                        videoBuffer = ByteArray(maxOf(parsed.length, videoBuffer.size * 2))
                    }
                    if (!readFully(videoBuffer, 0, parsed.length, timeout)) {
                        reportStreamEnd(callback)
                        break
                    }
                    val sourcePts = if (parsed.length >= 16) UsbDeviceWrapper.extractPtsFromHeader(videoBuffer) else 0
                    try {
                        if (isMainVideo) {
                            videoProcessor?.processVideoDirect(videoBuffer, parsed.length, sourcePts)
                        } else {
                            NaviVideoSingleton.forwarder.onUsbFrame(videoBuffer, parsed.length)
                        }
                        callback.onMessage(parsed.type.id, null, 0)
                    } catch (e: Exception) {
                        log("Video processing error (non-fatal): ${e.message}")
                    }
                    continue
                }

                var payload: ByteArray? = null
                if (parsed.length > 0) {
                    val target =
                        if (parsed.type == MessageType.AUDIO_DATA) {
                            if (parsed.length > audioBuffer.size) {
                                audioBuffer = ByteArray(maxOf(parsed.length, audioBuffer.size * 2))
                            }
                            audioBuffer
                        } else {
                            ByteArray(parsed.length)
                        }
                    if (!readFully(target, 0, parsed.length, timeout)) {
                        reportStreamEnd(callback)
                        break
                    }
                    payload = target
                }

                try {
                    callback.onMessage(parsed.type.id, payload, if (payload == null) 0 else parsed.length)
                } catch (e: Exception) {
                    log("Message callback error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            if (readingLoopActive.get()) {
                log("Reading loop error: ${e.message}")
                callback.onError(e.message ?: "Unknown error")
            }
        } finally {
            readingLoopActive.set(false)
            log("Reading loop stopped (bridge)")
        }
    }

    private fun reportStreamEnd(callback: UsbTransport.ReadingLoopCallback) {
        if (!readingLoopActive.get()) return // stopReadingLoop() — not an error
        val status =
            try {
                bridge.status
            } catch (e: Exception) {
                "bridge unreachable: ${e.message}"
            }
        log("Bridge stream ended: $streamFailure — $status")
        callback.onError("USB bridge stream ended: $streamFailure")
    }

    /** Wait up to [timeoutMs] for the socket to become readable (data, EOF or error). */
    private fun awaitReadable(timeoutMs: Int): Boolean {
        val pollFd =
            StructPollfd().apply {
                fd = stream.fileDescriptor
                events = OsConstants.POLLIN.toShort()
            }
        return try {
            Os.poll(arrayOf(pollFd), timeoutMs) > 0
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.EINTR) false else throw IOException("poll: ${e.message}", e)
        }
    }

    /** Read exactly [length] bytes; false on EOF, stall past [timeoutMs], or stop. */
    private fun readFully(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        timeoutMs: Int,
    ): Boolean {
        var got = 0
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (got < length) {
            if (!readingLoopActive.get()) return false
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) {
                streamFailure = "stalled mid-message ($got/$length bytes)"
                return false
            }
            if (!awaitReadable(minOf(remaining, POLL_SLICE_MS.toLong()).toInt())) continue
            val n = input.read(buffer, offset + got, length - got)
            if (n < 0) {
                streamFailure = "bridge closed the stream"
                return false
            }
            got += n
        }
        return true
    }

    private fun skipFully(
        length: Int,
        timeoutMs: Int,
    ): Boolean {
        if (length !in 0..MAX_PAYLOAD_SIZE) {
            streamFailure = "implausible payload length $length"
            return false
        }
        val scratch = ByteArray(minOf(length, 16 * 1024).coerceAtLeast(1))
        var left = length
        while (left > 0) {
            val n = minOf(left, scratch.size)
            if (!readFully(scratch, 0, n, timeoutMs)) return false
            left -= n
        }
        return true
    }

    private fun le32(
        buffer: ByteArray,
        offset: Int,
    ): Int =
        (buffer[offset].toInt() and 0xFF) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 3].toInt() and 0xFF) shl 24)

    // INFO, not debug: these lines are lifecycle-only (nothing per message) and they are the
    // only record of bridge behavior — there is no adb on the head unit.
    private fun log(message: String) {
        logInfo("[BRIDGE] $message", tag = Logger.Tags.USB)
    }

    companion object {
        private const val POLL_SLICE_MS = 250
        private const val MAX_PAYLOAD_SIZE = BridgeContract.MAX_PAYLOAD_SIZE

        // Same thresholds as UsbDeviceWrapper (see the rationale there).
        private const val INITIAL_RESPONSE_TIMEOUT_MS = 15_000L
        private const val MID_SESSION_SILENCE_MS = 60_000L

        /**
         * Try to open [deviceName] through the bridge. Never shows UI; the caller decides
         * whether to fall back to [UsbDeviceWrapper].
         */
        suspend fun open(
            context: Context,
            deviceName: String,
        ): OpenResult {
            if (!CarlinkBridge.isInstalled(context)) return OpenResult.NotInstalled
            val bridge = CarlinkBridge.connect(context) ?: return OpenResult.Failed(CarlinkBridge.lastFailure)
            return try {
                val api = bridge.apiVersion
                if (api < BridgeContract.MIN_API_VERSION) {
                    return OpenResult.Failed("bridge API $api is older than required ${BridgeContract.MIN_API_VERSION}")
                }
                // The bridge's own view (handler config, user, per-device permission, icon
                // counters) — logged on every attempt so a missing grant can be diagnosed.
                logInfo("[BRIDGE] status: ${bridge.status}", tag = Logger.Tags.USB)
                if (!bridge.hasPermission(deviceName)) return OpenResult.NoPermission
                val stream = bridge.open(deviceName) ?: return OpenResult.Failed("bridge open failed — ${bridge.status}")
                OpenResult.Opened(BridgeUsbTransport(bridge, stream, deviceName))
            } catch (e: SecurityException) {
                OpenResult.Failed("bridge rejected this app: ${e.message}")
            } catch (e: Exception) {
                OpenResult.Failed("bridge call failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
}
