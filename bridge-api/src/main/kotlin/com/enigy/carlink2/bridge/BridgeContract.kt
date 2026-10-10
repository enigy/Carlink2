package com.enigy.carlink2.bridge

/**
 * Constants shared by the display app and the sideloaded USB bridge.
 *
 * Why two apps: on AAOS the platform hands every USB host attach to a fixed handler package
 * (`config_UsbDeviceConnectionHandling_component` =
 * `android.car.usb.handler/android.car.usb.handler.UsbHostManagementActivity` in the car product
 * overlay). UsbProfileGroupSettingsManager.deviceAttachedForFixedHandler() grants that package's
 * UID permission for the device and then tries to start the activity. GM ships no app with that
 * package name, so a sideloaded app that takes it receives every grant without a dialog. The
 * display app has to stay Play-installed to keep distraction-optimized display while driving,
 * so it cannot take that name itself.
 *
 * The bridge also claims the Templates Host's ClusterIconContentProvider authority, which the
 * host calls but never registers, so cluster maneuver icons reach the HUD for a Play-installed
 * display app.
 */
object BridgeContract {
    /** Bump when ICarlinkBridge gains methods; the display app checks [MIN_API_VERSION]. */
    const val API_VERSION = 2

    /** Oldest bridge API the display app can drive. */
    const val MIN_API_VERSION = 1

    /** First bridge API with ICarlinkBridge.requestPermission ([205]). */
    const val PERMISSION_PROMPT_API_VERSION = 2

    // ICarlinkBridge.requestPermission results.
    const val PERMISSION_GRANTED = 0
    const val PERMISSION_PENDING = 1
    const val PERMISSION_DENIED = 2
    const val PERMISSION_NO_DEVICE = 3

    const val BRIDGE_PACKAGE = "android.car.usb.handler"
    const val BRIDGE_SERVICE_CLASS = "com.enigy.carlink2.bridge.BridgeService"

    /** The only package the bridge serves. Play guarantees nobody else can publish it. */
    const val DISPLAY_PACKAGE = "com.enigy.carlink2"

    const val CLUSTER_ICON_AUTHORITY =
        "com.google.android.apps.automotive.templates.host.ClusterIconContentProvider"

    /** CPC200 framing the bridge relies on: 16-byte header, magic at 0, payload length at 4. */
    const val HEADER_SIZE = 16
    const val PROTOCOL_MAGIC = 0x55aa55aa
    const val MAX_PAYLOAD_SIZE = 2 * 1024 * 1024

    /**
     * Largest single write() accepted. Binder transactions share a 1 MB buffer per process;
     * the biggest message the display app sends today is the ~234 KB ARMiPhoneIAP2 upload.
     */
    const val MAX_WRITE_BYTES = 512 * 1024
}
