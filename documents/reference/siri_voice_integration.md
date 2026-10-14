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

## Carlink2: on/off setting ([217])

Ported from ericyanush's commit `6da7265` on lvalen91/carlink#15. Carlink2 sends the
press/release from `CarlinkManager.requestSiri()` on its own IO path (the original used
`failureGate`/`commandWorker`, which Carlink2 doesn't have); a request queued for a session that
has since been replaced, or that waited over 2 s, is dropped.

**Settings → Adapter Configuration → Misc → Voice Button** chooses **Siri** (default) or **Off**.
Off disables the three assistant components (`SiriVoiceInteractionService`,
`SiriVoiceSessionService`, `SiriRecognitionService`) with `setComponentEnabledSetting`, so the
system can't select or invoke Carlink2 Siri and the button goes to the car's own assistant.
MainActivity re-applies the state on every start, and Reset to Defaults turns it back on. It is an
app-only setting: Apply saves it without reconnecting the adapter. A session already starting when
it is switched off reports "Voice button to Siri is off in Carlink2 settings".

Choosing Carlink2 Siri as the default assistant is a one-time step in the car's settings; Android
doesn't let an app make itself the assistant. After switching Off and back to Siri, the car may need
Carlink2 Siri chosen again.
