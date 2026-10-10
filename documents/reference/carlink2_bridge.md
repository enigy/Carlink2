# Carlink2: display app + USB bridge

Status as of 2026-10-09: written, **not yet built or run**. Nothing here has been verified
on the truck.

## Why two apps

Two head-unit rules pull in opposite directions:

1. **Driving overlay.** GM only lets a third-party app keep the screen while moving if it is
   distraction-optimized *and* installed by Play. A sideloaded copy is covered when you shift
   out of Park.
2. **USB permission.** The car product overlay sets
   `config_UsbDeviceConnectionHandling_component` to
   `android.car.usb.handler/android.car.usb.handler.UsbHostManagementActivity`
   (packages/services/Car `car_product/overlay`, android12L). When that is set,
   `UsbHostManager.usbDeviceAdded()` skips the normal attach flow — no
   `USB_DEVICE_ATTACHED` activity resolution, so no "always use" checkbox — and calls
   `UsbProfileGroupSettingsManager.deviceAttachedForFixedHandler()` instead. That method:
   - looks up the package `android.car.usb.handler` *for the parent of the current user*,
   - grants that UID permission for the device (`grantDevicePermission`),
   - then tries `startActivityAsUser()` on the component and catches `ActivityNotFoundException`.

   GM ships no app with that package name, so on stock firmware the grant goes nowhere and
   every app is left with the dialog. This is also why the old app never saw
   `USB_DEVICE_ATTACHED` ([171], [173]).

Play can't install an app named `android.car.usb.handler`, and a sideloaded app can't keep the
screen. So the work splits:

| | Display — `com.enigy.carlink2` | Bridge — `android.car.usb.handler` |
|---|---|---|
| Installed by | Play | USB drive (sideload) |
| UI | everything | none (no activity, no launcher icon) |
| USB | never opens it, never asks permission ([204]) | owns the adapter: open, claim, read, write |
| Cluster icons | sends CarIcons through the Car App Library | hosts `ClusterIconContentProvider` |
| Protocol knowledge | all of it | 16-byte header framing only |

## Cluster (HUD) icons

