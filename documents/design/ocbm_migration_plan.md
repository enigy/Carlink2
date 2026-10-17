# Planning: moving Carlink2 onto lvalen91's OCBM stack

Status: **planning only.** Nothing here changes the adapter or the app. Hands-on box steps are
deferred to lvalen91's own OCBM docs, which are authoritative for his hardware
(`ocbm/docs/ops/00_BUILD_AND_DEPLOY.md`, `01_RECOVERY.md`, `ocbm/tools/ocbm_install.sh`).

## What "switch to OCBM" actually means

Carlink2 today is a head-unit app that speaks the **stock** Carlinkit USB protocol (`0x55AA55AA`
framing) to an adapter running Carlinkit's closed firmware (`ARMadb-driver` / `AppleCarPlay` /
`ARMiPhoneIAP2`). The box terminates CarPlay and hands us a stripped feed.

OCBM replaces the adapter's **entire userspace** with lvalen91's open Rust stack and a new USB
protocol (**OCBM**). The stock protocol is gone, so a stock-protocol app can't talk to an OCBM box.
Switching means two moves at once:

1. **The box** gets reflashed from Carlinkit firmware to the OCBM userspace.
2. **The app** changes from Carlink2 to an OCBM client. lvalen91 already has one:
   `ocbm/host/CarlinkAndroid` — the AAOS head-unit app that is the direct counterpart to Carlink2
   (claims the box over OCBM, full wireless CarPlay, its own decode/render/mic). We would adopt or
   fork that, not retrofit OCBM into Carlink2's transport.

So this is less a "port" and more a "change which project you run," with Carlink2's UI/UX ideas
carried over where they already have been (lvalen91 ported carlink_native's screens onto
CarlinkAndroid on 2026-09-25).

## Pick the architecture first (decision #1)

OCBM ships two different Android designs. They are not interchangeable and the plan branches here.

| | `host/CarlinkAndroid` (full OCBM) | `host/gm_ccpa` (vehicle-hotspot) |
|---|---|---|
| Media path | iPhone ⇄ adapter Wi-Fi ⇄ USB/OCBM ⇄ app | iPhone ⇄ **vehicle's own 5 GHz** ⇄ app; adapter is only BT radio + MFi |
| Closest to Carlink2 | Yes — same "adapter bridges everything" model | No — inverted; adapter carries no media |
| Device-proven on a 2024 Silverado | Partly (see below) | Yes, full sessions end-to-end |
| HEVC (vs stock H.264 only) | Yes | Yes (2400×960 HW decode) |

`gm_ccpa` is the more battle-tested of the two on real hardware, but it's a bigger conceptual
departure (the truck's hotspot carries CarPlay). `CarlinkAndroid` matches how you already think
about the setup. **This choice drives everything downstream, so it's the first thing to settle.**

## The honest trade: what you gain, what you lose

**Gain**
- HEVC CarPlay instead of the stock box's forced H.264 — the headline reason OCBM exists.
- New CarPlay features land app-side, so you stop waiting on Carlinkit firmware.
- Fully open box userspace: SSH, logs over `CH_LOG`, no closed `riddleBox` to reverse.

**Lose / regress (read this before committing)**
- ~~Cluster turn-by-turn and the HUD maneuver icons.~~ **Corrected 2026-10-17 — portable, see
  "Cluster nav on OCBM" below.** The first draft took OCBM's "cluster TBT is out of reach for a
  third-party app" at face value. That finding is about the system APIs (`CarProjectionManager`,
  `CAR_NAVIGATION_MANAGER`, `ClusterHomeManager`), which OCBM evaluated and rightly rejected.
  Carlink2 doesn't use any of them: it reaches the cluster through the public **androidx.car.app
  Templates Host** (a `CarAppService` in the NAVIGATION + FEATURE_CLUSTER categories, with the two
  *normal* permissions `NAVIGATION_TEMPLATES` and `ACCESS_SURFACE`), and it works today as an
  unprivileged sideload on this exact truck. OCBM never evaluated that route (no mention anywhere in
  its tree). So cluster nav is a **port**, not a loss.
- **The Adapter Configuration dialog.** Audio source, mic source, media/echo delay, resolution, FPS,
  drive side, GPS forwarding, Wi-Fi band — all stock `BoxSettings` knobs with no OCBM equivalent;
  OCBM pushes a `VehicleConfig` YAML and hardcodes much of the rest.
- **Call-audio work in flight.** The `[235]`/PR #18 call path is a stock-protocol change and
  doesn't carry over. (ExitInfo is the opposite — OCBM already decodes it; see below.)
- **The Logs tab** (file sink + level switch). CarlinkAndroid is `adb logcat` only today.
- **Reset Cluster** — no cluster in that build.

## Cluster nav on OCBM — how (added 2026-10-17)

**The data already reaches the OCBM app.** The box's iAP2 stack decodes route guidance to JSON and
forwards it on `CH_METADATA` as `META_JSON` records (`iap2-core/src/metadata.rs`):

| iAP2 msg | JSON `kind` | Fields emitted today |
|---|---|---|
| 0x5201 RouteGuidanceUpdate | `routeGuidance` | routeGuidanceState, maneuverState, currentRoadName, destinationName, etaEpoch, timeRemainingSec, distanceRemainingM, distanceToManeuverM (+text/units), **currentManeuverIndex**, maneuverTotalCount |
| 0x5202 ManeuverInformation | `maneuver` | index, description, **maneuverType**, afterManeuverRoadName, distanceM (+text/units), drivingSide, junctionType, **exitInfo** |
| 0x5204 LaneGuidance | (one record per lane) | lane status / angle |

