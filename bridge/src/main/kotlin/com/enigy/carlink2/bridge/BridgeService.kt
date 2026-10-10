package com.enigy.carlink2.bridge

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log

/**
 * The display app binds here for the adapter. This service holds no state of its own beyond
 * the [UsbSession] and the [PermissionPrompt]. USB permission normally arrives from the
 * platform's fixed-handler grant; the prompt is only for an adapter that attached during boot
 * and so missed that grant ([205]).
 */
class BridgeService : Service() {
    private lateinit var usbManager: UsbManager
    private lateinit var session: UsbSession
    private lateinit var callers: CallerVerifier
    private lateinit var prompt: PermissionPrompt

    private val detachReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
                val device: UsbDevice? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                session.onDetached(device?.deviceName)
            }
        }

    private val binder =
        object : ICarlinkBridge.Stub() {
            override fun getApiVersion(): Int = BridgeContract.API_VERSION

            override fun hasPermission(deviceName: String?): Boolean {
                enforceCaller()
                return session.hasPermission(deviceName)
            }

            override fun open(deviceName: String?): ParcelFileDescriptor? {
                enforceCaller()
                return session.open(deviceName)
            }

            override fun write(
                data: ByteArray?,
                timeoutMs: Int,
            ): Int {
                enforceCaller()
                return session.write(data, timeoutMs)
            }

            override fun close() {
                enforceCaller()
                session.close("closed by display app")
            }

            override fun getStatus(): String {
                enforceCaller()
                return describe()
            }

            override fun requestPermission(deviceName: String?): Int {
                enforceCaller()
                return prompt.request(deviceName)
            }
        }

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        session = UsbSession(usbManager)
        callers = CallerVerifier(this, BuildConfig.CALLER_CERTS)
        prompt = PermissionPrompt(this, usbManager).apply { register() }
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(detachReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(detachReceiver, filter)
        }
        BridgeStats.event("service created")
        Log.i(TAG, "Bridge service created: ${describe()}")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        // Last client gone (display app stopped or died) — release the adapter.
        session.close("display app unbound")
        callers.forget()
        return false
    }

    override fun onDestroy() {
        session.close("bridge service destroyed")
        unregisterReceiver(detachReceiver)
        prompt.unregister()
        super.onDestroy()
    }

    private fun enforceCaller() {
        val reason = callers.rejectReason(Binder.getCallingUid()) ?: return
        Log.w(TAG, "Rejected caller: $reason")
        BridgeStats.event("rejected $reason")
        throw SecurityException(reason)
    }

    /**
     * Everything needed to diagnose a missing grant without adb: whether the platform still
     * routes attaches to this package, which Android user we run as (the grant goes to the
     * user that was current at attach time), and which devices we hold permission for.
     */
    private fun describe(): String {
        val devices =
            usbManager.deviceList.values.joinToString(prefix = "[", postfix = "]") { device ->
                "${device.deviceName} ${hex4(device.vendorId)}:${hex4(device.productId)} " +
                    "perm=${usbManager.hasPermission(device)}"
            }
        val openDevice = session.openDeviceName
        val sessionText =
            when {
                openDevice != null -> "open($openDevice)"
                session.lastError.isNotEmpty() -> "closed(last=${session.lastCloseReason}; error=${session.lastError})"
                else -> "closed(last=${session.lastCloseReason})"
            }
        return "bridge api=${BridgeContract.API_VERSION} v=${BuildConfig.VERSION_NAME} " +
            "user=${Process.myUid() / PER_USER_RANGE} handler=${fixedHandlerComponent()} " +
            "devices=$devices session=$sessionText ${prompt.describe()} ${BridgeStats.describe()}"
    }

    @SuppressLint("DiscouragedApi")
    private fun fixedHandlerComponent(): String {
        val resources = Resources.getSystem()
        val id = resources.getIdentifier("config_UsbDeviceConnectionHandling_component", "string", "android")
        if (id == 0) return "<no such resource>"
        return resources.getString(id).ifEmpty { "<empty>" }
    }

    private fun hex4(value: Int): String = value.toString(16).padStart(4, '0')

    private companion object {
        const val TAG = "Carlink2Bridge"

        // UserHandle.PER_USER_RANGE (hidden): uid = userId * 100000 + appId.
        const val PER_USER_RANGE = 100_000
    }
}
