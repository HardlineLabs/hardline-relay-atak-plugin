# Hardline Relay ATAK plugin

Compact PLI and point sharing over Meshtastic, with independent mesh status inside
ATAK. A Hardline Labs hobby project built for fun and experimentation. **Work in
progress**; nearby lab tests are not a mission-critical reliability certification.

Choose a channel, choose a reporting interval, and use ATAK's normal point Send
picker with contacts learned from received PLI. The pane shows the latest point
receipt, your latest PLI and each contact's position age. A small colored **HL**
badge opens the pane and shows recent mesh evidence independently of TAK-server
connectivity. Troubleshooting details appear when something needs attention.

- [Behavior, packet sizes and limits](docs/PRODUCT.md)
- [Build and acceptance checks](docs/DEVELOPMENT.md)
- [Actual ATAK SDK integration](atak-plugin/README.md)
- [Relay channel app](https://github.com/HardlineLabs/hardline-relay-app)
- [Wire contracts](protocol/README.md) and [third-party notices](docs/THIRD_PARTY.md)

The actual plugin targets the official ATAK CIV 5.6.0.23 SDK host. Its SDK test
signature does not establish compatibility with production-signed ATAK installs.
The restricted SDK and its keys/binaries are not included. `:app` is a standalone
simulation harness; its APK is not the ATAK plugin. CI tests that harness and
`:core`; the SDK plugin is built and tested separately on a licensed local setup.