`CarlinkAndroid`'s `CarlinkManager.onMetadata` consumes `routeGuidance` only to claim nav focus and
drops `maneuver` into `else -> Unit`. Everything a cluster trip needs is on the wire; nothing reads it.

**What ports from Carlink2 (~5,700 lines, mostly transport-agnostic):**
- Keep as-is: `cluster/` (`CarlinkClusterService`, `ClusterMainSession`, `CarlinkClusterSession`,
  `ClusterBindingState`), `navigation/TripBuilder`, `ManeuverMapper`, `DistanceFormatter`,
  `ManeuverIconRenderer`, and the whole `navigation/compose/` icon composer.
- Rewrite one seam: `NavigationStateManager.onNaviJson` reads stock NaviJSON keys. Replace with a
  mapper from OCBM's `routeGuidance`/`maneuver` JSON into `NavigationState`. Keep maneuvers in a
  map by `index`; `currentManeuverIndex` from 0x5201 selects the live one (OCBM's own note calls this
  out as the fix for the stale-first-maneuver bug).
- Drop: `Iap2RouteParser`/`Iap2StateParser` (raw TLV parsers) — OCBM already decodes the TLVs.
  Keep `Iap2ManeuverData` as the model.
- Relocate one piece: the cluster icon `ContentProvider` (authority
  `…templates.host.ClusterIconContentProvider`) lives in Carlink2's **bridge APK**
  (`bridge/…/ClusterIconProvider.kt`, one file). The OCBM app claims USB itself and has no bridge, so
  host that provider in the app. Carlink2's manifest notes the Templates Host resolves that authority
  by package; verify on the truck that an in-app provider is accepted the same way.

**Two gaps, both small, both on the Rust side (OCBM PRs to lvalen91):**
1. `maneuver()` does not emit params 10/11 (JunctionElementAngle, multi-occurrence; JunctionElementExitAngle).
   Carlink2's `[203]` real-geometry roundabout/junction icons need them. Without them,
   `ManeuverMapper` falls back to static glyphs by `maneuverType` — cluster nav still works, just the
   pre-`[203]` look. Adding the two ids is a few lines.
2. `start_route_guidance()` sends selectors 0/1/2 only; **ExitInfo needs selector 3** — one line.
   OCBM already decodes param 13 into `exitInfo`, so this is the clean version of the whole v5-binary
   question: no packed binary, no renumbering trick.

**Net:** on OCBM the cluster/HUD keeps everything it has today, and gains exit numbers and lane
guidance that the stock box never forwards. This removes the plan's make-or-break objection.

## Authorization note

OCBM is explicitly scoped by its author to a **licensed Apple/MFi developer doing ordinary accessory
development on genuine hardware** (`ocbm/docs/ops/07_AUTHORIZATION.md`). The CarPlay protocol facts
there are checked against Apple's CarPlay SDK under that membership. Plan on the same footing; this
is interoperability on your own hardware, not a workaround.

## Migration phases (once the architecture is chosen)

Deliberately reversible, bench before truck. Box specifics live in lvalen91's docs — this is only the
ordering.

1. **Decide & dry-read.** Settle CarlinkAndroid vs gm_ccpa. Read `ocbm/docs/README.md`,
   `00_ARCHITECTURE.md`, and the chosen host's README/`OCBMANDROID.md` end to end. No changes yet.
2. **Back up the box, fully.** Capture the stock firmware (the OCBM tree keeps a baseline SPI image +
   per-region backups and a programmer restore procedure). Confirm the recovery path works *before*
   touching anything — you've said you're comfortable here; this is where that matters.
3. **Build the OCBM box binaries and the host app** per `00_BUILD_AND_DEPLOY.md` (Rust +
   cargo-zigbuild for the box; Gradle for the Android app). Bench only.
4. **Reversible trial install** on the adapter using lvalen91's installer (it has a trial→finalize
   flow and a dead-man revert). Keep the stock image ready to reflash.
5. **Bench bring-up**: one wired, one wireless CarPlay session against your iPhone, HEVC confirmed.
6. **In-truck test**: USB-permission behavior on the GM unit (OCBM documents a per-connect permission
   prompt issue and a handler "squat" workaround — understand it before expecting seamless connect),
   audio, touch, now-playing on the media card, drive-state/day-night.
7. **Decide to keep or roll back.** Living on it for a week before committing is cheap; reflashing
   back to stock + Carlink2 is the fallback the whole plan is built around.

## Open questions to resolve before phase 3

- **Cluster/HUD nav**: resolved — portable (see above). Remaining question is only sequencing: port
  it before the first in-truck test, or accept a short window without it.
- **One adapter or two?** Flashing your only adapter means your daily CarPlay rides on the trial
  until it's stable. A second CPC200-CCPA to experiment on removes all schedule pressure.
- **Who owns the app?** Run lvalen91's CarlinkAndroid as-is, or fork it the way Carlink2 forked
  carlink_native? Affects how your UI/settings preferences carry over.
- **Signing & install**: CarlinkAndroid is a third-party app (`zeno.carlink.ocbm`); same
  uninstall-to-switch-keys friction you already hit with sideloads.

## Recommendation for the next step

Before any flashing: get a **second adapter** to use as the OCBM bench unit, and settle the
architecture question. Then do phases 1–2 only (read + full backup + prove recovery). That's
all-upside and commits nothing. With cluster nav shown to be portable, the remaining real
risks are the USB-permission prompt behavior on the GM unit and the loss of the Adapter
Configuration knobs — both worth confirming on a bench adapter before the truck.
