package com.carlink.navigation

import android.content.Context
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import com.carlink.logging.logNavi
import java.time.Duration
import java.time.ZonedDateTime

/**
 * Shared Trip builder for cluster navigation display.
 *
 * Builds a [Trip] with the current maneuver step and, when the adapter firmware sends
 * a double-maneuver burst (see NavigationStateManager burst-detection window,
 * BURST_THRESHOLD_MS = 50), an additional next step. Trip steps are ordered — the first
 * is what the driver needs to do now, the second is what comes after.
 *
 * Used by both cluster session implementations (ClusterMainSession for GM AAOS,
 * CarlinkClusterSession for non-GM AAOS) so the Trip payload handed to
 * NavigationManager.updateTrip is identical across platforms.
 *
 * ETA caveat: the adapter exposes only a single timeToDestination (seconds to final
 * destination). The same computed `eta` is reused for per-step TravelEstimates, which
 * is semantically incorrect (step ETAs ≠ destination ETA) but accepted because the
 * cluster UIs surface step DISTANCE, not step ETA. See the inline note at the first
 * TravelEstimate construction.
 *
 * No tests. Add coverage if step/ETA semantics change.
 */
object TripBuilder {
    fun buildTrip(
        state: NavigationState,
        context: Context,
    ): Trip {
        val tripBuilder = Trip.Builder()
        // Single ETA reused across the current step, the next step, and the destination.
        // Only the destination estimate is actually "arrival time" — step ETAs are the
        // same value because the adapter doesn't expose per-step timing. See class KDoc.
        val eta = ZonedDateTime.now().plus(Duration.ofSeconds(state.timeToDestination.toLong()))

        // Primary step = the maneuver the driver is APPROACHING = state.maneuverType/roadName.
        // Per Apple's iAP2 route-guidance list (Iap2StateParser 0x000d), currentManeuverIdx element 0
        // is "the maneuver being approached now", and the live NaviRemainDistance counts down to that
        // same maneuver — confirmed here by distanceMeters: at each fresh segment liveRemain tracks
        // maneuvers[cur].distanceMeters, and maneuvers[cur].postManeuverRoadName is the road you turn
        // ONTO at that approaching maneuver (not the road you're currently on). [194] wrongly promoted
        // state.next* (the maneuver AFTER the approaching one) to the primary step, which put the HUD
        // text one turn AHEAD; reverted here. state.next* stays a look-ahead preview only.
        val maneuver = ManeuverMapper.buildManeuver(state, context)
        val stepBuilder = Step.Builder()
        stepBuilder.setManeuver(maneuver)
        state.roadName?.let { stepBuilder.setCue(it) }

        val stepEstimate =
            TravelEstimate
                .Builder(
                    DistanceFormatter.toDistance(state.remainDistance),
                    eta,
                ).build()

        tripBuilder.addStep(stepBuilder.build(), stepEstimate)

        // Next step — look-ahead preview (the maneuver AFTER the one being approached).
        if (state.hasNextStep) {
            val nextManeuver =
                ManeuverMapper.buildManeuverForType(
                    state.nextManeuverType!!,
                    state.turnSide,
                    context,
                    exitAngle = state.nextExitAngle,
                )
            val nextStepBuilder = Step.Builder()
            nextStepBuilder.setManeuver(nextManeuver)
            state.nextRoadName?.let { nextStepBuilder.setCue(it) }

            val nextStepEstimate =
                TravelEstimate
                    .Builder(
                        DistanceFormatter.toDistance(state.distanceToDestination),
                        eta,
                    ).build()

            tripBuilder.addStep(nextStepBuilder.build(), nextStepEstimate)

            logNavi {
                "[TRIP] Next step (preview): maneuver=${state.nextManeuverType}, road=${state.nextRoadName}"
            }
        }

        if (state.destinationName != null || state.distanceToDestination > 0) {
            // Destination.Builder().setName is optional — if destinationName is null
            // we still add a nameless Destination when distanceToDestination > 0 so
            // the cluster has something to render (typical display: "— 0.3 mi").
            val destBuilder = Destination.Builder()
            state.destinationName?.let { destBuilder.setName(it) }

            val destEstimate =
                TravelEstimate
                    .Builder(
                        DistanceFormatter.toDistance(state.distanceToDestination),
                        eta,
                    ).build()

            tripBuilder.addDestination(destBuilder.build(), destEstimate)
        }

        // Not ceremonial: GM's OnStarTurnByTurnManager renders a spinner instead of
        // the turn-card while a Trip is in the loading state. Always set false here.
        tripBuilder.setLoading(false)

        return tripBuilder.build()
    }
}
