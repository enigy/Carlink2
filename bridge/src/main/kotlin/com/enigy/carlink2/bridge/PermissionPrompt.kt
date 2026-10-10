package com.enigy.carlink2.bridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

/**
 * One-tap fallback for an adapter the platform never granted ([205]).
 *
 * The fixed-handler grant goes to whichever user is current at attach. An adapter that
 * enumerates during boot, while the headless system user is current, is never granted to the
 * driver's copy of the bridge, and Android doesn't redo it on the user switch (truck log
 * 2026-10-09). For that case the bridge asks for the device itself through the system USB
 * permission prompt — once per device instance. A denial sticks until the adapter
 * re-enumerates (new /dev/bus/usb path), so tapping Deny never starts a prompt loop.
 */
internal class PermissionPrompt(
    private val context: Context,
    private val usbManager: UsbManager,
) {
    private val lock = Any()

    // Device instance the prompt was shown for, and whether the user turned it down.
    private var askedFor: String? = null
    private var denied = false

    private val resultReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action != ACTION_RESULT) return
                val device: UsbDevice? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                val name = device?.deviceName ?: synchronized(lock) { askedFor } ?: return
                // Read the platform's state rather than trusting the extra: before API 33 this
                // receiver is exported, so anyone could send the action.
                val granted = usbManager.deviceList[name]?.let { usbManager.hasPermission(it) } ?: false
                synchronized(lock) {
                    if (name == askedFor) denied = !granted
                }
                BridgeStats.event("prompt ${if (granted) "allowed" else "denied"} $name")
                Log.i(TAG, "USB permission prompt for $name: ${if (granted) "allowed" else "denied"}")
            }
        }

    fun register() {
        val filter = IntentFilter(ACTION_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(resultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(resultReceiver, filter)
        }
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(resultReceiver) }
    }

    /** @return a BridgeContract.PERMISSION_* value; see ICarlinkBridge.requestPermission. */
    fun request(deviceName: String?): Int {
        val device = deviceName?.let { usbManager.deviceList[it] } ?: return BridgeContract.PERMISSION_NO_DEVICE
        if (usbManager.hasPermission(device)) return BridgeContract.PERMISSION_GRANTED
        synchronized(lock) {
            if (deviceName == askedFor) {
                return if (denied) BridgeContract.PERMISSION_DENIED else BridgeContract.PERMISSION_PENDING
            }
            askedFor = deviceName
            denied = false
        }
        // MUTABLE: the platform adds EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED to the result.
        val mutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val result =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_RESULT).setPackage(context.packageName),
                mutableFlag or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        usbManager.requestPermission(device, result)
        BridgeStats.event("prompt shown $deviceName")
        Log.i(TAG, "Showing the USB permission prompt for $deviceName")
        return BridgeContract.PERMISSION_PENDING
    }

    fun describe(): String =
        synchronized(lock) {
            val asked = askedFor ?: return@synchronized "prompt=none"
            "prompt=$asked(${if (denied) "denied" else "asked"})"
        }

    private companion object {
        const val TAG = "Carlink2Bridge"
        const val ACTION_RESULT = "com.enigy.carlink2.bridge.USB_PERMISSION_RESULT"
    }
}
