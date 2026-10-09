package com.enigy.carlink2.bridge

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Decides whether a Binder caller may drive the adapter.
 *
 * An open bridge would let any app on the head unit talk to the CPC200, including pushing
 * binaries the adapter executes (SendFile to /tmp/bin). The caller must be
 * [BridgeContract.DISPLAY_PACKAGE]; when [certsConfig] lists fingerprints, it must also be
 * signed by one of them.
 */
internal class CallerVerifier(
    private val context: Context,
    certsConfig: String,
) {
    private val allowedCerts: List<ByteArray> =
        certsConfig
            .split(',')
            .map { it.replace(":", "").replace(" ", "").trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull(::parseSha256)

    // Verification needs two PackageManager calls; write() runs per adapter message.
    private val verifiedUids = ConcurrentHashMap.newKeySet<Int>()

    init {
        if (allowedCerts.isEmpty()) {
            Log.w(TAG, "No caller certificate pinned — accepting ${BridgeContract.DISPLAY_PACKAGE} by package name")
        }
    }

    /** Null when allowed; otherwise why not. */
    fun rejectReason(uid: Int): String? {
        if (uid in verifiedUids) return null
        val pm = context.packageManager
        val packages = pm.getPackagesForUid(uid)?.toList().orEmpty()
        if (BridgeContract.DISPLAY_PACKAGE !in packages) {
            return "uid $uid ($packages) is not ${BridgeContract.DISPLAY_PACKAGE}"
        }
        if (allowedCerts.isNotEmpty() &&
            allowedCerts.none { pm.hasSigningCertificate(uid, it, PackageManager.CERT_INPUT_SHA256) }
        ) {
            return "${BridgeContract.DISPLAY_PACKAGE} (uid $uid) is not signed by a pinned certificate"
        }
        verifiedUids += uid
        return null
    }

    /** Package UIDs can be reassigned after an uninstall; re-verify after clients go away. */
    fun forget() {
        verifiedUids.clear()
    }

    private fun parseSha256(hex: String): ByteArray? {
        if (hex.length != 64) {
            Log.e(TAG, "Ignoring caller cert '$hex': expected 64 hex characters")
            return null
        }
        return try {
            ByteArray(32) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            Log.e(TAG, "Ignoring caller cert '$hex': ${e.message}")
            null
        }
    }

    private companion object {
        const val TAG = "Carlink2Bridge"
    }
}
