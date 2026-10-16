package com.carlink.ui.components

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.carlink.ui.settings.BackgroundLogoConfig
import kotlin.random.Random

// ── Tunables ────────────────────────────────────────────────────────────────
// Blur over the background scene. Large enough that the mountains read as color and light
// only, small enough that the sunset and the logo still show through as shapes.
private val GLASS_BLUR_RADIUS = 28.dp

// Dark tint over the blurred scene so the settings text keeps its contrast. Below API 31
// Modifier.blur is a no-op (no RenderEffect), so the sharp scene gets a heavier tint instead.
// gminfo3.7 is AAOS 12 (API 31/32) and gets the blur.
private const val GLASS_TINT_ALPHA = 0.45f
private const val GLASS_TINT_ALPHA_NO_BLUR = 0.75f

// Grain: a tiled noise texture over the glass. It gives the dithered, frosted look and hides
// the banding a smooth blur shows on the radio's panel. Seeded, so the pattern never changes.
private const val GRAIN_TILE_PX = 128
private const val GRAIN_MAX_ALPHA = 22 // of 255, per pixel
private const val GRAIN_SEED = 220L
// ────────────────────────────────────────────────────────────────────────────

/**
 * Frosted-glass backdrop for the settings page: the connecting screen's background scene
 * ([BackgroundLogoConfig.drawableRes], so it follows the Background Logo setting), blurred,
 * tinted and grained.
 *
 * Opaque by construction once in place — the scene fills the bounds and the blur clamps at the
 * edges (as [BlurredEdgeTreatment.Rectangle] does) — so the projection's SurfaceView never shows
 * through it. The scene is a static image (no fog animation), so the blurred layer is drawn once
 * and reused.
 *
 * [229] [progress] is the settings page's arrival ([SettingsFlyIn]): the glass fades in over the
 * CarPlay screen while its frost thickens. Read in the layer phase, so the animation redraws the
 * layer without recomposing.
 */
@Composable
fun FrostedGlassBackground(
    backgroundLogo: BackgroundLogoConfig,
    modifier: Modifier = Modifier,
    progress: () -> Float = { 1f },
) {
    val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val tint = MaterialTheme.colorScheme.surface.copy(alpha = if (blurSupported) GLASS_TINT_ALPHA else GLASS_TINT_ALPHA_NO_BLUR)
    val grain = remember { ShaderBrush(ImageShader(grainTile(), TileMode.Repeated, TileMode.Repeated)) }

    Box(modifier = modifier.fillMaxSize().graphicsLayer { alpha = SettingsFlyIn.glassAlpha(progress()) }) {
        Image(
            painter = painterResource(id = backgroundLogo.drawableRes),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier.fillMaxSize().graphicsLayer {
                    val radius = SettingsFlyIn.glassBlurDp(progress(), GLASS_BLUR_RADIUS.value).dp.toPx()
                    renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                    clip = true
                },
        )
        Box(modifier = Modifier.fillMaxSize().background(tint))
        Box(modifier = Modifier.fillMaxSize().background(grain))
    }
}

/** One tile of grain: each pixel black or white at a random alpha up to [GRAIN_MAX_ALPHA]. */
private fun grainTile(): ImageBitmap {
    val random = Random(GRAIN_SEED)
    val pixels =
        IntArray(GRAIN_TILE_PX * GRAIN_TILE_PX) {
            val alpha = random.nextInt(GRAIN_MAX_ALPHA + 1)
            val gray = if (random.nextBoolean()) 0xFF else 0x00
            (alpha shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
    return Bitmap.createBitmap(pixels, GRAIN_TILE_PX, GRAIN_TILE_PX, Bitmap.Config.ARGB_8888).asImageBitmap()
}
