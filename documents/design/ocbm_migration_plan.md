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
- **Cluster turn-by-turn and the HUD maneuver icons.** This is the big one, and it's exactly the
  work we've been investing in (the `[203]` junction icons, `[207]`/`[213]` HUD fixes, and the
  `[236]` exit-number reader). Carlink2 drives those through AAOS **Templates Host /
  NavigationManager** and the cluster display. OCBM's own notes
  (`docs/ops/04_OPEN_ITEMS.md`, `01_ANDROID_AND_AAOS.md`) record that a third-party app **cannot**
  use `CarProjectionManager`, `CAR_NAVIGATION_MANAGER` cluster TBT, or `ClusterHomeManager` — they're
  `signature|privileged` on a locked GM unit. `CarlinkAndroid` therefore has **no cluster/HUD nav
  path** and isn't planning one. Net: you'd likely trade richer CarPlay video for losing nav on the
  gauge cluster. If the cluster HUD is a must-keep, that alone may decide against the switch.
- **The Adapter Configuration dialog.** Audio source, mic source, media/echo delay, resolution, FPS,
  drive side, GPS forwarding, Wi-Fi band — all stock `BoxSettings` knobs with no OCBM equivalent;
  OCBM pushes a `VehicleConfig` YAML and hardcodes much of the rest.
- **ExitInfo / call-audio work in flight.** The `[236]` ExitInfo reader and the `[235]`/PR #18 call
  path are stock-protocol changes; they don't carry over. ExitInfo would instead be a one-line
  selector in OCBM's Rust subscribe (the clean version of what we asked lvalen91 for) — but only if
  OCBM surfaces nav to the app at all, which per the cluster limitation above it largely doesn't.
- **The Logs tab** (file sink + level switch). CarlinkAndroid is `adb logcat` only today.
- **Reset Cluster** — no cluster in that build.

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

- **Cluster/HUD nav**: is losing it acceptable? (Likely the make-or-break question.)
- **One adapter or two?** Flashing your only adapter means your daily CarPlay rides on the trial
  until it's stable. A second CPC200-CCPA to experiment on removes all schedule pressure.
- **Who owns the app?** Run lvalen91's CarlinkAndroid as-is, or fork it the way Carlink2 forked
  carlink_native? Affects how your UI/settings preferences carry over.
- **Signing & install**: CarlinkAndroid is a third-party app (`zeno.carlink.ocbm`); same
  uninstall-to-switch-keys friction you already hit with sideloads.

## Recommendation for the next step

Before any flashing: get a **second adapter** to use as the OCBM bench unit, and settle the
architecture question. Then do phases 1–2 only (read + full backup + prove recovery). That's
all-upside and commits nothing. Hold the cluster-nav trade-off up against how much you rely on the
HUD today — that's the decision most likely to stop this, and it's better faced first than at
phase 6.
