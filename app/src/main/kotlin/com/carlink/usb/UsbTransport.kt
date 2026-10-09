package com.carlink.usb

/**
 * Byte path to the CPC200 adapter, as [com.carlink.protocol.AdapterDriver] sees it.
 *
 * - [BridgeUsbTransport]: the sideloaded bridge (android.car.usb.handler) owns the USB device
 *   and its platform-granted permission. The normal path.
 * - [UsbDeviceWrapper]: this app opens the device itself behind the USB permission dialog.
 *   Fallback for when the bridge is missing or has not been granted the current device.
 */
interface UsbTransport {
    val isOpened: Boolean

    /** /dev/bus/usb path of the adapter instance this transport is bound to. */
    val deviceName: String

    /** @return bytes written, or -1 on error — same contract as UsbDeviceConnection.bulkTransfer. */
    fun write(
        data: ByteArray,
        timeout: Int = 1000,
    ): Int

    fun startReadingLoop(
        callback: ReadingLoopCallback,
        timeout: Int = 30000,
        videoProcessor: VideoDataProcessor? = null,
    )

    fun stopReadingLoop()

    fun close()

    /**
     * Callback interface for direct video data processing.
     * [DIRECT_HANDOFF]: Data is already read into a buffer by the read loop.
     * Processor receives the buffer directly — no callback, no copy.
     */
    interface VideoDataProcessor {
        /**
         * Process video data directly. Data is valid only for duration of this call.
         *
         * @param data Buffer containing video payload (including 20-byte video header)
         * @param dataLength Actual bytes read into data
         * @param sourcePtsMs Source presentation timestamp in milliseconds from video header
         */
        fun processVideoDirect(
            data: ByteArray,
            dataLength: Int,
            sourcePtsMs: Int,
        )
    }

    /**
     * Callback interface for reading loop events.
     */
    interface ReadingLoopCallback {
        fun onMessage(
            type: Int,
            data: ByteArray?,
            dataLength: Int,
        )

        fun onError(error: String)
    }
}
