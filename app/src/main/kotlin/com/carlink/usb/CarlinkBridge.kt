package com.carlink.usb

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
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

    private fun bridgeIntent(): Intent =
        Intent().setComponent(ComponentName(BridgeContract.BRIDGE_PACKAGE, BridgeContract.BRIDGE_SERVICE_CLASS))

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
                if (!appContext.bindService(bridgeIntent(), newConnection, Context.BIND_AUTO_CREATE)) {
                    runCatching { appContext.unbindService(newConnection) }
                    lastFailure = "bindService refused (bridge not installed, or not visible to this app)"
                    return@withLock null
                }
                connection = newConnection
            }
            // Otherwise a binding exists and the system is restarting the bridge; wait for it.

            val bridge = withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() }
            // Still active = timed out; completed with null = the connection callbacks set the reason.
            if (bridge == null && deferred.isActive) lastFailure = "timed out binding the bridge"
            bridge
        }

    private fun createConnection(appContext: Context): ServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                binder: IBinder,
            ) {
                val bridge = ICarlinkBridge.Stub.asInterface(binder)
                service = bridge
                lastFailure = ""
                logInfo("[BRIDGE] Connected to ${name.flattenToShortString()}", tag = Logger.Tags.USB)
                pending?.complete(bridge)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
                logWarn("[BRIDGE] Bridge process died — waiting for the system to restart it", tag = Logger.Tags.USB)
            }

            override fun onBindingDied(name: ComponentName) {
                // Bridge reinstalled or disabled: this binding will never reconnect.
                service = null
                connection = null
                runCatching { appContext.unbindService(this) }
                lastFailure = "binding died (bridge reinstalled?)"
                logWarn("[BRIDGE] Binding died — will rebind on next connect", tag = Logger.Tags.USB)
                pending?.complete(null)
            }

            override fun onNullBinding(name: ComponentName) {
                lastFailure = "bridge returned a null binder"
                pending?.complete(null)
            }
        }
}
