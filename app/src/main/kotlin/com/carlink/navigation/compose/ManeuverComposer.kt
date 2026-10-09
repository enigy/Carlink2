package com.carlink.navigation.compose

import android.graphics.Matrix
import android.graphics.Path
import com.carlink.navigation.Iap2ManeuverData
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Maneuver-icon dispatcher backed by [AppleManeuverPaths] (paths extracted directly from
 * Apple's MapKit private `MKArrowAppend*ToPathInRect` + `MKJunction getRoundaboutArrowPath:`
 * on macOS arm64e — see `~/Documents/Apple_Maps_SVG/MAPKIT_DISPATCH_OBSERVED.md`).
 *
 * Bundled bezier data is the deterministic output of Apple's own bezier-builder code; we
 * don't re-implement Apple's geometry, we look it up. This replaces the prior synthesis
 * approach in [RoundaboutComposer] which produced fragmented icons that didn't match
 * Apple's quality.
 *
 * Inputs come from the iAP2 protocol (carlink_native namespace, `cpManeuverType` 0–53);
 * see `Apple_Maps_Maneuver_Icons_RE.md` §9 for the cpType→shape mapping.
 *
 * [203]: turns and straight-through maneuvers are drawn from the maneuver's own junction
 * geometry by [JunctionTurnComposer] — real exit angle (0x000b) plus grey stubs for the other
 * arms (0x000a) — instead of one fixed 90° arrow for every left or right. Without geometry,
 * sharp/slight/end-of-road turns still get their own angle rather than the 90° arrow.
 */
internal object ManeuverComposer {
    const val VIEWPORT = 56.0f

    /** Exit angle drawn for a turn type when the maneuver carries no junction geometry. */
    private val NOMINAL_EXIT_DEG =
        mapOf(
            1 to -90.0, 2 to 90.0, // left / right
            20 to -90.0, 21 to 90.0, // end of road left / right (T-junction)
            47 to -135.0, 48 to 135.0, // sharp left / right
            49 to -45.0, 50 to 45.0, // slight left / right
        )

    /** noTurn, straight, followRoad — drawn through the junction only for a gentle bend. */
    private val STRAIGHT_TYPES = setOf(0, 3, 5)
    private const val MAX_STRAIGHT_BEND_DEG = 30.0

    /** A left/right turn whose wire angle is under this (or on the wrong side) is suspect. */
    private const val MIN_TURN_DEG = 4.0

    /**
     * @return null when the static drawable for this type (ManeuverIconRenderer) is more
     *   specific than anything composed here. ComposedIconStore then stores no bitmap and
     *   ManeuverMapper shows that drawable.
     */
    fun compose(data: Iap2ManeuverData): ComposedIcon? {
        val cp = data.cpManeuverType
        return when (cp) {
            // ── Roundabouts (cpType 6, 7, 28..46) — use Apple's MKJunction-extracted paths ──
            6, 7, in 28..46 -> roundabout(data)

            // ── Turns and straight-through — from the real junction geometry ([203]) ──
            in NOMINAL_EXIT_DEG.keys, in STRAIGHT_TYPES -> junctionTurn(data)

            // ── U-turns ──
            4, 18, 19, 26 -> singleSvg(AppleManeuverPaths.U_TURN)

            // ── Merges ──
            9, 14, 53 -> singleSvg(AppleManeuverPaths.MERGE_RIGHT)
            13, 52 -> singleSvg(AppleManeuverPaths.MERGE_LEFT)

            // ── Exits / ramps ──
            8, 23 -> singleSvg(AppleManeuverPaths.EXIT_RIGHT)
            22 -> singleSvg(AppleManeuverPaths.EXIT_LEFT)

            // ── Ferries / change highway — straight, as the static set draws them too ──
            15, 16, 17, 51 -> singleSvg(AppleManeuverPaths.STRAIGHT)

            // ── Destinations — Apple's Arrival composed paths (NSArray<NSBezierPath*>) ──
            10, 12, 27 -> singleSvgEvenOdd(AppleManeuverPaths.ARRIVAL)

            // ── Static drawable is more specific: start of route (PROCEED_TO_ROUTE is
            // byte-identical to ARRIVAL, a destination pin) and arrive on the left / right. ──
            11, 24, 25 -> null

            else -> singleSvg(AppleManeuverPaths.STRAIGHT)
        }
    }

