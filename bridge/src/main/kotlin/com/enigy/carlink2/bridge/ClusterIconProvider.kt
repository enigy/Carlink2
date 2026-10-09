package com.enigy.carlink2.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Claims `com.google.android.apps.automotive.templates.host.ClusterIconContentProvider`.
 *
 * Moved here from the display app's ClusterIconShimProvider (upstream lvalen91/carlink).
 * GoogleTemplatesHost has this provider class but never registers it. When the host turns a
 * CarIcon maneuver icon into navigation state for the cluster it calls insert() on this
 * authority; the first failure latches `skipIcons = true` for the session and the HUD shows no
 * icons. Play rejects the authority for anyone but its first claimant, so the Play-installed
 * display app cannot declare it — the sideloaded bridge does.
 *
 * Contract the host expects:
 * - insert(): store PNG bytes for an iconId, return a content URI
 * - query(selection = iconId): return contentUri + aspectRatio
 * - openFile(): serve the PNG via pipe (with optional ?w=&h= scaling)
 *
 * Content-addressed URIs ([201], bridge v2): the display app sends the same icon on every
 * distance tick, and the host re-inserts it each time. URIs used to be keyed by the host's
 * iconId, so if that id changes per insert the HUD gets a "new" image every tick and reloads
 * it — the flicker. URIs are now keyed by a digest of the PNG bytes, so an unchanged icon keeps
 * an unchanged URI and the URI only changes when the picture does (a new step).
 *
 * Exported with grantUriPermissions because the host and cluster run as other UIDs. Content
 * is limited to maneuver-icon PNGs the host itself inserted; delete/update are no-ops.
 */
class ClusterIconProvider : ContentProvider() {
    // contentKey → PNG bytes. Access-order LRU; synchronizedMap so eviction runs under the lock.
    private val pngByContent: MutableMap<String, ByteArray> = lruMap(MAX_CONTENT_ENTRIES)

    // Host iconId → contentKey, for query(selection = iconId).
    private val contentByIconId: MutableMap<String, String> = lruMap(MAX_ICON_ID_ENTRIES)

    // "contentKey@WxH" → scaled PNG, so a HUD that re-opens the same URI every tick doesn't pay
    // a decode + scale + PNG encode each time.
    private val scaledPng: MutableMap<String, ByteArray> = lruMap(MAX_SCALED_ENTRIES)

    override fun onCreate(): Boolean {
        Log.i(TAG, "Cluster icon provider registered ($AUTHORITY)")
        return true
    }

    // insert() must NEVER return null: null latches the host's skipIcons for the session.
    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri {
        val iconId = values?.getAsString("iconId")
        val data = values?.getAsByteArray("data")
        if (iconId == null || data == null) {
            Log.w(TAG, "insert() missing iconId or data (iconId=$iconId, dataSize=${data?.size})")
            return Uri.parse("content://$AUTHORITY/img/unknown")
        }
        val contentKey = contentKey(data)
        BridgeStats.iconInserts.incrementAndGet()
        if (pngByContent.put(contentKey, data) == null) BridgeStats.iconDistinctContents.incrementAndGet()
        if (contentByIconId.put(iconId, contentKey) == null) BridgeStats.iconDistinctIds.incrementAndGet()
        return contentUri(contentKey)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(arrayOf("contentUri", "aspectRatio"))
        if (selection == null) return cursor

        val contentKey = contentByIconId[selection]
        val data = contentKey?.let { pngByContent[it] }
        if (contentKey == null || data == null) {
            BridgeStats.iconQueryMisses.incrementAndGet()
            return cursor
        }

        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        val aspectRatio = if (opts.outHeight > 0) opts.outWidth.toDouble() / opts.outHeight else 1.0
        cursor.addRow(arrayOf<Any>(contentUri(contentKey).toString(), aspectRatio))
        BridgeStats.iconQueryHits.incrementAndGet()
        return cursor
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor? {
        val contentKey = uri.lastPathSegment
        val data = contentKey?.let { pngByContent[it] }
        if (contentKey == null || data == null) {
            BridgeStats.iconOpenMisses.incrementAndGet()
            Log.w(TAG, "openFile() cache miss for $contentKey")
            return null
        }

        val targetW = uri.getQueryParameter("w")?.toIntOrNull()
        val targetH = uri.getQueryParameter("h")?.toIntOrNull()
        val output =
            if (targetW != null && targetH != null && targetW > 0 && targetH > 0) {
                val scaledKey = "$contentKey@${targetW}x$targetH"
                scaledPng[scaledKey] ?: scaleIcon(data, targetW, targetH).also { scaledPng[scaledKey] = it }
            } else {
                data
            }

        val pipe = ParcelFileDescriptor.createPipe()
        val writeEnd = pipe[1]
        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { it.write(output) }
            } catch (e: Exception) {
                Log.e(TAG, "openFile() pipe write error for $contentKey: ${e.message}")
            }
        }, "IconPipe").start()
        BridgeStats.iconOpens.incrementAndGet()
        return pipe[0]
    }

    private fun contentUri(contentKey: String): Uri = Uri.parse("content://$AUTHORITY/img/$contentKey")

    /** "png_" + first 16 hex chars of SHA-256 — plenty to tell maneuver icons apart. */
    private fun contentKey(png: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(png)
        val hex = StringBuilder("png_")
        for (i in 0 until 8) hex.append("%02x".format(digest[i]))
        return hex.toString()
    }

    private fun scaleIcon(
        png: ByteArray,
        w: Int,
        h: Int,
    ): ByteArray =
        try {
            val original = BitmapFactory.decodeByteArray(png, 0, png.size)
            if (original == null) {
                png
            } else {
                val scaled = Bitmap.createScaledBitmap(original, w, h, true)
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                if (scaled !== original) scaled.recycle()
                original.recycle()
                out.toByteArray()
            }
        } catch (e: Exception) {
            Log.w(TAG, "scaleIcon(${w}x$h) failed: ${e.message} — serving original")
            png
        }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun getType(uri: Uri): String = "image/png"

    private companion object {
        const val TAG = "Carlink2Bridge"
        const val AUTHORITY = BridgeContract.CLUSTER_ICON_AUTHORITY

        // Distinct maneuver pictures per trip are few; ~10-40 KB per PNG.
        const val MAX_CONTENT_ENTRIES = 20

        // The host may mint a new iconId per insert; ids are tiny, so keep a longer tail.
        const val MAX_ICON_ID_ENTRIES = 256
        const val MAX_SCALED_ENTRIES = 20

        fun <V> lruMap(maxEntries: Int): MutableMap<String, V> =
            Collections.synchronizedMap(
                object : LinkedHashMap<String, V>(maxEntries, 0.75f, true) {
                    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean = size > maxEntries
                },
            )
    }
}
