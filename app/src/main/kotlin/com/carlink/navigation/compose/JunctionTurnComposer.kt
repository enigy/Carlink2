package com.carlink.navigation.compose

import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Turn arrows built from the junction geometry Apple sends with each maneuver ([203]).
 *
 * The route comes up from the bottom to the junction point J, turns through the REAL exit
 * angle (iAP2 0x000b) around a rounded corner, and leaves along the exit arm to Apple's
 * arrowhead. The junction's other arms (0x000a) are drawn as grey road stubs from J, the way
 * [ManeuverComposer] draws roundabout spokes, so the icon shows which road you take.
 *
 * At 90° with no side roads this reproduces Apple's 90° turn exactly
 * ([AppleManeuverPaths.RIGHT_TURN]): corner centre (49, 38) in Apple's frame, road edges at
 * radii 11 and 18, entry leg 32.5 from J to the bottom of the stem's rounded cap, exit leg
 * 20.86 from J to the arrowhead base, Apple's arrowhead template. Other angles use the same
 * pieces; sharper turns tighten the corner so it fits on the legs, and very sharp turns
 * lengthen the exit so the arrowhead clears the entry stem.
 *
 * Frame: J at the origin, x right, y DOWN. Angles are iAP2 driver-relative degrees:
 * 0 = straight ahead, +90 = right, -90 = left, ±180 = back the way you came — the same
 * convention [ManeuverComposer] feeds the roundabout builder.
 *
 * [IconBitmapRenderer] centres and scales the result, so only relative geometry matters.
 */
internal object JunctionTurnComposer {
    private const val W = GeometryPrimitives.ROAD_WIDTH // 7.0
    private const val HALF_W = W / 2.0

    /** Centerline radius of Apple's 90° corner (midway between its 11 and 18 road edges). */
    private const val CORNER_R = 14.5

    /** J → bottom of the entry stem's rounded cap (Apple: 56 − 23.5). */
    private const val ENTRY_LEG = 32.5

    /** J → arrowhead base (Apple: 55.36 − 34.5). */
    private const val EXIT_LEG = 20.86

    /** Grey side-road stub length from J. */
    private const val ARM_LEN = 16.0

    /** Widest |v| of [GeometryPrimitives.ARROWHEAD_TEMPLATE] — the barb tips. */
    private const val ARROW_HALF_SPAN = 15.1

    /** Gap kept between a sharp turn's arrowhead and the entry stem. */
    private const val ARROW_CLEARANCE = 1.0

    /** Below this the route is drawn straight through J (no corner). */
    private const val MIN_TURN_DEG = 4.0

    /** Beyond this callers should draw a U-turn instead; the arrowhead can't clear the stem. */
    const val MAX_TURN_DEG = 160.0

    /** Side arms this close to the entry or exit are the route itself, not another road. */
    private const val SAME_ARM_DEG = 12.0

    /** Overlap between adjacent pieces so the union has no hairline seams. */
    private const val EPS = 0.05

    /**
     * @param exitDeg driver-relative exit angle, |exitDeg| ≤ [MAX_TURN_DEG]
     * @param otherArmsDeg the junction's other arms (0x000a), same convention; may be empty
     */
    fun compose(
        exitDeg: Double,
        otherArmsDeg: List<Int>,
    ): ComposedIcon {
        val clampedExit = exitDeg.coerceIn(-MAX_TURN_DEG, MAX_TURN_DEG)
        val turnDeg = abs(clampedExit)
        val side = if (clampedExit >= 0) 1.0 else -1.0
        val exitRad = Math.toRadians(clampedExit)
        val ux = sin(exitRad)
        val uy = -cos(exitRad)
        val exitLeg = exitLegFor(turnDeg)

        val foreground = Path().apply { fillType = Path.FillType.WINDING }

        // Corner: centerline radius r, tangent points t from J along the entry and exit legs.
        val hasCorner = turnDeg >= MIN_TURN_DEG
        val halfTurn = Math.toRadians(turnDeg) / 2.0
        val r =
            if (hasCorner) {
                min(CORNER_R, 0.85 * min(ENTRY_LEG - W, exitLeg) / tan(halfTurn)).coerceAtLeast(HALF_W + 0.75)
            } else {
                0.0
            }
        val t = if (hasCorner) r * tan(halfTurn) else 0.0

        // 1. Entry stem from the corner down to Apple's rounded bottom cap.
        val capCenterY = ENTRY_LEG - HALF_W
        unionInto(foreground, rect(-HALF_W, t - EPS, HALF_W, capCenterY))
        unionInto(foreground, circle(0.0, capCenterY, HALF_W))

        // 2. Corner: the road band between radii r ± HALF_W, swept through the turn angle.
        if (hasCorner) {
            val cx = side * r
            val cy = t
            val startDeg = if (side > 0) 180f else 0f
            val sweepDeg = (side * turnDeg).toFloat()
            val outer = (r + HALF_W).toFloat()
            val inner = (r - HALF_W).toFloat()
            val band =
                Path().apply {
                    arcTo(oval(cx, cy, outer), startDeg, sweepDeg, true)
                    arcTo(oval(cx, cy, inner), startDeg + sweepDeg, -sweepDeg, false)
                    close()
                }
            unionInto(foreground, band)
        } else {
            unionInto(foreground, circle(0.0, 0.0, HALF_W)) // round the joint of a near-straight route
        }

        // 3. Exit leg from the corner's exit tangent point to the arrowhead base.
        val ex = ux * exitLeg
        val ey = uy * exitLeg
        unionInto(foreground, strip(ux * (t - EPS), uy * (t - EPS), ex, ey))

        // 4. Apple's arrowhead, base at the end of the exit leg.
        val arrow = Path().apply { fillType = Path.FillType.WINDING }
        GeometryPrimitives.appendArrowhead(arrow, ex, ey, ux, uy)
        unionInto(foreground, arrow)

        // Grey roads: only when the junction has other arms. The entry and exit pads join the
        // stubs to the route; white covers them except at the corner, which reads as the
        // intersection the route rounds.
        val sideArms = otherArmsDeg.map { it.toDouble() }.filter { arm -> isOtherArm(arm, clampedExit) }
        val background =
            if (sideArms.isEmpty()) {
                null
            } else {
                val padLen = t + HALF_W
                Path().apply {
                    fillType = Path.FillType.WINDING
                    unionInto(this, circle(0.0, 0.0, HALF_W))
                    unionInto(this, arm(180.0, padLen))
                    unionInto(this, arm(clampedExit, padLen))
                    for (deg in sideArms) unionInto(this, arm(deg, ARM_LEN))
                }
            }

        return ComposedIcon(background = background, foreground = foreground)
    }

