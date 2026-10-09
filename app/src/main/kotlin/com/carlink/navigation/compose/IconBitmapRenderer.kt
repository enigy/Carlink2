package com.carlink.navigation.compose

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Renders a [ComposedIcon] (background + foreground paths) to a [Bitmap] suitable for
 * `CarIcon.createWithBitmap()`. Two-pass paint: background first in grey, then foreground
 * in white — matches Apple's MapKit two-tone scheme (secondaryLabelColor + labelColor).
 *
 * Stateless static functions, safe to call from any thread.
 *
 * Color scheme matches Apple Maps' card icons:
 *   - background  → 0xFFB0B0B0 (semantic = secondaryLabelColor on dark theme; ~70% grey)
 *   - foreground  → 0xFFFFFFFF (semantic = labelColor on dark theme; pure white)
 *
 * Adjust [FOREGROUND_COLOR] / [BACKGROUND_COLOR] if rendering on a light-themed cluster —
 * but cluster context on GM/AAOS is always dark, so white-on-grey-on-transparent is the
 * universal choice.
 */
internal object IconBitmapRenderer {
    /**
     * Share of the bitmap's side the icon's longer bbox edge fills ([202]).
     *
     * Each icon is scaled to its own bounds, the way the static fallback AVDs
     * (res/drawable/cp_maneuver_*.xml) are cropped: their viewports sit ~2pt outside the glyph,
     * so the glyph fills ~92% of the long side (right turn 47.75/51.75, straight 47.27/51.27,
     * U-turn 49.6/53.6). Matching that makes a composed icon the same size on the HUD as the
     * fallback the first step shows while the route is still composing.
     *
     * History: this used a fixed 160pt viewport (sized before per-icon centering, when one
     * viewport had to hold every arrow around Apple's (28, 28) origin), which left glyphs
     * filling about a third of the bitmap; on-truck the composed icons read ~half the size of
     * the first (fallback) icon. [201] tried a fixed 80pt viewport (2×); fitting to bounds
     * matches the fallback exactly instead. Trade-off: stroke weight now varies a little
     * between icons, as it already does across the AVD set.
     */
    private const val GLYPH_FILL = 0.92f

    /**
     * Apple's nominal canvas is centered on (28, 28) but the actual icon bbox is NOT
     * always centered there. e.g. LEFT_TURN bbox center is at x=-21.6 (49pt left of canvas
     * center), RIGHT_TURN at x=52.6 (25pt right). Translating Apple's (28, 28) to the
     * viewport center produces visually off-center bitmaps — pure LEFT_TURN ends up at
     * the left edge of the bitmap and looks clipped when the cluster displays it.
     *
     * We compute each icon's actual bbox at render time and translate to center THAT.
     * Roundabouts have a background ring naturally centered on (28, 28); their union bbox
     * stays roughly centered, so the change is a no-op for them. Simple turns get a
     * per-icon translation so the arrow sits in the visual center regardless of which
     * direction it points.
     */

    /** Foreground color: the path the driver should take (Apple's `labelColor` on dark). */
    private const val FOREGROUND_COLOR = Color.WHITE

    /**
     * Background color: the rest of the junction (Apple's `secondaryLabelColor` on dark).
     *
     * NOTE: Cluster ICs on AAOS typically render at ~88-120 dp (compact size) on a dark
     * background. A 70% grey (#B0B0B0) is hard to see at that scale against the cluster's
     * typically very-dark theme. Apple's iOS uses ~60% opacity on white = #999999 effective,
     * which IS more visible at small sizes. Tuned to match the visual weight Apple's icons
     * achieve at the cluster's render size.
     */
    private val BACKGROUND_COLOR = Color.argb(0xFF, 0x99, 0x99, 0x99)

    fun render(
        size: Int,
        icon: ComposedIcon,
        foregroundColor: Int = FOREGROUND_COLOR,
        backgroundColor: Int = BACKGROUND_COLOR,
        canvasBackground: Int = Color.TRANSPARENT,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (canvasBackground != Color.TRANSPARENT) canvas.drawColor(canvasBackground)

        val bbox = RectF()
        icon.foreground.computeBounds(bbox, true)
        icon.background?.let {
            val bgBox = RectF()
            it.computeBounds(bgBox, true)
            bbox.union(bgBox)
        }
        val centerX = (bbox.left + bbox.right) / 2.0f
        val centerY = (bbox.top + bbox.bottom) / 2.0f

        // Fit the longer bbox edge to GLYPH_FILL of the bitmap — same cropping as the AVDs.
        val extent = maxOf(bbox.width(), bbox.height()).coerceAtLeast(1.0f)
        val scale = size * GLYPH_FILL / extent

        val matrix = Matrix().apply {
            setTranslate(-centerX, -centerY)
            postScale(scale, scale)
            postTranslate(size / 2.0f, size / 2.0f)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        // Background pass — grey junction shape (ring + non-exit spokes + entry)
        icon.background?.let { bg ->
            val scaled = Path().also { bg.transform(matrix, it) }
            paint.color = backgroundColor
            canvas.drawPath(scaled, paint)
        }

        // Foreground pass — white path (exit + arrowhead, or whole shape for simple maneuvers)
        val scaledFg = Path().also { icon.foreground.transform(matrix, it) }
        paint.color = foregroundColor
        canvas.drawPath(scaledFg, paint)

        return bmp
    }
}
