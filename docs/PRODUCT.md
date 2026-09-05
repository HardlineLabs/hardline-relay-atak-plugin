# Product scope

Display Hardline mesh status inside ATAK CIV 5.6.0 while preserving normal
ATAK server connections. The plugin must remain useful when a TAK server is
connected, disconnected, or absent. The Relay App owns simple Meshtastic setup.

The initial integration choice is independent, read-only binding to Meshtastic
2.7.13 IMeshService from the ATAK host. This avoids a second configuration service
or giving a plugin access to stored channel keys. Verify the host Context,
class loading, lifecycle, signing, and API dependency behavior against the exact SDK.

The current standalone harness demonstrates status presentation with fake radio
and TAK-server states. It never connects to a radio or an ATAK host. Its simulation
labels must remain visible. A radio connection is not proof of mesh peer reachability.

The separate :atak-plugin module builds a minimal toolbar/pane entry point against
the official local 5.6.0.23 SDK and uses the tested presenter. It has no radio
binding yet. Discovery, loading and pane display pass in the pinned emulator host.
Follow atak-plugin/README.md to repeat host checks on the actual phones.
No production signing or real-radio compatibility is claimed by this baseline.

CoT routing, position display, chat, airtime policy, duplicate suppression and
real peer-reachability detection are later increments. Do not bridge TAK server
traffic onto LoRa automatically.
