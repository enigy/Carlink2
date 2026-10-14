# Carlink2

Carlink2 is enigy's two-app build of Carlink for the GM gminfo3.7 head unit. It's a native Kotlin
app based on [lvalen91/carlink](https://github.com/lvalen91/carlink), using the two-app split from
Eric Yanush's [PR #15](https://github.com/lvalen91/carlink/pull/15). The two APKs work as one:

| App | Package | Installed via | Owns |
|---|---|---|---|
| Display | `com.enigy.carlink2` | Google Play (required: only Play installs keep the screen while driving) | All UI, CarPlay/AA protocol, video, audio, cluster session |
| USB bridge | `android.car.usb.handler` | Sideload from a USB drive | The adapter's USB connection and the HUD icon provider |

GM's head unit hands every USB device to an app named `android.car.usb.handler`, so the bridge gets
USB permission without the usual dialog. It also hosts the HUD icon provider that Play won't give a
fork, so maneuver icons show on the HUD. Details and diagnostics:
[documents/reference/carlink2_bridge.md](documents/reference/carlink2_bridge.md).

## Requirements

- [Carlinkit CPC200-CCPA](https://www.carlinkit.com/ccpa) adapter on firmware 2025.10
- AAOS 10 or later. Developed and tested only on the gminfo3.7 (AAOS 12)
- A USB drive to install the bridge

## Install

1. If the old sideloaded Carlink (`com.enigy.carlink`) is installed, uninstall it first. It claims
   the same HUD icon provider, and Android won't install the bridge alongside it.
2. Install Carlink2 from Google Play. <!-- TODO: Play link -->
3. Copy [`app/carlink2_bridge.apk`](app/carlink2_bridge.apk) (bridge 1.0.2) to a USB drive.
   SHA-256: `aff977cc82806016a1401d182cc0f0ce88c711c16aca66ef7c4db613e879079c`
4. While parked, open Carlink2. It shows **USB bridge not installed** with an **Open Files** button
   that opens the head unit's hidden Files app. Insert the USB drive, Open the drive and tap the APK. If asked, allow Files
   to install apps.
5. When the install finishes, tap the Carlink2 notification (or press Home and open Carlink2).
   Carlink2 connects once you're back.
6. If the adapter was already plugged in, the bridge may show a one-time USB permission prompt.
   Tap Allow, or unplug and replug the adapter.

`adb install` works for the bridge too.

## What's new in Carlink2

Numbers in brackets are entries in [documents/revisions.txt](documents/revisions.txt).

**Connection**
- No USB permission dialog: the bridge owns the adapter, and Carlink2 never touches USB itself ([200], [204]).
- An adapter that was plugged in while the head unit booted no longer gets stuck without permission.
  The adapter re-plugs itself shortly after boot, which gives the bridge permission silently; if that
  doesn't happen, the bridge asks once ([205], [208]).
- **Reset Device** now actually reboots the adapter, which takes about a minute ([209]).
- When a wireless CarPlay phone never starts its session (for example, another iPhone grabbed the
  adapter's Wi-Fi first), Carlink2 reboots the adapter by itself instead of sitting on "Phone rejected
  configuration" ([209]).
- Disconnects are handled once, off the UI thread, with one quick retry when the phone isn't found ([206]).
- Setup help for new installs: a missing-bridge panel with **Open Files**, a notification that leads
  back from Files, and a **USB bridge isn't starting** panel with an App info shortcut ([210]–[212]).

**HUD and cluster navigation**
- Turn icons are twice as large, no longer flicker, and are all the same size ([201], [202]).
- Icons are drawn from the junction Apple sends, with the real exit angle and side roads ([203]).
- Navigation no longer drops off the HUD about 650–700 ft before the destination ([207]).
- The HUD's text and icon stay in step with the phone after a reconnect mid-route ([213]).

**Display**
- No more flash of the system bars, widget panel or a black screen when the phone connects ([214], [215]).
- **Background Logo** setting: GMC or CHEVROLET on the connecting screen ([216]).

**Voice**
- The steering wheel voice button can start Siri, ported from Eric Yanush's work in PR #15. To turn it
  on, choose **Carlink2 Siri** as the default assistant in the truck's settings ([217], [218]). New,
  and still being confirmed on the truck.
  See [documents/reference/siri_voice_integration.md](documents/reference/siri_voice_integration.md).

## Known issues

- **Voice phone calls are hard to hear for the other person.** Researching audio and echo-cancellation options.

> [!IMPORTANT]
> My 2025 GMC Sierra with HUD (gminfo3.7, Intel, AAOS) is the target platform and my only hardware
> for testing. My phone is an iPhone, so CarPlay has had the most tuning.

> [!WARNING]
> Compatibility with anything else is **untested**. Carlink2 is tuned for video and audio performance
> on the gminfo3.7, which is my only focus. Feel free to fork it and tune it for your own setup.

> [!TIP]
> Before reporting audio issues:
> 1. By default the adapter carries all audio, including the microphone. Unpair the phone and the
>    vehicle from each other's Bluetooth, and make sure Carlink2 has microphone access.
> 2. For the steering wheel voice button, choose **Carlink2 Siri** as the truck's default assistant
>    (see What's new). Steering wheel call controls are a system feature: if you need them, skip step 1
>    and set the in-app audio routing to Bluetooth, so the phone and vehicle stay connected for all audio.

## Custom adapter firmware

Two adapter programs were patched to fix problems in Carlinkit's firmware. These patches need custom
firmware on the adapter. It isn't required, but it gives a more complete, closer-to-stock experience.

1. Carlinkit's firmware strips the rich iAP2 navigation data the iPhone sends. The patch forwards the
   full route information, which the app uses to draw HUD turn icons, including roundabouts drawn
   from the real junction arms. CarPlay doesn't send ready-made icons; Android Auto does, and those are
   forwarded to the cluster as-is.
2. Carlinkit's Android Auto program mis-parsed the vehicle GPS (NMEA) that the app forwards to the
   phone. The patch fixes it so Android phones receive accurate GPS.

## Screenshots (Android emulator with USB passthrough to the CPC200-CCPA)

![Screenshot of Android Auto via Adapter from Pixel 10](/screenshots/Aauto.png)
![Screenshot of Apple CarPlay via Adapter from iPhone Air](/screenshots/Carplay.png)

### Main app screen
![Screenshot of Main App Screen](/screenshots/MainPage.png)

### Adapter configuration

Most changes take effect when you tap **Apply**, which restarts the adapter session in place. GPS
Forwarding, WiFi Band and Cluster Navigation use **Apply & Restart**, which restarts the app.
Background Logo applies right away with no reconnect.

![](/screenshots/adapter_config-Audio.png)
![](/screenshots/adapter_confid-Visual.png)
![](/screenshots/adapter_config-Misc.png)

### Display mode

Controls which system bars are shown or hidden, to give the projection more room.

![](/screenshots/Settings-DisplayMode.png)

### Exporting logs

When file logging is on, you can export the app's logs to a file. Export uses Android's built-in Files
app (the document picker), so Carlink2 doesn't need permission to your folders, and you can save
straight to a USB drive.

> [!CAUTION]
> OS restrictions apply.

![](/screenshots/File_Logging.png)

### Log levels

The app is very verbose, and more information makes troubleshooting easier. Log levels let you focus
on the areas you need.

![](/screenshots/LogLevels.png)

# Documentation

I, or mostly Claude, have collected and organized as much documentation as I can about the app, the
adapter and the gminfo, to help me and others understand them. Commits with no code changes are usually
new documentation or corrections.

> [!IMPORTANT]
> I can't promise all of it is accurate, but it's the most detailed, centralized source you'll likely
> find without access to the adapter's or GM's source code. If you have that access, I know a guy and a
> site who will gladly publish it anonymously.

Most questions are answered in [documents/reference](/documents/reference/). Good starting points:
- [carlink2_bridge.md](documents/reference/carlink2_bridge.md): the two-app design, install and diagnostics
- [siri_voice_integration.md](documents/reference/siri_voice_integration.md): the voice button
- [revisions.txt](documents/revisions.txt): every build and what changed

Ask on the [XDA thread](https://xdaforums.com/t/carlink.4774308/), and report issues on GitHub or XDA.

# Credits

Repos and people that provided stepping stones and foundations for this app:

- [Carlink by lvalen91](https://github.com/lvalen91/carlink): the native Kotlin port this fork comes from
- [Eric Yanush](https://github.com/ericyanush): the two-app split ([PR #15](https://github.com/lvalen91/carlink/pull/15)) and the voice button → Siri assistant
- [CPC200-CCPA resources by lvalen91](https://github.com/lvalen91/CPC200-CCPA_resources): adapter firmware and reverse engineering
- [ludwig-v's wireless CarPlay dongle reverse engineering](https://github.com/ludwig-v/wireless-carplay-dongle-reverse-engineering): firmware decryption and unpacking
- [fileslauncher by muzso](https://github.com/muzso/fileslauncher): the idea behind the **Open Files** button
- [CarPlay by abuharsky](https://github.com/abuharsky/carplay): the original Android implementation
- [node-CarPlay by rhysmorgan134](https://github.com/rhysmorgan134/node-CarPlay): protocol reverse engineering
- [LIVI by f-io](https://github.com/f-io/LIVI): Linux (Raspberry Pi) and macOS implementation
- [pycarplay by electric-monk](https://github.com/electric-monk/pycarplay): Python implementation
