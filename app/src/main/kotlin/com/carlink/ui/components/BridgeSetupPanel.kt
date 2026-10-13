package com.carlink.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.carlink.ui.theme.AutomotiveDimens

/** Where the bridge APK is published; shown so a new user can put it on a USB drive. */
private const val BRIDGE_APK_LOCATION = "github.com/enigy/Carlink2 → app/carlink2_bridge.apk"

private val TextColor = Color(0xFFDDE4E5) // same fixed light color as the overlay status text

/**
 * [210] Shown on the connecting screen in place of the status text while the USB bridge
 * (android.car.usb.handler) isn't installed. Without it this app can't reach the adapter, so
 * explain that and offer the head unit's built-in Files app to install it from a USB drive.
 * MainActivity re-checks on resume, so the panel goes away once the user comes back.
 */
@Composable
fun BridgeSetupPanel(onOpenFiles: () -> Boolean) {
    SetupPanel(
        title = "USB bridge not installed",
        body =
            "Carlink2 needs its USB bridge app to talk to the adapter. To install it while parked:\n" +
                "1. Copy carlink2_bridge.apk to a USB drive ($BRIDGE_APK_LOCATION).\n" +
                "2. Plug the drive into a free USB port.\n" +
                "3. Tap Open Files, open the drive, and tap the APK. If asked, allow Files to install apps.\n" +
                "Then come back here; Carlink2 connects on its own.",
        buttonLabel = "Open Files",
        buttonIcon = Icons.Default.FolderOpen,
        failureText = "This head unit's Files app couldn't be opened. Install the bridge with adb instead.",
        onClick = onOpenFiles,
    )
}

/**
 * [211] Shown instead while the bridge is installed but several fresh attempts to reach it got
 * no answer. Carlink2 keeps retrying underneath; this says what the user can do meanwhile.
 */
@Composable
fun BridgeNotStartingPanel(onOpenAppInfo: () -> Boolean) {
    SetupPanel(
        title = "USB bridge isn't starting",
        body =
            "The USB bridge is installed but isn't answering Carlink2, which keeps retrying.\n" +
                "Tap Open App info, then Force stop, and come back here.\n" +
                "If it still doesn't connect, restart the head unit.",
        buttonLabel = "Open App info",
        buttonIcon = Icons.Default.Info,
        failureText = "Settings has no App info page for the bridge. Restart the head unit.",
        onClick = onOpenAppInfo,
    )
}

@Composable
private fun SetupPanel(
    title: String,
    body: String,
    buttonLabel: String,
    buttonIcon: ImageVector,
    failureText: String,
    onClick: () -> Boolean,
) {
    var failed by remember { mutableStateOf(false) }
    Column(
        modifier =
            Modifier
                .widthIn(max = 960.dp)
                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(24.dp))
                .padding(32.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = TextColor,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = TextColor,
        )
        Spacer(modifier = Modifier.height(24.dp))
        FilledTonalButton(
            onClick = { failed = !onClick() },
            modifier = Modifier.height(AutomotiveDimens.ButtonMinHeight),
            contentPadding =
                PaddingValues(
                    horizontal = AutomotiveDimens.ButtonPaddingHorizontal,
                    vertical = AutomotiveDimens.ButtonPaddingVertical,
                ),
        ) {
            Icon(
                imageVector = buttonIcon,
                contentDescription = null,
                modifier = Modifier.size(AutomotiveDimens.IconSize),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = buttonLabel, style = MaterialTheme.typography.titleLarge)
        }
        if (failed) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = failureText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
