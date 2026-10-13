package com.carlink.usb

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.enigy.carlink2.bridge.BridgeContract
import com.enigy.carlink2.bridge.ICarlinkBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-wide binding to the sideloaded USB bridge.
 *
 * Bound once and kept for the life of the process: the binding is what keeps the bridge
 * process alive and at our priority. If the bridge crashes, the system rebinds on its own and
 * [ServiceConnection.onServiceConnected] fires again; if it is reinstalled, the binding dies
 * and the next [connect] binds afresh.
 */
object CarlinkBridge {
    private const val BIND_TIMEOUT_MS = 5000L

    private val bindLock = Mutex()

    @Volatile private var service: ICarlinkBridge? = null

    @Volatile private var connection: ServiceConnection? = null

    @Volatile private var pending: CompletableDeferred<ICarlinkBridge?>? = null

    /** Why the last [connect] returned null. */
    @Volatile var lastFailure: String = ""
        private set

    /** [211] [connect] calls in a row that got no bridge; 0 once one connects. */
    @Volatile var consecutiveFailures: Int = 0
        private set

    private fun bridgeIntent(): Intent =
        Intent().setComponent(ComponentName(BridgeContract.BRIDGE_PACKAGE, BridgeContract.BRIDGE_SERVICE_CLASS))

    /**
     * The bridge's status line if a binding is already up; never binds. A blocking Binder
     * call — keep it off the main thread and hot paths.
     */
    fun statusIfBound(): String? =
        service?.takeIf { it.asBinder().isBinderAlive }?.let { runCatching { it.status }.getOrNull() }

    /** Needs the `<queries>` entry for the bridge package in the manifest. */
    fun isInstalled(context: Context): Boolean = context.packageManager.resolveService(bridgeIntent(), 0) != null

    suspend fun connect(context: Context): ICarlinkBridge? =
        bindLock.withLock {
            service?.takeIf { it.asBinder().isBinderAlive }?.let { return@withLock it }

            val deferred = CompletableDeferred<ICarlinkBridge?>()
            pending = deferred
            if (connection == null) {
                val appContext = context.applicationContext
                val newConnection = createConnection(appContext)
                // Set before binding: the callbacks ignore any binding that isn't the current one,
                // and onServiceConnected can arrive (on the main thread) before bindService returns.
                connection = newConnection
                if (!appContext.bindService(bridgeIntent(), newConnection, Context.BIND_AUTO_CREATE)) {
                    connection = null
                    runCatching { appContext.unbindService(newConnection) }
                    lastFailure = "bindService refused (bridge not installed, or not visible to this app)"
                    consecutiveFailures++
                    return@withLock null
                }
            }
            // Otherwise a binding exists and the system is restarting the bridge; wait for it.

            val bridge = withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() }
            if (bridge == null) {
                consecutiveFailures++
                // Still active = timed out; completed with null = the connection callbacks set the reason.
                if (deferred.isActive) {
                    lastFailure = "timed out binding the bridge"
                    // [211] Don't wait on this binding again. Truck log 2026-10-13: after the bridge
                    // was reinstalled a binding never connected, and every attempt for two minutes
                    // timed out waiting on that same binding. The next connect() binds afresh.
                    dropBinding(context.applicationContext)
                }
            }
            bridge
        }

    /**
     * [211] Forget the binding so the next [connect] binds afresh: when the bridge has just been
     * installed, and on Reset Device. Don't call while a transport is open through it.
     */
    fun reset(context: Context) {
        dropBinding(context.applicationContext)
        lastFailure = ""
        consecutiveFailures = 0
    }

    private fun dropBinding(appContext: Context) {
        connection?.let { runCatching { appContext.unbindService(it) } }
        connection = null
        service = null
    }

    /**
     * [211] Open the bridge's App info page in Settings, where it can be force-stopped. Returns
     * false if Settings has no such page.
     */
    fun openAppInfo(context: Context): Boolean =
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", BridgeContract.BRIDGE_PACKAGE, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            logInfo("[BRIDGE_SETUP] Opened the bridge's App info", tag = Logger.Tags.USB)
            true
        } catch (e: ActivityNotFoundException) {
            logWarn("[BRIDGE_SETUP] No App info page for the bridge: ${e.message}", tag = Logger.Tags.USB)
            false
        }

    private fun createConnection(appContext: Context): ServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                binder: IBinder,
            ) {
                // [211] A binding dropped by dropBinding() answering late must not stand in for
                // the current one; the same goes for the callbacks below.
                if (connection !== this) return
                val bridge = ICarlinkBridge.Stub.asInterface(binder)
                service = bridge
                lastFailure = ""
                consecutiveFailures = 0
                logInfo("[BRIDGE] Connected to ${name.flattenToShortString()}", tag = Logger.Tags.USB)
                pending?.complete(bridge)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                if (connection !== this) return
                service = null
                logWarn("[BRIDGE] Bridge process died — waiting for the system to restart it", tag = Logger.Tags.USB)
            }

            override fun onBindingDied(name: ComponentName) {
                // Bridge reinstalled or disabled: this binding will never reconnect.
                runCatching { appContext.unbindService(this) }
                if (connection !== this) return
                service = null
                connection = null
                lastFailure = "binding died (bridge reinstalled?)"
                logWarn("[BRIDGE] Binding died — will rebind on next connect", tag = Logger.Tags.USB)
                pending?.complete(null)
            }

            override fun onNullBinding(name: ComponentName) {
                if (connection !== this) return
                lastFailure = "bridge returned a null binder"
                pending?.complete(null)
            }
        }
}
