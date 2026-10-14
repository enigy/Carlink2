# CarPlay split view (map + music pane) — firmware-patch scoping

Status: **scoping only, nothing built.** Authored 2026-10-14.

Goal the user asked for: a 65% Maps / 35% music-pane split on CarPlay (like Android Auto's
map+media split), **and** a fluid toggle between full-screen Maps and the split — ideally by
asking the CarPlay SDK to do the layout rather than Carlink2 faking it.

## Short answer

- CarPlay **does** support exactly this layout primitive: **view areas** (rectangles the iPhone
  lays its UI out inside), and a runtime command — `updateViewArea` — that switches between
  declared areas live, with no reconnect. This is how OEMs and the open-source DiPlay receiver do
  a fluid full ⇄ split toggle.
- **But the CPC200-CCPA firmware does not implement the runtime switch.** Its CarPlay receiver
  declares view/safe areas **once, at session setup**, and has **no `updateViewArea` sender**.
  So the SDK cannot be asked to switch live on this adapter without adding that sender to the
  firmware binary from scratch — far beyond the data-append code-cave patches done so far
  (`[133]`). **A fluid, no-reconnect toggle via the adapter is not feasible at reasonable effort.**
- Everything that changes display geometry on this adapter (Open width/height, `HU_VIEWAREA_INFO`,
  `HU_SAFEAREA_INFO`) is read **only at AirPlay SETUP**, so every layout change = a CarPlay
  reconnect. The realistic builds are therefore either an **instant app overlay** (no geometry
  change, imperfect CarPlay layout) or a **clean split that costs a reconnect per toggle**.

## Evidence (what was checked)

Firmware on the truck is `2025.10.15.1127`. Using the key in
`lvalen91/CPC200-CCPA_resources` (`AutoPlay9uPT4n17`), the stock update image decrypts and the
three relevant programs extract cleanly:

- `AppleCarPlay` — 325,104 B packed, md5 `7b2aeeb111d8924c387ab751ecc95469`
- `ARMiPhoneIAP2` — 181,648 B packed, md5 `d933c9d5…` (matches the repo's recorded fingerprint,
  so the documented patch toolchain is for this exact binary)

What the reverse-engineering docs already establish about `AppleCarPlay`'s view-area code
(r2 + live-verified Feb–May 2026, in both the Carlink2 RE docs and the resources repo):

- `_CopyDisplayDescriptions` (fcn.0001c0b4, ~2112 B) builds the SETUP `displayInfo`/`viewAreas`
  plist from `HU_VIEWAREA_INFO` + `HU_SAFEAREA_INFO`. It appends **one** view area to the array.
- `viewAreaTransitionControl` is **hardcoded false**; `adjacentViewAreas` and `initialViewArea`
  strings exist but are **never populated / not wired**. Live capture confirms the adapter sends
  `adjacentViewAreas: []`, `viewAreaTransitionControl: false`, a single full-screen area.
- The receiver's runtime "mode" machine (`AirPlayReceiverSessionMakeModeStateFromDictionary`,
  `changeModes`/`modesChanged`, `requestUI`, `setLimitedUI`, `setNightMode`, `duckAudio`, …) has
  channels only for **screen, mainAudio, speech, phoneCall, turnByTurn** — **no view-area
  channel**, and no `updateViewArea` / `AirPlayReceiverSessionViewAreaUpdate` in the command set.

Taken together: the adapter can **declare** areas statically but has **no runtime path to change
which area is active** mid-session. The multi-area SDK code is compiled-but-disabled for
declaration; the *sender* side simply isn't there.

Note on confirming by unpack: stock UPX (3.94, x86) recognises the binary once its magic is
restored (ludwig-v changed `UPX!` → `U"U"` / `0x55225522`) but then fails with "compressed data
violation" — this variant needs the adapter's **modified UPX**, which is a 32-bit ARM ELF meant to
run on the adapter over SSH. So a definitive string grep for a hidden `updateViewArea` sender must
be done **on the adapter**, not here (command in the last section). The weight of existing r2
evidence already says it is absent.

## The SafeArea angle (the useful finding)

