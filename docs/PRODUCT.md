# Product scope

Hardline Relay displays mesh PLI inside ATAK independently of normal TAK server
connections. Relay App owns private-channel creation and QR provisioning;
the plugin owns PLI intervals, peer display and application receipts. Last-known
positions must not look like proof that a peer is still actively sending.

## Operation

The optional :atak-plugin module targets ATAK CIV API 5.6.0 using the local
5.6.0.23 SDK development host. Version 0.5.0 (5) binds independently to
Meshtastic Android 2.7.13 IMeshService using the ATAK host Context. Meshtastic
owns BLE. The plugin reads channel configuration transiently (including keys for
change detection), never persists it, and never writes radio configuration.
It requires firmware 2.7.15.567b8ea and the lab's fixed 7-hop configuration.

Open Hardline Relay from its toolbar item or the small HL badge above the map's
connection area. Choose an installed channel or a saved Relay profile. A profile
requiring activation opens Relay for passphrase entry and verified configuration;
the plugin itself never writes radio settings. Sending is paused during activation
and remains Off afterward. Installed keys alone do not establish the profile's RF.

Reporting choices are Off / Manual, 10s, 30s, 1m, 2m, 5m and 10m. Short intervals
consume more airtime; start with a slower cadence for larger meshes. Send PLI now
sends a single report when no previous report is waiting. Closing the pane leaves an enabled timer running; Pause PLI
stops it. Plugin unload, detected disconnection or radio/channel changes stop
automatic sending and reset the session. There are no catch-up bursts.

Channel, interval and ACK-wait menus use full-width rows with wrapping labels.
PLI ACK wait / retry defaults to two minutes, with 30s/1m/2m/3m/5m choices.
Only one attempt waits at a time. If a scheduled update is overdue, a matching receipt
releases it; otherwise the deadline marks the old attempt unconfirmed and releases
the newest GPS position under a new token. Previous attempts stay visible and late
ACKs cannot clear a newer wait. Silence cannot distinguish packet loss from a lost
receipt or delay. The pane shows wait age, remaining deadline, held updates,
last-confirmed age, unanswered retained attempts, and independent PLI/point average,
latest and longest round trips with sample counts. Each keeps the last 32 matched
attempts in the current radio session; PLI uses the first peer receipt per attempt.

## ATAK chat

Select a `[Relay]` contact in ATAK's normal chat interface. Explicit one-to-one text
uses the selected private channel; receiving creates a normal ATAK conversation
and returns a processing receipt. DELIVERED means the recipient plugin stored it;
READ is never inferred. The plugin shows unconfirmed after two minutes without a
receipt. Chat does not need a new GPS fix once a contact is available. PLI initially
discovers contacts; received chats can establish reply contacts too.

Messages are limited to 160 UTF-8 bytes (fewer characters with emoji/non-ASCII).
Oversized messages produce an error and are not truncated or split. There is no
public-room forwarding, group fan-out, attachment transport, automatic resend or
TAK-server bridge. Other members of the private channel can decrypt radio packets;
only the addressed plugin imports the conversation. Both users require plugin 0.5.
ATAK owns persistent conversation history; the plugin's pending delivery records
reset when the radio session changes. See the [chat contract](../protocol/chat-v1.md).

## Status and positions

The compact pane shows GPS freshness, your latest PLI/last confirmation, the latest
point attempt, and every contact's position age. Overdue contacts sort first.
Faults reveal the relevant reason and radio settings. The badge is gray with no
selected channel, red for an unavailable radio, amber while waiting for evidence
or resolving activation/position reporting, and green after recent peer evidence.
Green is a recent communication observation, not guaranteed current reachability.
TAK-server connectivity is separate. No Wi-Fi, mobile data or backend is required.

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
outside this first compact format. No automatic resend or periodic point sending. A failed/unconfirmed attempt offers
a manual Retry button, reusing the source marker only while that marker and contact
remain available in the same session.

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

## Evidence boundary

The supported lab uses two Moto G Play 2024 / Android 14 phones, Heltec V3 radios,
Meshtastic Android 2.7.13 and firmware 2.7.15.567b8ea, US LONG_FAST / seven hops.
Manual PLI, same-channel processing receipts and native point sends/receipts have
passed both directions. Protected optical provisioning, wrong-passphrase rejection,
radio restart/reconnect/read-back and offline storage have been exercised on both
phones. Public-compatible and separate-frequency profiles were verified on both,
including passphrase activation from the loaded ATAK dropdown. Native point updates
kept one receiver marker with the new name. Short-interval reporting, Pause and
visible overdue transitions were checked with Wi-Fi/mobile data disabled.
A ten-minute scheduled PLI arrived and was confirmed after the full interval.
Closing the receiving ATAK produced an unconfirmed point; restart and manual Retry
produced Received. Bluetooth loss showed a red badge and cleared selection;
reconnection/reselection restored traffic while automatic reporting remained Off. See DEVELOPMENT.md for repeatable acceptance procedures.

Version 0.5 acceptance on this pair also verified native one-to-one ATAK chat and
DELIVERED receipts in both directions on a private channel, separate PLI and point
round-trip displays, and readable timing menus. With the receiving ATAK stopped,
a ten-second reporting interval held its next update behind a thirty-second ACK
deadline. The old attempt remained visibly unconfirmed when the newest position
was sent; Pause stopped further scheduled sends. No public-room chat was sent.

JVM tests cover wire bounds, addressing, revision ordering, late/duplicate receipts,
stale transitions, all interval values, bounded receipt scheduling, session resets
and submission/receipt races. Build/lint and physical host tests are separate.

This is nearby integration evidence. It does not establish long-range reliability,
third-party public-node forwarding, multi-hop congestion behavior, production host
signing compatibility or operation on every phone/region. Third-recipient filtering
has deterministic tests; a three-radio field exercise remains separate. No ordinary
TAK-server traffic is bridged to LoRa, and live TAK-server coexistence has not been
certified. Do not install another Meshtastic ATAK bridge automatically.