    /**
     * Exit leg long enough that a sharp turn's arrowhead clears the entry stem: the nearer barb
     * tip sits at lateral distance d·sinφ − SPAN·cosφ from the stem's centerline, where φ is
     * the angle between the exit and the stem (180° − turn).
     */
    private fun exitLegFor(turnDeg: Double): Double {
        if (turnDeg <= 90.0) return EXIT_LEG
        val phi = Math.toRadians(180.0 - turnDeg)
        val needed = (HALF_W + ARROW_CLEARANCE + ARROW_HALF_SPAN * cos(phi)) / sin(phi)
        return max(EXIT_LEG, needed)
    }

    private fun isOtherArm(
        armDeg: Double,
        exitDeg: Double,
    ): Boolean = angleBetween(armDeg, exitDeg) > SAME_ARM_DEG && angleBetween(armDeg, 180.0) > SAME_ARM_DEG

    /** Unsigned difference between two angles, 0..180. */
    private fun angleBetween(
        a: Double,
        b: Double,
    ): Double {
        val d = abs(((a - b) % 360.0 + 360.0) % 360.0)
        return if (d > 180.0) 360.0 - d else d
    }

    /** A road-width strip from J outward along [deg] for [length]. */
    private fun arm(
        deg: Double,
        length: Double,
    ): Path {
        val rad = Math.toRadians(deg)
        val dx = sin(rad)
        val dy = -cos(rad)
        return strip(-dx * EPS, -dy * EPS, dx * length, dy * length)
    }

    /** Road-width quad from (x0, y0) to (x1, y1). */
    private fun strip(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
    ): Path {
        val len = kotlin.math.hypot(x1 - x0, y1 - y0).coerceAtLeast(1e-6)
        val px = -(y1 - y0) / len * HALF_W
        val py = (x1 - x0) / len * HALF_W
        return Path().apply {
            moveTo((x0 + px).toFloat(), (y0 + py).toFloat())
            lineTo((x1 + px).toFloat(), (y1 + py).toFloat())
            lineTo((x1 - px).toFloat(), (y1 - py).toFloat())
            lineTo((x0 - px).toFloat(), (y0 - py).toFloat())
            close()
        }
    }

    private fun rect(
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
    ): Path =
        Path().apply {
            addRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), Path.Direction.CW)
        }

    private fun circle(
        cx: Double,
        cy: Double,
        radius: Double,
    ): Path = Path().apply { addCircle(cx.toFloat(), cy.toFloat(), radius.toFloat(), Path.Direction.CW) }

    private fun oval(
        cx: Double,
        cy: Double,
        radius: Float,
    ): RectF = RectF(cx.toFloat() - radius, cy.toFloat() - radius, cx.toFloat() + radius, cy.toFloat() + radius)

    /**
     * Merge [part] into [dst] as one outline, so anti-aliasing runs only on the true boundary
     * (see [ManeuverComposer]'s ring/spoke note). If Skia's path op fails, fall back to
     * overlapping contours under WINDING fill — visually the same, minus the seam guarantee.
     */
    private fun unionInto(
        dst: Path,
        part: Path,
    ) {
        if (!dst.op(part, Path.Op.UNION)) {
            // Op results can come back EVEN_ODD, under which overlapping contours would cancel.
            dst.fillType = Path.FillType.WINDING
            dst.addPath(part)
        }
    }
}