GoogleTemplatesHost writes each maneuver icon to
`com.google.android.apps.automotive.templates.host.ClusterIconContentProvider` but never
registers that provider (see `gminfo/third_party_access.md`). If nobody claims it, the
host's first `insert()` fails and it stops sending icons for the session. Play only lets the
first publisher declare an authority (issue #6; upstream `zeno.carlink` holds it), so a
fork's Play build can't. The bridge is sideloaded, so it can. The host doesn't check which
package owns the authority, as the old sideload flavor already showed.

The display app needs only package visibility (`<queries>` in its manifest) so that
`NavigationStateManager.initialize()` sees the provider and keeps sending bitmaps. That
check runs once per process, so if you install the bridge while Carlink2 is running,
restart Carlink2.

Icon URIs are content-addressed (bridge v2, [201]): `content://…/img/png_<sha256 prefix>`.
Every `updateTrip` re-sends the icon and the host re-inserts it, so keying the URI on the
picture rather than the host's iconId means the HUD only sees a new URI when the picture
changes. On the app side, the primary step's icon is held until the step changes.

## Data path

```
CPC200 ⇄ USB ⇄ bridge
                ├─ IN:  pump thread → reads header (16 B) then payload (≤16 KB chunks),
                │       same request pattern the old direct path used, forwards only
                │       COMPLETE messages into a Unix socket
                └─ OUT: ICarlinkBridge.write(byte[]) → one bulkTransfer per message,
                        serialized with a lock
display app
   BridgeUsbTransport: reads the socket with poll() timeouts, then reuses the old read
   loop's rules — video demux, buffer reuse, 15 s initial-response and 60 s silence limits
```

Nothing per frame goes over Binder. The bridge doesn't parse message types, so protocol
changes ship through Play and the bridge rarely needs re-sideloading.

`ICarlinkBridge` (bridge-api): `getApiVersion`, `hasPermission(deviceName)`,
`open(deviceName) → ParcelFileDescriptor`, `write(data, timeoutMs)`, `close`, `getStatus`,
and since API 2 (bridge v3, [205]) `requestPermission(deviceName) → PERMISSION_*`.
Add methods only at the end. Transaction codes follow declaration order, and the two apps
update independently.

## Who may call the bridge

The bridge controls the adapter, and the adapter executes files pushed with SendFile
(`/tmp/bin/...`). So the bridge only answers callers whose UID belongs to
`com.enigy.carlink2`. Play guarantees package-name uniqueness, so that check alone is
reasonable. To pin the signing certificate as well, set the **App signing key certificate**
SHA-256 from Play Console (Test and release → App integrity) when building the bridge:

```
# ~/.gradle/gradle.properties
carlink2.callerCerts=AB:CD:...:EF
```

Several fingerprints can be listed, comma-separated (e.g. the upload key for a sideloaded
debug build of the display app). If you pin the Play key, a display app signed with anything
else is rejected and can't connect.

## Connection logic (CarlinkManager.openTransport)

The bridge is the only path ([204]). The display app's direct-USB fallback, its permission
dialog and the boot-time permission probe are gone.

1. Bridge installed and holds permission for the attached `/dev/bus/usb/...` instance →
   open through the bridge. No dialog.
2. Bridge lacks permission → keep retrying for `BRIDGE_PERMISSION_GRACE_MS` (25 s; with
   reconnect backoff that is about 30 s after the first miss). A re-enumeration in that window
   — such as the adapter's own USB reset, see the known issue — brings a fresh grant and an
   immediate reconnect. After it, the **bridge** shows the system USB permission prompt
   ([205]) and the status reads "Approve the USB access prompt". The display app checks for
   the answer every second (`PROMPT_POLL_MS`, up to 2 min). Allow → connects. Deny → "Unplug
   and replug the adapter", and the bridge won't ask again for that device instance: a
   denial sticks until the adapter re-enumerates, so there's no prompt loop. Bridge v2 (API 1)
   can't prompt → straight to the replug message.
3. Bridge not installed → "Install the Carlink2 USB bridge". Any other bridge error (bind
   timeout, rejected, open failed) → "USB bridge not responding — retrying…".

While blocked (2 or 3), the reconnect loop checks the bridge quietly and only starts a
connection once it could succeed, so the screen holds the message instead of cycling
Connecting → Disconnected. An adapter attach broadcast reconnects at once (after the 2.5 s
wake settle).

## Known issue: an adapter that enumerates during boot is never granted

`deviceAttachedForFixedHandler()` grants permission to the package as it exists in the user
that is current **at the moment of attach**. On AAOS the headless system user (0) is current
during early boot, before the switch to the driver (user 12 on the truck). If the adapter
enumerates in that window, the grant goes to user 0's copy of the bridge (or nowhere: the
bridge is only installed for the driver) and the driver's bridge has no permission until the
adapter re-enumerates. Android doesn't re-run attach handling on the user switch; the stock
car USB handler covers this with privileged APIs (`BootUsbService`, `grantPermission`) a
sideloaded app can't use.

**Verified on the truck, 2026-10-09 (v202):** after a cold start the adapter was
`/001/009 perm=false` for 4 minutes. A replug brought it back as `/001/011 perm=true` and it
streamed for 20 minutes without a drop. Also the likely cause of lvalen91's "worked, then
stopped" report on PR #15.

