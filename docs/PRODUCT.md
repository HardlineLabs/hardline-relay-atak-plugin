# Product scope

Hardline Relay displays mesh PLI inside ATAK independently of normal TAK server
connections. Relay App owns private-channel creation and QR provisioning;
the plugin owns PLI intervals, peer display and application receipts. Last-known
positions must not look like proof that a peer is still actively sending.

## Operation

The optional :atak-plugin module targets ATAK CIV API 5.6.0 using the local
5.6.0.23 SDK development host. Version 0.6.0 (6) binds independently to
Meshtastic Android 2.7.13 IMeshService using the ATAK host Context. Meshtastic
owns BLE. The plugin reads channel configuration transiently (including keys for
change detection), never persists it, and never writes radio configuration.
It requires firmware 2.7.15.567b8ea and the lab's fixed 7-hop configuration.

Open Hardline Relay from its toolbar item or the small HL badge above the map's
connection area. Choose an installed channel or a saved Relay profile. A profile
requiring activation opens Relay for passphrase entry and verified configuration;
the plugin itself never writes radio settings. Sending is paused during activation
and remains Off afterward. Installed keys alone do not establish the profile's RF.

Reporting choices remain Off / Manual, 10s, 30s, 1m, 2m, 5m and 10m. There is no
operator retry-timer menu: recovery is automatic for both manual and scheduled PLI.
Pause cancels pending PLI submissions. Closing the pane leaves enabled reporting
running. Brief connection interruptions retain last-known contacts and recover on
the same verified radio/channel; a changed configuration or unload resets the session.
No catch-up burst is sent. Connection details are expandable; firmware submission
and peer processing confirmation remain distinct.

The [0.6 PLI/recovery contract](../protocol/pli-v2.md) owns exact packet fields,
timing, freshness, limits and compatibility. Upgrade both plugins to 0.6. The
receiver still accepts legacy PLI. New PLI is compact and standalone; chat/point
formats remain unchanged. Radio LongFast/hop settings are preserved.

## Text transport

Enable **Text transport** when private data is struggling on a busy mesh. Every
outbound plugin packet then uses framed channel text, including PLI, points, chat
and receipts. All updated teammates receive either transport without changing
their own toggle. Each device controls how it sends. The choice is remembered.

Text gets Meshtastic's higher text queue priority, but Base64 adds overhead and
HLR1 messages appear in the channel conversation. It does not reserve airtime.
Use chat bodies of 107 UTF-8 bytes or fewer in this mode; larger packets fail
visibly rather than being fragmented. Positions/points/receipts already fit.
The current wire contract owns exact limits and mixed-mode behavior.

## ATAK chat

Select a `[Relay]` contact in ATAK's normal chat interface. Explicit one-to-one text
uses the selected private channel; receiving creates a normal ATAK conversation
and returns a processing receipt. DELIVERED means the recipient plugin stored it;
READ is never inferred. The plugin shows unconfirmed after two minutes without a
receipt. Chat does not need a new GPS fix once a contact is available. PLI initially
discovers contacts; received chats can establish reply contacts too.

Messages are limited to 160 UTF-8 bytes (fewer characters with emoji/non-ASCII).
Oversized messages produce an error and are not truncated or split. There is no
public-room forwarding, group fan-out, attachment transport or
TAK-server bridge. Other members of the private channel can decrypt radio packets;
only the addressed plugin imports the conversation. Both users should use plugin 0.6.
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

The [PLI contract](../protocol/pli-v2.md) owns exact packet fields, freshness
thresholds, bounds and trust limitations. PLI uses ATAK's self position guarded by
a recent matching phone GPS fix. Received PLI creates/updates plugin-owned map
markers and a peer list. Fix time and local receipt age are separate. Stale entries
are last-known, not an online guarantee. Brief disconnection retains last-known entries; explicit channel/configuration change or unload clears the session. Positions are not persisted across ATAK restarts.

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
outside this first compact format. Bounded automatic recovery uses the shared outbox; no periodic point sending. A failed/unconfirmed attempt offers
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
