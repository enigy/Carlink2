package com.carlink.ui.components

import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.carlink.ui.settings.DisplayMode
import com.carlink.ui.settings.DisplayModePreference

/**
 * [230] Gives a dialog's window Carlink2's display mode.
 *
 * Compose's Dialog and AlertDialog open in a window of their own, which starts with the system
 * bars showing. Since Android 11 the focused window decides whether the bars show, so while a
 * dialog had focus the GM head unit put its navigation bar back on the left side (opening Adapter
 * Configuration, truck 2026-10-16) — what [214] fixed for CarAppActivity's window. Call this
 * inside the dialog's content (any slot of an AlertDialog); it draws nothing.
 *
 * [mode] defaults to the saved display mode; the Display Mode dialog passes its preview.
 */
@Composable
fun DialogSystemBars(mode: DisplayMode? = null) {
    val view = LocalView.current
    val context = LocalContext.current
    val target = mode ?: remember { DisplayModePreference.getInstance(context).getDisplayModeSync() }
    DisposableEffect(view, target) {
        (view.parent as? DialogWindowProvider)?.window?.let { target.applySystemBars(it) }
        onDispose {}
    }
}

/** Shows or hides [window]'s system bars as this display mode does (bars only, not layout). */
fun DisplayMode.applySystemBars(window: Window) {
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    when (this) {
        DisplayMode.SYSTEM_UI_VISIBLE -> controller.show(WindowInsetsCompat.Type.systemBars())
        DisplayMode.STATUS_BAR_HIDDEN -> {
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.show(WindowInsetsCompat.Type.navigationBars())
        }
        DisplayMode.NAV_BAR_HIDDEN -> {
            controller.show(WindowInsetsCompat.Type.statusBars())
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        }
        DisplayMode.FULLSCREEN_IMMERSIVE -> controller.hide(WindowInsetsCompat.Type.systemBars())
    }
    if (this != DisplayMode.SYSTEM_UI_VISIBLE) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
