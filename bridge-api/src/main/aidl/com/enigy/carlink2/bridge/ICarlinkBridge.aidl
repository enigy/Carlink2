package com.enigy.carlink2.bridge;

import android.os.ParcelFileDescriptor;

/**
 * Display app (com.enigy.carlink2) -> USB bridge (android.car.usb.handler).
 *
 * The bridge owns the CPC200 USB device. Inbound adapter traffic is not delivered over
 * Binder: open() returns the read end of a socket that carries complete protocol messages
 * (16-byte header + payload), back to back, in arrival order. Outbound traffic goes
 * through write(), one adapter message per call.
 *
 * Methods other than getApiVersion() throw SecurityException for callers that are not the
 * display app. Add new methods at the END only — transaction codes follow declaration order,
 * and the two apps are updated independently.
 */
interface ICarlinkBridge {
    /** BridgeContract.API_VERSION of the installed bridge build. */
    int getApiVersion();

    /** True when the bridge's UID holds USB permission for deviceName (a /dev/bus/usb path). */
    boolean hasPermission(String deviceName);

    /**
     * Open deviceName, claim its bulk interface and start forwarding inbound messages.
     * Returns the read end of the message socket, or null on failure (reason in getStatus()).
     * A session that is already open is closed first. EOF on the socket means the session ended.
     */
    ParcelFileDescriptor open(String deviceName);

    /** Bulk OUT transfer of one message. Returns bytes written, or -1 like bulkTransfer(). */
    int write(in byte[] data, int timeoutMs);

    /** Close the current session, if any. */
    void close();

    /** One-line diagnostics: handler config, user, USB permission, session and icon counters. */
    String getStatus();
}