**[208] — letting the adapter re-plug itself.** The adapter's `AutoResetUSB` setting
(firmware default 1) power-cycles its USB when it has no host session, so it re-enumerates
on its own. Any re-enumeration after the driver's profile is up gives the bridge the grant
silently. The fork had set it to 0 in [162], when every re-enumeration meant a new prompt
for the single app; the 4-minute stall above happened with 0. ericyanush's split never sends
the key (adapter at default 1) and reports no prompts on any boot. [208] sends
`AutoResetUSB=1` explicitly — the adapter persists the value, so an adapter that stored 0
must be set back — and waits ~30 s before the bridge's one-time prompt (step 2 above), which
stays as the backstop. Unverified on our truck: the first session after updating stores the
setting; the next cold start is the test (the adapter should come back as a new
`/dev/bus/usb` device soon after boot with `perm=true`, and no `prompt shown` event).

## Diagnostics without adb

The bridge can't be inspected directly, so the display app logs the bridge's own status on
every connect attempt (tag `USB`, level INFO, so it reaches release file logs):

```
[BRIDGE] status: bridge api=1 v=1.0.0 user=10
  handler=android.car.usb.handler/android.car.usb.handler.UsbHostManagementActivity
  devices=[/dev/bus/usb/001/005 1314:1521 perm=true]
  session=closed(last=never opened)
  in=0msg/0B drop(hdr=0 partial=0) out=0 outErr=0
  icons(insert=0 query=0/0miss open=0/0miss) events=[...]
```

What to check in a log:

| Field | Expect | If not |
|---|---|---|
| `handler=` | `android.car.usb.handler/...` | GM changed the overlay; the bridge can't get grants |
| `user=` | `10` | the bridge is bound in another user |
| `perm=` on the 1314:xxxx device | `true` | grant missed (see known issue); the prompt should follow |
| `prompt=` (v3) | `none` | `…(asked)` = prompt shown (allowed or still up); `…(denied)` = replug to be asked again |
| `icons(insert=…)` during nav | climbing | the host isn't reaching the bridge's provider |
| `insert` vs `pictures` vs `ids` (v2) | `pictures` ≈ steps driven | `ids` ≈ `insert` ⇒ host mints an id per tick (v2's URIs cover it) |
| `open` vs `relays=` in `[NAV_HEALTH]` | `open` ≪ `relays` | the HUD re-reads the icon every tick |
| `[NAVI_ICON] Cluster icon provider available via android.car.usb.handler` | present at startup | the `<queries>` entry or the bridge is missing |

Other lines: `[BRIDGE] Adapter … opened through the bridge`, `[BRIDGE] Bridge has no
permission … retrying`, `[BRIDGE] Bridge still has no permission … asking the bridge to
prompt`, `[BRIDGE] USB permission prompt up for …`, `[BRIDGE] USB permission allowed for … —
connecting`, `[BRIDGE] USB permission denied for … — replug to be asked again`,
`[RECONNECT] Still blocked (…) — waiting`, `[USB] Adapter attached while disconnected —
reconnecting`, `[BRIDGE] Bridge stream ended: …`. Bridge events: `prompt shown …`,
`prompt allowed …` / `prompt denied …`.

## Install

1. Uninstall the old sideloaded `com.enigy.carlink` if it is installed. It declares the same
   icon authority, and Android refuses a second claimant
   (`INSTALL_FAILED_CONFLICTING_PROVIDER`). The old Play build doesn't declare it and can
   stay, but two apps fighting over the adapter is not a useful test.
2. Sideload `bridge-release.apk` from the USB drive.
3. Install Carlink2 from Play (internal testing track).
4. Once the head unit has fully started, unplug and replug the adapter so the platform runs
   the fixed handler with the bridge present. (A head-unit power cycle doesn't do it: the
   adapter enumerates before the driver's profile is up. See the known issue.)
5. Launch Carlink2 while parked and export a log.

## Build

```
./gradlew :app:bundleRelease :bridge:assembleRelease
```

- Display AAB: `app/build/outputs/bundle/release/app-release.aab` → Play
- Bridge APK: `bridge/build/outputs/apk/release/bridge-release.apk` → USB drive

Both use `keystore.properties`. The bridge has its own `versionCode` in
`bridge/build.gradle.kts`, so bump it when the bridge changes.
