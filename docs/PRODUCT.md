# Product scope

Hardline Relay displays mesh PLI inside ATAK independently of normal TAK server
connections. Relay App owns private-channel creation and QR provisioning;
the plugin owns PLI intervals, peer display and application receipts. Last-known
positions must not look like proof that a peer is still actively sending.

## Implemented experiment, not completed acceptance

The optional :atak-plugin module targets ATAK CIV API 5.6.0 using the local
5.6.0.23 SDK development host. Version 0.2.0-dev (2) binds independently to
Meshtastic Android 2.7.13 IMeshService using the ATAK host Context. Meshtastic
owns BLE. The plugin reads channel configuration transiently (including keys for
change detection), never persists it, and never writes radio configuration.
It requires firmware 2.7.15.567b8ea and the lab's fixed 7-hop configuration.

In Tools > Hardline Relay, select a private secondary channel provisioned by Relay.
Controls are Send PLI now, Off, Every 10s and Every 30s. Sending defaults Off;
selecting a channel enables reception, not automatic transmission. Closing the
pane does not stop an enabled timer: choose Off explicitly before leaving.
Plugin unload, detected disconnection or radio/channel change stops automatic
sending and clears session state. Do not edit channels concurrently in Meshtastic.

The [PLI contract](../protocol/pli-v1.md) owns exact packet fields, freshness
thresholds, bounds and trust limitations. PLI uses ATAK's self position guarded by
a recent matching phone GPS fix. Received PLI creates/updates plugin-owned map
markers and a peer list. Fix time and local receipt age are separate. Stale entries
are last-known, not an online guarantee. Local disconnection/channel reset currently
removes entries rather than retaining an offline history; this differs from a peer
simply ceasing to send while the receiver remains connected.

Receipts correlate to individual packets and should follow the receiver's marker
update. Submission, radio delivery and application processing are different states.
A missing receipt means unconfirmed, not proven loss. No backend, enrollment or
cryptographic sender authentication is implemented. Meshtastic's exported receive
broadcast is an untrusted local-app boundary; use trusted lab phones only.

## Evidence and remaining gates

Observed on two Moto G Play 2024 / Android 14 phones with Heltec V3 radios:

- SDK host/plugin installation, host loading and AIDL radio/channel reads pass.
- Manual PLI arrived in each direction on RelayTest. Peer panes displayed callsigns,
  fix times and receipt ages. This was real RF, not a fake transport.
- Initial direct-message return receipts did not appear. The current build changes
  receipts to same-channel broadcasts. That change builds and is installed, but
  its two-way receipt acceptance test has not completed. Root cause of the original
  missing receipts was not conclusively established.
- GPS permissions were enabled; poor indoor reception initially prevented fixes.
  Window placement produced fixes and allowed the manual PLI test.
- HLM1 subsequently lost BLE; the plugin detected disconnection, reset selection
  and showed automatic Off. Controlled reconnection/recovery is not yet verified.

Local checks pass: 13 core JVM tests (10 PLI and 3 independent status tests),
seven device-script tests, standalone APK/instrumentation APK builds and lint,
and actual ATAK SDK plugin build/Android/TAK lint. Unit tests are not proof of
radio receipts or map rendering. The separate :app remains a labelled simulation
harness; its instrumentation does not test the loaded ATAK plugin.

Pending: same-channel receipts both ways; visual marker location/title verification;
bounded 10s/30s runs; Off/no-more-sends; stale transitions/recovery; restart/reload;
BLE loss/reconnect; real TAK-server coexistence; range/airtime/reliability; production
host/signing compatibility. Do not call this milestone complete yet. Do not bridge
ordinary TAK-server CoT or install the reference Meshtastic ATAK plugin alongside
this experiment automatically. See DEVELOPMENT.md for acceptance and review gaps.