Carlink2 **already** sends `HU_VIEWAREA_INFO` and `HU_SAFEAREA_INFO` every session
(`MessageSerializer` + `MainActivity.buildViewAreaData` / `buildSafeAreaData`), with the invariant
safeArea ⊆ viewArea ⊆ display. Today the safe area equals the full screen on gminfo37 (no cutouts).

If we instead set **viewArea = full 2400×960** but **safeArea = left 1560×960** with
`drawUIOutsideSafeArea = 1`, CarPlay keeps the video stream full-size and (per the RE note,
live-verified iOS 18) keeps Maps/app content **inside the safe rect** — i.e. Maps renders in the
left ~65% and the right ~35% is left for Carlink2 to draw its music pane over. This gives the clean
map-left / pane-right split **without** shrinking the H.264 stream and **without** a firmware patch.

Caveat: the safe area is still only applied at SETUP, so toggling full ⇄ split by rewriting
`HU_SAFEAREA_INFO` still forces a CarPlay reconnect. It may be a *faster* reconnect than changing
the Open width (the decoder/surface stay at 2400×960), but it is not instant. This is worth a
**code-free truck experiment** before building anything (see below).

## Options

| # | Approach | Toggle | Map layout | Firmware patch | Effort / risk |
|---|---|---|---|---|---|
| A | App overlay only — draw the pane over the right 35%, change no geometry | **Instant** (show/hide) | CarPlay still thinks it's 2400 wide: map's right third is hidden behind the pane and the dock/buttons may land under it | None | Low; layout imperfect, touch dead-zones possible |
| B | **SafeArea split** — viewArea full, safeArea = left 1560, pane over the right | Reconnect (likely a few s; stream unchanged) | Clean: Maps in left 65%, pane right | None (reuses existing SendFile path) | Medium; **best result without a patch** |
| C | Open-width 1560 + pane | Reconnect ~11 s (measured 17:20 log) | Clean; CarPlay is genuinely 1560 wide | None | Medium; proven cost, decoder reconfigures |
| D | Patch `AppleCarPlay` to declare two areas **and send `updateViewArea`** | Instant, animated | Clean | **Yes — implement an AirPlay event-channel command sender in the binary** | Very high; the sender does not exist in stock fw → not a code-cave append but new protocol code; **not recommended** |

## Recommendation

1. The fluid SDK-driven toggle the user pictured (option D) is **blocked by the firmware** — not
   worth pursuing as a binary patch.
2. **Run the SafeArea experiment first (no code):** on the truck, push an `HU_SAFEAREA_INFO` of
   left 1560×960, `drawUIOutsideSafeArea = 1`, with viewArea left full, and watch what CarPlay does
   with the right 840 px while Maps is active and navigating. If Maps stays in the left rect and the
   right is blank/wallpaper (coverable), **option B is the build** and the only open question is
   reconnect latency on toggle.
   - Carlink2 already has the builders; this needs a throwaway one-off, not a feature.
3. If B's split is clean but the per-toggle reconnect is too slow, fall back to **option A** for the
   toggle (instant overlay) and accept the rougher CarPlay layout, or keep B and treat "full vs
   split" like the existing display-mode change (a brief reconnect).

Whichever wins, the Carlink2-side work is the same shape as option 2 from the earlier discussion:
the music pane (it already receives title/artist/art/progress/play-state and can send
play-pause/next/prev), the 65/35 layout, touch routing, and the toggle — about one revision once
the geometry question above is settled.

## If the firmware route is ever revisited

To settle the one unproven point (whether a hidden `updateViewArea` sender exists), unpack on the
adapter and grep — do **not** trust stock x86 UPX for this variant:

```sh
ssh root@192.168.43.1                      # dropbear starts before CarPlay; recovery-safe
/path/to/modified/upx -d /usr/sbin/AppleCarPlay -o /tmp/acp.un
strings /tmp/acp.un | grep -iE 'updateViewArea|ViewAreaUpdate|adjacentViewArea|viewAreaIndex'
```

If those return only the SETUP-plist keys (no `…Update`/`…Index` sender symbols), option D is
confirmed dead and B/C/A stand as the only paths.
