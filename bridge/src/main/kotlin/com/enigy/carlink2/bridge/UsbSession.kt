package com.enigy.carlink2.bridge

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.FileOutputStream
import java.io.IOException

/**
 * One open CPC200 at a time: claims the bulk interface, forwards inbound messages into a
 * socket the display app reads, and performs outbound writes on the display app's behalf.
 *
 * The pump reads the way the display app's direct-USB path always has — exactly 16 header
 * bytes, then the payload in ≤16 KB chunks — so the adapter sees the same request pattern.
 * It forwards a message only once it is complete, so the socket never holds a torn message
 * and the display app's parser can stay strictly aligned.
 */
internal class UsbSession(
    private val usbManager: UsbManager,
) {
    private class Active(
        val deviceName: String,
        val connection: UsbDeviceConnection,
        val iface: UsbInterface,
        val inEndpoint: UsbEndpoint,
        val outEndpoint: UsbEndpoint,
        val sink: ParcelFileDescriptor,
    ) {
        @Volatile var running = true
    }

    private val lock = Any()
    private val writeLock = Any()

    @Volatile private var active: Active? = null

    @Volatile var lastError: String = ""
        private set

    @Volatile var lastCloseReason: String = "never opened"
        private set

    val openDeviceName: String? get() = active?.deviceName

    fun hasPermission(deviceName: String?): Boolean {
        val device = deviceName?.let { usbManager.deviceList[it] } ?: return false
        return usbManager.hasPermission(device)
    }

    fun open(deviceName: String?): ParcelFileDescriptor? {
        synchronized(lock) {
            closeLocked("superseded by open($deviceName)")

            val device = deviceName?.let { usbManager.deviceList[it] }
                ?: return fail("$deviceName is not attached")
            if (!usbManager.hasPermission(device)) return fail("no USB permission for $deviceName")

            val connection = usbManager.openDevice(device)
                ?: return fail("openDevice($deviceName) returned null")
            val endpoints = findBulkEndpoints(device)
            if (endpoints == null) {
                connection.close()
                return fail("no interface with bulk IN and OUT on $deviceName")
            }
            val (iface, inEndpoint, outEndpoint) = endpoints
            if (!connection.claimInterface(iface, true)) {
                connection.close()
                return fail("claimInterface failed on $deviceName")
            }

            val pair =
                try {
                    ParcelFileDescriptor.createSocketPair()
                } catch (e: IOException) {
                    connection.releaseInterface(iface)
                    connection.close()
                    return fail("socket pair: ${e.message}")
                }
            enlargeSendBuffer(pair[0])

            val session = Active(device.deviceName, connection, iface, inEndpoint, outEndpoint, pair[0])
            active = session
            Thread({ pump(session) }, "Bridge-UsbPump").apply {
                isDaemon = true
                start()
            }
            lastError = ""
            BridgeStats.event("open $deviceName")
            Log.i(TAG, "Opened $deviceName (IN=0x${inEndpoint.address.toString(16)} OUT=0x${outEndpoint.address.toString(16)})")
            // Returned through AIDL with PARCELABLE_WRITE_RETURN_VALUE, which closes our copy.
            return pair[1]
        }
    }

    fun write(
        data: ByteArray?,
        timeoutMs: Int,
    ): Int {
        if (data == null || data.size > BridgeContract.MAX_WRITE_BYTES) {
            BridgeStats.writeErrors.incrementAndGet()
            return -1
        }
        val session = active ?: return -1
        // UsbDeviceConnection.bulkTransfer is not documented thread-safe per endpoint, and
        // the display app writes from its heartbeat, mic and UI threads.
        val result =
            synchronized(writeLock) {
                session.connection.bulkTransfer(session.outEndpoint, data, data.size, timeoutMs)
            }
        if (result == data.size) BridgeStats.writes.incrementAndGet() else BridgeStats.writeErrors.incrementAndGet()
        return result
    }

    fun close(reason: String) {
        synchronized(lock) { closeLocked(reason) }
    }

    fun onDetached(deviceName: String?) {
        synchronized(lock) {
            if (deviceName != null && active?.deviceName == deviceName) closeLocked("device detached")
        }
    }

    private fun closeOwn(
        session: Active,
        reason: String,
    ) {
        synchronized(lock) {
            if (active === session) closeLocked(reason)
        }
    }

    private fun closeLocked(reason: String) {
        val session = active ?: return
        active = null
        session.running = false
        try {
            session.connection.releaseInterface(session.iface)
        } catch (_: Exception) {
        }
        // Closing the connection makes a bulkTransfer blocked in the pump return -1.
        session.connection.close()
        // The display app sees EOF on its end of the socket.
        try {
            session.sink.close()
        } catch (_: IOException) {
        }
        lastCloseReason = reason
        BridgeStats.event("close ${session.deviceName}: $reason")
        Log.i(TAG, "Closed ${session.deviceName}: $reason")
    }

    private fun pump(session: Active) {
        // Same priority the display app's read loop uses: this thread feeds audio AND video.
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY - 2)
        val out = FileOutputStream(session.sink.fileDescriptor)
        val header = ByteArray(BridgeContract.HEADER_SIZE)
        var message = ByteArray(INITIAL_MESSAGE_BUFFER)
        var reason = "pump stopped"

        try {
            while (session.running) {
                val n = session.connection.bulkTransfer(session.inEndpoint, header, header.size, READ_TIMEOUT_MS)
                if (n < 0) {
                    // Timeout, or a transient fast failure. Detach and close() end the session;
                    // the display app decides when silence means the adapter is gone.
                    Thread.sleep(FAILED_READ_BACKOFF_MS)
                    continue
                }
                if (n != header.size) {
                    BridgeStats.droppedHeaders.incrementAndGet()
                    continue
                }
                val length = le32(header, 4)
                if (le32(header, 0) != BridgeContract.PROTOCOL_MAGIC || length !in 0..BridgeContract.MAX_PAYLOAD_SIZE) {
                    BridgeStats.droppedHeaders.incrementAndGet()
                    continue
                }

                val total = header.size + length
                if (message.size < total) message = ByteArray(maxOf(total, message.size * 2))
                System.arraycopy(header, 0, message, 0, header.size)
                var got = 0
                while (got < length && session.running) {
                    val r =
                        session.connection.bulkTransfer(
                            session.inEndpoint,
                            message,
                            header.size + got,
                            minOf(length - got, MAX_CHUNK),
                            READ_TIMEOUT_MS,
                        )
                    if (r <= 0) break
                    got += r
                }
                if (got != length) {
                    BridgeStats.droppedPartial.incrementAndGet()
                    continue
                }

                out.write(message, 0, total)
                BridgeStats.messagesIn.incrementAndGet()
                BridgeStats.bytesIn.addAndGet(total.toLong())
            }
        } catch (e: IOException) {
            reason = "display app stopped reading (${e.message})"
        } catch (e: InterruptedException) {
            reason = "pump interrupted"
        } catch (e: Exception) {
            reason = "pump error: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, reason, e)
        } finally {
            closeOwn(session, reason)
        }
    }

    private fun fail(reason: String): ParcelFileDescriptor? {
        lastError = reason
        BridgeStats.event("open failed: $reason")
        Log.w(TAG, "open failed: $reason")
        return null
    }

    // Lowest-index interface with both bulk IN and bulk OUT — same rule as the display app.
    private fun findBulkEndpoints(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            var inEndpoint: UsbEndpoint? = null
            var outEndpoint: UsbEndpoint? = null
            for (j in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(j)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (endpoint.direction == UsbConstants.USB_DIR_IN) inEndpoint = endpoint else outEndpoint = endpoint
            }
            if (inEndpoint != null && outEndpoint != null) return Triple(iface, inEndpoint, outEndpoint)
        }
        return null
    }

    // A full video frame is up to 2 MB; a bigger send buffer lets the pump hand off a frame
    // and get back to the USB endpoint while the display app is still reading it. The kernel
    // caps this at wmem_max, so it is best effort.
    private fun enlargeSendBuffer(fd: ParcelFileDescriptor) {
        try {
            Os.setsockoptInt(fd.fileDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_SNDBUF, SEND_BUFFER_BYTES)
        } catch (e: Exception) {
            Log.w(TAG, "SO_SNDBUF not raised: ${e.message}")
        }
    }

    private fun le32(
        buffer: ByteArray,
        offset: Int,
    ): Int =
        (buffer[offset].toInt() and 0xFF) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 3].toInt() and 0xFF) shl 24)

    private companion object {
        const val TAG = "Carlink2Bridge"

        // Matches the display app's direct path (AdapterDriver readTimeout).
        const val READ_TIMEOUT_MS = 30_000
        const val FAILED_READ_BACKOFF_MS = 200L
        const val MAX_CHUNK = 16_384
        const val INITIAL_MESSAGE_BUFFER = 256 * 1024
        const val SEND_BUFFER_BYTES = 4 * 1024 * 1024
    }
}
