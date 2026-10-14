# Steering wheel voice button and Siri

Carlink2 exposes a minimal Android voice interaction service so AAOS can route
the configured assistant invocation to the connected CarPlay adapter. Select
**Carlink2 Siri** as the vehicle's default assistant to enable this route. The
service does not implement speech recognition or collect assistant context.
AAOS requires a recognizer component in this descriptor; its minimal service
declines local recognition without opening the microphone. Siri handles speech
through the connected phone.

When invoked, it sends the adapter's Siri button-down and button-up commands
through the active CarPlay session. The adapter's `AUDIO_SIRI_START` event then
activates Carlink's existing projection microphone and audio path. No new
recording path is started. If CarPlay is disconnected, the call is active, or a
voice request is already in progress, the request is declined; the app does not
start or reconnect a projection session on the assistant's behalf.

The command pair is sent on a background worker. Repeated requests are
debounced, and queued commands are discarded if their session becomes stale.
The assistant session closes after forwarding the request so the vehicle can
continue its normal projection flow. A brief message is shown only when the
request cannot be sent.

This depends on the vehicle allowing Carlink to be selected as its default
assistant and routing the steering wheel's short press to that assistant. Long
press behavior may remain owned by GM's projection or Bluetooth integration.

## Carlink2 port ([217], [218])

Ported from ericyanush's commit `6da7265` on lvalen91/carlink#15. Carlink2 sends the
press/release from `CarlinkManager.requestSiri()` on its own IO path (the original used
`failureGate`/`commandWorker`, which Carlink2 doesn't have); a request queued for a session that
has since been replaced, or that waited over 2 s, is dropped.

There is no on/off switch in Carlink2: choosing **Carlink2 Siri** as the car's default assistant
in the car's settings turns it on, and choosing another assistant turns it off. [217] briefly had
a Voice Button setting that disabled the assistant components; [218] removed it, and Carlink2
re-enables those components at startup if that setting had switched them off.

**Showing up in the car's assistant list ([219]).** Android only offers an app as the default
assistant when its voice interaction service declares `android:supportsAssist="true"` in
`res/xml/siri_voice_interaction.xml` (the ASSISTANT role needs a service that "is capable of
handling the assist action"). [217] shipped `false`, from upstream `6da7265`, so Carlink2 Siri never
appeared in the truck's digital assistant menu; ericyanush's follow-up `aec6b3d` sets it to `true`.
