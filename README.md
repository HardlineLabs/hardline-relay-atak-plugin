# Hardline Relay ATAK plugin

Independent mesh status inside ATAK CIV 5.6.0.
Private PLI/compact-point experiment in progress; not a production release.

Start with [development](docs/DEVELOPMENT.md) and [product scope](docs/PRODUCT.md).
Run `./tools/check.ps1` from PowerShell. Open the IDE with `./tools/open-studio.ps1`.

`:app` builds a standalone, clearly labelled simulation harness. `:core` tests
mesh status independently of TAK server connectivity. This APK is not an ATAK
plugin. The real [ATAK host integration](atak-plugin/README.md) builds separately
against the local official 5.6.0.23 SDK. Physical-phone AIDL binding and manual PLI
reception in both directions have passed. The pane shows callsign, fix time and
receipt age. Manual PLI receipts and compact point sends/receipts pass both ways;
automatic intervals and visible PLI map staleness still need hardware acceptance;
see [current scope](docs/PRODUCT.md).

Received PLI populates Relay contacts for ATAK's normal point Send picker.
The plugin pane shows the last point's submission/receipt status separately from
PLI. [Compact points](protocol/point-v1.md) fit in 48–71 bytes plus transport overhead.

Versioned status vectors live in [protocol](protocol/README.md); the
[Relay App repository](https://github.com/HardlineLabs/hardline-relay-app) owns
contract changes and radio configuration. The plugin owns PLI intervals and display.
