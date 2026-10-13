package com.carlink.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn

/**
 * Opens the head unit's built-in Files app (DocumentsUI). Car makers often hide it from the app
 * list but keep it installed, still allowed to hand APKs to the package installer, so it is how
 * the USB bridge gets installed from a USB drive ([210]).
 *
 * Same lookup as muzso/fileslauncher: the launch intent of either package name, else their known
 * activities. `com.android.documentsui` is AOSP's name; `com.google.android.documentsui` is the
 * Android Studio automotive emulator's. Needs the `<queries>` entries in the manifest. Files is
 * not distraction-optimized, so while driving the system covers it with its own blocking screen.
 */
object FilesLauncher {
    private val PACKAGES = listOf("com.android.documentsui", "com.google.android.documentsui")

    private val ACTIVITIES =
        listOf(
            "com.android.documentsui" to "com.android.documentsui.files.FilesActivity",
            "com.android.documentsui" to "com.android.documentsui.LauncherActivity",
            "com.google.android.documentsui" to "com.android.documentsui.LauncherActivity",
        )

    /** Starts Files and returns true, or returns false if no candidate could be started. */
    fun open(context: Context): Boolean {
        val pm = context.packageManager
        val candidates =
            PACKAGES.mapNotNull { pm.getLaunchIntentForPackage(it) } +
                ACTIVITIES.map { (pkg, cls) -> Intent().setClassName(pkg, cls) }
        for (intent in candidates) {
            val target = intent.component?.flattenToShortString() ?: intent.`package`
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                logInfo("[BRIDGE_SETUP] Opened Files ($target)", tag = Logger.Tags.USB)
                return true
            } catch (e: ActivityNotFoundException) {
                // Not on this head unit; try the next one.
            } catch (e: SecurityException) {
                logWarn("[BRIDGE_SETUP] Files ($target) refused: ${e.message}", tag = Logger.Tags.USB)
            }
        }
        logWarn("[BRIDGE_SETUP] No Files app could be opened (tried ${ACTIVITIES.size + PACKAGES.size} targets)", tag = Logger.Tags.USB)
        return false
    }
}
