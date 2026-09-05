# ATAK host integration

Target API: ATAK CIV 5.6.0. Exact SDK/host: 5.6.0.23 (51b827db),
host versionCode 1786740347. The workstation inventory owns the archive pin.

`./tools/build-atak.ps1` verifies local SDK hashes, opts into :atak-plugin,
then builds and lints the CIV debug APK. This passes locally. Host discovery,
loading and opening the development pane pass on the pinned API34 emulator
with SwiftShader graphics. Radio binding is not implemented or tested yet.
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
5. Close/reopen its pane and restart ATAK. Check that normal TAK connections remain
   untouched. Add read-only Meshtastic AIDL binding only after this baseline works.

For one emulator append `--allow-emulator --serial emulator-5554 --count 1`,
using the actual serial from `./tools/devices.ps1 list`.
Never silently uninstall a production ATAK installation to change signing identities.
The generic `devices.ps1 test` runs the standalone harness tests, not ATAK-host tests.

TAK.gov is informational/manual-download-only. Uploads, signing requests, support
contact, account changes or any other submissions need explicit creator approval.
If the SDK is missing, have the creator download the pinned ZIP manually from
ATAK-CIV 5.6 > Developer Resources. Do not automate or bypass its download block.
