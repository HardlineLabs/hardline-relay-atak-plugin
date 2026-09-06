# Product scope

Hardline Relay displays mesh PLI inside ATAK independently of normal TAK server
connections. Relay App owns private-channel creation and QR provisioning;
the plugin owns PLI intervals, peer display and application receipts. Last-known
positions must not look like proof that a peer is still actively sending.

## Implemented experiment, not completed acceptance

The optional :atak-plugin module targets ATAK CIV API 5.6.0 using the local
5.6.0.23 SDK development host. Version 0.3.0-dev (3) binds independently to
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

## Compact point sending

Received PLI also populates namespaced Relay contacts in ATAK's normal recipient
picker. Select one supported point, use ATAK Send, choose the contact labelled
Relay and tap Send. The IpConnector routes that explicit action into this plugin;
ordinary TAK contacts/server traffic are not intercepted. Stale Relay contacts
remain visibly STALE. A callsign is a display name; radio node ID identifies the peer.

The [point contract](../protocol/point-v1.md) defines the 48–71-byte point and
17-byte receipt. Only the selected recipient imports and acknowledges, using a
same-channel broadcast with a recipient field. Other channel members can still
read it; recipient selection is not recipient-only encryption. Supported basic
spot and ground markers carry coordinates, a name of up to 24 UTF-8 bytes, symbol
and color. Unsupported selections and long names fail visibly rather than silently
changing the point. Altitude, remarks, files, shapes/routes and custom icons are
outside this first compact format. No automatic resend or periodic point sending.

The plugin pane has a separate **Last point** indicator: SUBMITTING, AWAITING
RECEIPT, RECEIVED (green), UNCONFIRMED (amber), or SEND FAILED (red). It includes
the point name and recipient for an encoded attempt. RECEIVED requires that peer's
plugin receipt after marker creation/update; radio submission is not receipt.
After 60 seconds without confirmation it shows delivery unknown, not proven loss.
Late matching receipts can confirm within five minutes in the same session.
Unrelated PLI, older sends and wrong-peer receipts cannot replace this result.

Repeated sends update the same received marker; older revisions cannot move it
backwards. Received points show last update/receive time separately from PLI
freshness. Up to 128 received points remain in memory through BLE loss/channel
reselection and disappear on plugin unload/ATAK restart. They are not yet durable
ATAK imports. A plugin restart clears the last-send indicator and Relay contacts.

## Evidence and remaining gates

Observed on two Moto G Play 2024 / Android 14 phones with Heltec V3 radios:

- SDK host/plugin installation, host loading and AIDL radio/channel reads pass.
- Manual PLI arrived in each direction on RelayTest. Peer panes displayed callsigns,
  fix times and receipt ages. This was real RF, not a fake transport.
- Initial direct-message return receipts did not appear. The current build changes
  receipts to same-channel broadcasts. Manual PLI and those application receipts
  passed both ways during compact-point acceptance on the existing private channel.
  Root cause of the original missing direct-message receipts was not established.
- Compact point Send through ATAK's native recipient picker passed both ways.
  PLI-derived Relay contacts were selectable, each recipient showed the named
  ground marker on its map, and each sender showed Last point: RECEIVED.
  Tests used explicit named test points, not substituted phone GPS positions.
- GPS permissions were enabled; poor indoor reception initially prevented fixes.
  Window placement produced fixes and allowed the manual PLI test.
- HLM1 subsequently lost BLE; the plugin detected disconnection, reset selection
  and showed automatic Off. Controlled reconnection/recovery is not yet verified.

Local checks pass: 26 core JVM tests (10 PLI, 10 point, 3 session and 3 status tests),
seven device-script tests, standalone APK/instrumentation APK builds and lint,
and actual ATAK SDK plugin build/Android/TAK lint. Unit tests are not proof of
radio receipts or map rendering. The separate :app remains a labelled simulation
harness; its instrumentation does not test the loaded ATAK plugin.

Pending: extended point update/loss/recovery and third-peer hardware acceptance;
complete PLI visual marker location/title verification;
bounded 10s/30s runs; Off/no-more-sends; stale transitions/recovery; restart/reload;
BLE loss/reconnect; real TAK-server coexistence; range/airtime/reliability; production
host/signing compatibility. Do not call this milestone complete yet. Do not bridge
ordinary TAK-server CoT or install the reference Meshtastic ATAK plugin alongside
this experiment automatically. See DEVELOPMENT.md for acceptance and review gaps.
