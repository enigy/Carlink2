package com.carlink.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * [229] The settings page's arrival progress, provided by CarlinkApp while it animates the page in
 * and out. Read in the layer phase (inside graphicsLayer), so the animation never recomposes.
 * Without a provider the page is simply in place.
 */
internal val LocalSettingsArrival = staticCompositionLocalOf<() -> Float> { { 1f } }

/** [229] The page content's part of [SettingsFlyIn]: scale, fade and blur from [progress]. */
internal fun Modifier.settingsFlyIn(progress: () -> Float): Modifier =
    graphicsLayer {
        val p = progress()
        val scale = SettingsFlyIn.contentScale(p)
        scaleX = scale
        scaleY = scale
        alpha = SettingsFlyIn.contentAlpha(p)
        val radius = SettingsFlyIn.contentBlurDp(p).dp.toPx()
        renderEffect = if (radius >= 0.5f) BlurEffect(radius, radius, TileMode.Decal) else null
    }

/**
 * [229] The settings page's arrival, as functions of one progress value p (0 = away, 1 = in place;
 * the caller animates p with [ENTER_MS] / [EXIT_MS]). Two layers move differently:
 *  - the page content flies in from behind the driver: it starts [START_SCALE]x, see-through and
 *    blurred, and shrinks into place as it sharpens. Leaving runs the same path back toward them;
 *  - the frosted glass doesn't move. It fades in over the CarPlay screen (or the connecting
 *    screen) while its frost thickens from [GLASS_BLUR_START_DP] to the glass's full blur.
 */
internal object SettingsFlyIn {
    const val ENTER_MS = 600
    const val EXIT_MS = 450

    const val START_SCALE = 1.35f
    const val CONTENT_BLUR_DP = 18f
    const val GLASS_BLUR_START_DP = 8f

    // The content stays clear of the first 15 % (it's still past the driver's shoulder) and is
    // fully opaque by 75 %; the glass is fully in by 60 %, so the page lands on frost.
    private const val CONTENT_FADE_START = 0.15f
    private const val CONTENT_FADE_SPAN = 0.6f
    private const val GLASS_FADE_SPAN = 0.6f

    fun contentScale(p: Float): Float = START_SCALE + (1f - START_SCALE) * p

    fun contentAlpha(p: Float): Float = ((p - CONTENT_FADE_START) / CONTENT_FADE_SPAN).coerceIn(0f, 1f)

    fun contentBlurDp(p: Float): Float = CONTENT_BLUR_DP * (1f - p.coerceIn(0f, 1f))

    fun glassAlpha(p: Float): Float = (p / GLASS_FADE_SPAN).coerceIn(0f, 1f)

    fun glassBlurDp(
        p: Float,
        fullDp: Float,
    ): Float = GLASS_BLUR_START_DP + (fullDp - GLASS_BLUR_START_DP) * p.coerceIn(0f, 1f)
}
