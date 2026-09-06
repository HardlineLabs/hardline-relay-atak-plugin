# ATAK host integration

Target API: ATAK CIV 5.6.0. Exact SDK/host: 5.6.0.23 (51b827db),
host versionCode 1786740347. The workstation inventory owns the archive pin.

`./tools/build-atak.ps1` verifies local SDK hashes, opts into :atak-plugin,
then builds and lints the CIV debug APK. This passes locally. Host discovery,
loading and opening the development pane pass on the pinned API34 emulator
with SwiftShader graphics for the original scaffold. The PLI build now loads and
binds to real radios on both phones; acceptance remains partial as recorded in
../docs/PRODUCT.md. The emulator does not emulate a LoRa radio.
Normal CI builds only :app (the standalone simulation) and :core; it never
uploads or downloads the restricted SDK.

The official build plugin uses offline SDK mode. No TAK account credentials
are needed. The SDK's publicly supplied test keystore/password is only for
the bundled SDK host, never a production identity. Release variants are disabled.
Keep SDK jars, APK, keystore, guide and licenses outside Git; do not redistribute
the SDK or commit its sample tree. Our entry point uses the public plugin API.

## Local SDK host test

After the emulator boots or exactly two authorized development phones are connected:

1. Run `./tools/build-atak.ps1`.
2. Install the SDK host with `./tools/devices.ps1 install --apk "C:/Users/Aj/HardlineRelayDev/references/ATAK-CIV-5.6.0.23-SDK/ATAK-CIV-5.6.0.23-SDK/atak.apk"`.
3. Install the plugin with `./tools/devices.ps1 install --apk atak-plugin/build/outputs/apk/civ/debug/atak-plugin-civ-debug.apk`.
4. Open ATAK, complete local permission/onboarding prompts, enable Hardline Relay
   in Plugins, then open its toolbar item. Confirm the development disclaimer.
5. Follow the physical PLI acceptance workflow in ../docs/DEVELOPMENT.md.
   Verify Plugins > Hardline Relay says Loaded after every update/restart;
   one lab phone required manual re-enabling after APK updates.

For one emulator append `--allow-emulator --serial emulator-5554 --count 1`,
using the actual serial from `./tools/devices.ps1 list`.
Never silently uninstall a production ATAK installation to change signing identities.
The generic `devices.ps1 test` runs the standalone harness tests, not ATAK-host tests.
The SDK host has a development watermark/test signature. It is not byte-identical
to production 5.6.0.CIV; matching API is not production signing/load compatibility.

TAK.gov is informational/manual-download-only. Uploads, signing requests, support
contact, account changes or any other submissions need explicit creator approval.
If the SDK is missing, have the creator download the pinned ZIP manually from
ATAK-CIV 5.6 > Developer Resources. Do not automate or bypass its download block.
