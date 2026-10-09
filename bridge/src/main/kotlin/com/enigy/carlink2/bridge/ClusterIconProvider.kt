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
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Claims `com.google.android.apps.automotive.templates.host.ClusterIconContentProvider`.
 *
 * Moved here unchanged in behavior from the display app's ClusterIconShimProvider (upstream
 * lvalen91/carlink). GoogleTemplatesHost has this provider class but never registers it. When
 * the host turns a CarIcon maneuver icon into navigation state for the cluster it calls
 * insert() on this authority; the first failure latches `skipIcons = true` for the session
 * and the HUD shows no icons. Play rejects the authority for anyone but its first claimant,
 * so the Play-installed display app cannot declare it — the sideloaded bridge does.
 *
 * Contract the host expects:
 * - insert(): cache PNG bytes keyed by iconId
 * - query(): return contentUri + aspectRatio
 * - openFile(): serve cached PNG via pipe (with optional scaling)
 *
 * Exported with grantUriPermissions because the host and cluster run as other UIDs. Content
 * is limited to maneuver-icon PNGs the host itself inserted; delete/update are no-ops.
 */
class ClusterIconProvider : ContentProvider() {
    // Access-order LRU; synchronizedMap so the eviction callback runs under the same lock.
    private val iconCache: MutableMap<String, ByteArray> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, ByteArray>(MAX_CACHE_SIZE, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > MAX_CACHE_SIZE
            },
        )

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
        val cacheKey = "cluster_icon_$iconId"
        iconCache[cacheKey] = data
        BridgeStats.iconInserts.incrementAndGet()
        return Uri.parse("content://$AUTHORITY/img/$cacheKey")
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

        val cacheKey = "cluster_icon_$selection"
        val data = iconCache[cacheKey]
        if (data == null) {
            BridgeStats.iconQueryMisses.incrementAndGet()
            return cursor
        }

        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        val aspectRatio = if (opts.outHeight > 0) opts.outWidth.toDouble() / opts.outHeight else 1.0
        cursor.addRow(arrayOf<Any>("content://$AUTHORITY/img/$cacheKey", aspectRatio))
        BridgeStats.iconQueryHits.incrementAndGet()
        return cursor
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor? {
        val cacheKey = uri.lastPathSegment
        val data = cacheKey?.let { iconCache[it] }
        if (data == null) {
            BridgeStats.iconOpenMisses.incrementAndGet()
            Log.w(TAG, "openFile() cache miss for $cacheKey")
            return null
        }

        val targetW = uri.getQueryParameter("w")?.toIntOrNull()
        val targetH = uri.getQueryParameter("h")?.toIntOrNull()
        val output =
            if (targetW != null && targetH != null && targetW > 0 && targetH > 0) {
                scaleIcon(data, targetW, targetH)
            } else {
                data
            }

        val pipe = ParcelFileDescriptor.createPipe()
        val writeEnd = pipe[1]
        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { it.write(output) }
            } catch (e: Exception) {
                Log.e(TAG, "openFile() pipe write error for $cacheKey: ${e.message}")
            }
        }, "IconPipe").start()
        BridgeStats.iconOpens.incrementAndGet()
        return pipe[0]
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

        // Maneuver-icon variety per trip is small; ~10-40 KB per PNG.
        const val MAX_CACHE_SIZE = 20
    }
}
