package com.carlink.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.carlink.MainActivity
import com.carlink.R

/**
 * [212] The way back to Carlink2 from the Files app, which can't be given a button of its own and
 * offers no route back (on the truck only Home, then Carlink2, worked).
 *
 * - Opening Files from the bridge setup panel posts a quiet notification, there in the
 *   notification list the whole time: tap to return.
 * - When the bridge package is installed, it is replaced by a heads-up one: tap to return and
 *   connect.
 * - MainActivity cancels it on every resume.
 *
 * A notification tap may start an activity from the background; the app itself mostly may not,
 * so Carlink2 can't simply bring itself back when the install finishes.
 */
object BridgeSetupNotification {
    private const val WAITING_CHANNEL_ID = "bridge_setup_waiting"
    private const val INSTALLED_CHANNEL_ID = "bridge_setup_installed"
    private const val NOTIFICATION_ID = 2120

    fun showWaiting(context: Context) =
        post(
            context,
            NotificationCompat.Builder(context, WAITING_CHANNEL_ID)
                .setContentTitle("Installing the USB bridge")
                .setContentText("Tap to return to Carlink2")
                .setPriority(NotificationCompat.PRIORITY_LOW),
        )

    fun showInstalled(context: Context) =
        post(
            context,
            NotificationCompat.Builder(context, INSTALLED_CHANNEL_ID)
                .setContentTitle("USB bridge installed")
                .setContentText("Tap to return to Carlink2 and connect")
                .setPriority(NotificationCompat.PRIORITY_HIGH),
        )

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun post(
        context: Context,
        builder: NotificationCompat.Builder,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(WAITING_CHANNEL_ID, "USB bridge setup", NotificationManager.IMPORTANCE_LOW),
        )
        // HIGH so it pops up over Files when the install finishes.
        manager.createNotificationChannel(
            NotificationChannel(INSTALLED_CHANNEL_ID, "USB bridge installed", NotificationManager.IMPORTANCE_HIGH),
        )
        val returnIntent =
            PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        manager.notify(
            NOTIFICATION_ID,
            builder
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(returnIntent)
                .setAutoCancel(true)
                .build(),
        )
    }
}
