package com.carlink.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.carlink.protocol.KnownDevices

/**
 * Finds attached Carlinkit adapters. Listing devices needs no USB permission; only the
 * sideloaded bridge ever opens one ([204]).
 */
object AdapterDevices {
    fun findAll(usbManager: UsbManager): List<UsbDevice> =
        usbManager.deviceList.values.filter { device ->
            KnownDevices.isKnownDevice(device.vendorId, device.productId)
        }

    fun findFirst(usbManager: UsbManager): UsbDevice? = findAll(usbManager).firstOrNull()
}
