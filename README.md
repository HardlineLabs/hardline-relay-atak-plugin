# Hardline Relay ATAK plugin

Independent mesh status inside ATAK CIV 5.6.0.
Development scaffold; not an operational ATAK plugin yet.

Start with [development](docs/DEVELOPMENT.md) and [product scope](docs/PRODUCT.md).
Run `./tools/check.ps1` from PowerShell. Open the IDE with `./tools/open-studio.ps1`.

`:app` builds a standalone, clearly labelled simulation harness. `:core` tests
mesh status independently of TAK server connectivity. This APK is not an ATAK
plugin. The real [ATAK host integration](atak-plugin/README.md) is gated on
the exact 5.6 SDK and matching development host APK from TAK.gov.

Versioned status vectors live in [protocol](protocol/README.md); the
[Relay App repository](https://github.com/HardlineLabs/hardline-relay-app) owns
contract changes and future radio configuration.
