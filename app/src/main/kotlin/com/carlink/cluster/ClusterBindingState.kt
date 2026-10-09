package com.carlink.cluster

/**
 * Cross-boundary flag tracking whether a live [ClusterMainSession] currently holds the
 * Templates Host binding. MainActivity cannot directly query the out-of-process
 * CarAppActivity session, so this shared object substitutes for that lookup.
 *
 * Writes (ClusterMainSession):
 * - `true` in onCreateScreen when a session starts.
 * - `false` in the session's onDestroy observer, but only for the *primary* session AND only
 *   when it is still the current primary (a superseded late teardown leaves the flag alone;
 *   secondary sessions never touch it).
 *
 * Writes (MainActivity.restartClusterBinding):
 * - `false` immediately after finishing the CarAppActivity task, so a delayed/absent onDestroy
 *   can't leave the flag stuck `true` and deadlock the relaunch guard below.
 *
 * Reads (MainActivity.launchCarAppActivity):
 * - If `true`, the launch is deferred and retried after 4s, preventing a second
 *   CarAppActivity from being started while the Host is still tearing down the old
 *   session (which would cause the Host to reject the new bind).
 *
 * @Volatile: onDestroy can be dispatched from a Host-owned thread, so the MainActivity
 * UI-thread read must see the write without CPU-cache staleness.
 */
object ClusterBindingState {
    @Volatile
    var sessionAlive = false

    /**
     * `SystemClock.elapsedRealtime()` of the most recent successful `updateTrip()` relay
     * (0 = none since process start). Written by the primary [ClusterMainSession] on every relay;
     * read by CarlinkManager's [NAV_HEALTH] diagnostic as the nav-OUTPUT heartbeat (paired with
     * [com.carlink.navigation.NavigationStateManager.lastNaviJsonElapsedMs], the nav-INPUT
     * heartbeat). Together they localize a blank cluster: input fresh + output stale → relay/Host
     * side; both stale → nav stopped arriving from the adapter.
     */
    @Volatile
    var lastRelayElapsedMs: Long = 0L

    /**
     * Successful `updateTrip()` relays since process start. [NAV_HEALTH] logs it next to the
     * bridge's icon-provider counters: inserts climbing in step with relays mean the Templates
     * Host re-sends the maneuver icon on every distance tick ([201] HUD flicker).
     */
    val relayCount = java.util.concurrent.atomic.AtomicLong()
}