    /**
     * A turn or straight-through maneuver. Uses the wire exit angle when it agrees with the
     * maneuver type (right types need a right-hand angle, etc.); otherwise the type's nominal
     * angle. Plain 90° turns without geometry keep Apple's exact extracted paths.
     */
    private fun junctionTurn(data: Iap2ManeuverData): ComposedIcon {
        val cp = data.cpManeuverType
        val nominal = NOMINAL_EXIT_DEG[cp]
        val wireExit =
            data.exitAngle?.let { normalizeDeg(it.toDouble()) }?.takeIf { exit ->
                if (cp in STRAIGHT_TYPES) {
                    abs(exit) <= MAX_STRAIGHT_BEND_DEG
                } else {
                    nominal != null && exit * nominal > 0 && abs(exit) >= MIN_TURN_DEG
                }
            }

        if (wireExit != null) {
            if (abs(wireExit) > JunctionTurnComposer.MAX_TURN_DEG) return uTurn(wireExit)
            return JunctionTurnComposer.compose(wireExit, data.entryAngles)
        }
        return when {
            nominal == null -> singleSvg(AppleManeuverPaths.STRAIGHT)
            cp == 1 -> singleSvg(AppleManeuverPaths.LEFT_TURN)
            cp == 2 -> singleSvg(AppleManeuverPaths.RIGHT_TURN)
            // End of road: a T — the road also continues the other way.
            cp == 20 || cp == 21 -> JunctionTurnComposer.compose(nominal, listOf((-nominal).toInt()))
            else -> JunctionTurnComposer.compose(nominal, emptyList())
        }
    }

    /** Apple's U-turn bends left (right-hand traffic); mirror it for a right-hand exit. */
    private fun uTurn(exitDeg: Double): ComposedIcon {
        val path = SvgPathParser.parse(AppleManeuverPaths.U_TURN).apply { fillType = Path.FillType.WINDING }
        if (exitDeg > 0) path.transform(Matrix().apply { setScale(-1f, 1f) })
        return ComposedIcon(background = null, foreground = path)
    }

    /** Degrees into [-180, 180). */
    private fun normalizeDeg(deg: Double): Double = ((deg % 360.0) + 540.0) % 360.0 - 180.0

    // ─── Roundabout ring geometry (56pt viewport, ring centered at (28,28)) ───
    // Matches MapKit's getRoundaboutArrowPath background donut: outer r=16.29, inner r=9.29.
    private const val RING_CX = 28.0f
    private const val RING_OUTER = 16.290909f
    private const val RING_INNER = 9.290909f
    // Spoke stub: a grey radial bar for each real junction arm.
    private const val SPOKE_INNER = 13.0f
    private const val SPOKE_OUTER = 24.5f
    private const val SPOKE_HALFWIDTH = 3.3f

    /**
     * Compose a roundabout icon. The white exit **arrow** is a pre-baked MapKit core-primitive
     * render at the nearest 10° (36-way) — see [AppleManeuverPaths.roundaboutPaths]; finer than
     * Apple's own 8-way CarPlay-card snap. The grey **ring + spokes** is built here at runtime
     * from the REAL junction arms in `entryAngles`, so it reflects this specific roundabout
     * instead of a generic schematic.
     *
     * Wire conventions (live-verified 2026-05-29, 3-agent):
     * - `exitAngle` (0x000b) = driver-relative degrees: 0°=straight, +90°=right, −90°=left,
     *   ±180°=back. Fed straight to [AppleManeuverPaths.roundaboutPaths] (no rotation).
     * - `entryAngles` (0x000a, multi) = the OTHER arms in the same driver-relative frame. We
     *   draw a grey spoke at each. We do NOT draw a spoke at the entry (180°, shown by the
     *   arrow's stem) or the exit (shown by the arrow head) — so there is no grey stub to
     *   diverge from the fine arrow. Note iAP2 often omits arms (incomplete list), so spokes
     *   show only the arms actually reported.
     * - `exitOrdinal` = cpType−27 (28→1st, 29→2nd, …); 6/7 default to 2.
     *
     * Lookup miss for the arrow → [NoSuchElementException]; ComposedIconStore catches it and
     * ManeuverMapper falls through to the static AVD safety net.
     */
    private fun roundabout(data: Iap2ManeuverData): ComposedIcon {
        val exitOrdinal = when (data.cpManeuverType) {
            in 28..46 -> data.cpManeuverType - 27
            else -> 2
        }
        val nArms = max(data.entryAngles.size + 1, exitOrdinal + 1)
        val clampedExitOrdinal = exitOrdinal.coerceIn(1, (nArms - 1).coerceAtLeast(1))

        val relExitDeg: Double = data.exitAngle?.let { exit ->
            // Apple's exitAngle is already driver-relative. Normalize to (-180, +180].
            ((exit.toDouble() % 360.0) + 540.0) % 360.0 - 180.0
        } ?: 0.0  // straight-through default if no exit angle

        val paths = AppleManeuverPaths.roundaboutPaths(nArms, clampedExitOrdinal, relExitDeg)
            ?: throw NoSuchElementException(
                "no roundaboutPaths for nArms=$nArms ord=$clampedExitOrdinal exitDeg=$relExitDeg cp=${data.cpManeuverType}"
            )

        val foreground = SvgPathParser.parse(paths.arrow).apply {
            fillType = Path.FillType.WINDING
        }
        val background = buildRingAndSpokes(data.entryAngles)
        return ComposedIcon(background = background, foreground = foreground)
    }

    /**
     * Build the grey ring + per-arm spokes from the real junction arm angles (driver-relative
     * degrees). App icon frame: center (28,28), y-DOWN so 0°=up, +90°=right (+x), −90°=left.
     *
     * The ring donut and the spoke quads overlap (spokes start inside the ring band so they
     * connect cleanly). They MUST be merged into a single outline via [Path.op] UNION before
     * returning: [IconBitmapRenderer] fills the background as one anti-aliased path, and
     * overlapping sub-contours would otherwise leave an AA seam where the ring's outer edge
     * crosses each spoke (visible as a notch/clip at the ring radius). Unioning removes all
     * internal edges so AA only runs on the true boundary.
     */
    private fun buildRingAndSpokes(entryAngles: List<Int>): Path {
        val result = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addCircle(RING_CX, RING_CX, RING_OUTER, Path.Direction.CW)
            addCircle(RING_CX, RING_CX, RING_INNER, Path.Direction.CW) // EVEN_ODD → donut hole
        }
        for (deg in entryAngles) {
            val a = Math.toRadians(deg.toDouble())
            val ux = sin(a).toFloat(); val uy = (-cos(a)).toFloat()   // radial (up = −y)
            val px = cos(a).toFloat(); val py = sin(a).toFloat()      // perpendicular
            val spoke = Path().apply {
                moveTo(RING_CX + SPOKE_INNER * ux + SPOKE_HALFWIDTH * px, RING_CX + SPOKE_INNER * uy + SPOKE_HALFWIDTH * py)
                lineTo(RING_CX + SPOKE_OUTER * ux + SPOKE_HALFWIDTH * px, RING_CX + SPOKE_OUTER * uy + SPOKE_HALFWIDTH * py)
                lineTo(RING_CX + SPOKE_OUTER * ux - SPOKE_HALFWIDTH * px, RING_CX + SPOKE_OUTER * uy - SPOKE_HALFWIDTH * py)
                lineTo(RING_CX + SPOKE_INNER * ux - SPOKE_HALFWIDTH * px, RING_CX + SPOKE_INNER * uy - SPOKE_HALFWIDTH * py)
                close()
            }
            result.op(spoke, Path.Op.UNION) // merge into one outline → no internal AA seam
        }
        return result
    }

    private fun singleSvg(svg: String): ComposedIcon {
        val p = SvgPathParser.parse(svg).apply { fillType = Path.FillType.WINDING }
        return ComposedIcon(background = null, foreground = p)
    }

    private fun singleSvgEvenOdd(svg: String): ComposedIcon {
        val p = SvgPathParser.parse(svg).apply { fillType = Path.FillType.EVEN_ODD }
        return ComposedIcon(background = null, foreground = p)
    }
}
