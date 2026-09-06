# Development

Use PowerShell 7, Microsoft JDK 17.0.20.1+1, Android platform 36 revision 2,
build-tools 36.0.0 and Python 3.13.15. Gradle's checked-in wrapper owns its version.
Set JAVA_HOME and ANDROID_HOME (or an untracked local.properties SDK path), then run:

```powershell
./gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
python -m unittest discover -s tools -p 'test_*.py'
```

The workstation convenience scripts use HARDLINE_RELAY_DEV, defaulting to
`$env:USERPROFILE/HardlineRelayDev`, with `tools/jdk-17.0.20.1+1` and `AndroidSdk`
inside it. They do not download the restricted ATAK SDK. The workflow under
.github/workflows/validate.yml records the public CI toolchain and download hashes.

## Daily loop

```powershell
.\tools\check.ps1
.\tools\devices.ps1 list
.\tools\devices.ps1 install
.\tools\devices.ps1 test
```

Install/test defaults to exactly two authorized physical devices. More, fewer,
offline, or unauthorized devices fail before installation. To select one:
`./tools/devices.ps1 install --serial SERIAL --count 1`.
Explicitly select both phones with two `--serial` arguments if other devices
are connected. Emulator use additionally requires `--allow-emulator`.

Live tools use one terminal per phone and do not save logs:
`./tools/devices.ps1 logs --serial SERIAL --count 1` and
`./tools/devices.ps1 mirror --serial SERIAL --count 1`. Stop with Ctrl+C.
Logs show HardlineRelay and crash entries only. Never collect Meshtastic packet
payloads, channel URLs, QR images, or keys in routine diagnostics.

`./tools/start-emulator.ps1` starts Hardline_Relay_API34 after the hypervisor
is enabled and Windows has restarted. Then use `./tools/check.ps1 -Connected`.
The emulator tests Android UI/lifecycle; it does not emulate Bluetooth LoRa radios.
Startup now waits for Android's package service, not just ADB connectivity.
It uses software graphics, 3 GB RAM and four virtual CPU cores on this workstation.
Hardware graphics caused the ATAK SDK host to crash with "No config chosen";
SwiftShader renders it successfully. `-Graphics host` is an optional alternative
for other workloads, not the verified ATAK baseline.
Only one Hardline emulator uses port 5554. An already-running instance is reused.
For the actual SDK-host plugin build and install, use ../atak-plugin/README.md.

## Build outputs

The daily install/test commands above target the standalone harness. For real
ATAK work run `./tools/build-atak.ps1`, then install the explicit APK:

```powershell
./tools/devices.ps1 install --apk atak-plugin/build/outputs/apk/civ/debug/atak-plugin-civ-debug.apk --serial SERIAL_A --serial SERIAL_B --count 2
./tools/compare-contract.ps1 -OtherRepository '../Hardline Relay App'
```

## Physical PLI acceptance

1. Read PRODUCT.md for the evidence boundary. Verify both ADB devices, fixed
   versions, radio power/antennas, and Meshtastic Connected to the correct radio.
   Use the same verified profile on both radios, US LONG_FAST and seven hops.
2. Open ATAK on each phone. Confirm precise location permission and a live GPS
   fix; window/outdoor placement was needed indoors. No mock positions or silent
   network-location fallback. Keep phone clocks automatic for fix-time checks.
3. In ATAK Plugins, load Hardline Relay if necessary. Open Tools > Hardline Relay
   and choose the same channel on each. Verify reporting is Off / Manual.
4. Send one manual PLI from A. On B verify peer pane AND actual map marker/location,
   callsign, fix time and receipt age. On A require CONFIRMED for the matching
   token, attributed to B. Repeat B to A. Submitted alone is not acceptance.
5. Only after receipts work, run one sender at 10s for roughly three updates,
   then Off. Repeat at 30s. Observe intervals and absence of catch-up traffic.
   Leave both Off. Do not run unattended high-rate seven-hop tests.
6. Stop peer sending while keeping the receiver connected. Verify its marker and
   peer pane become STALE after the contract threshold; receipt-only traffic must
   not refresh PLI age. Resume one fresh PLI and verify recovery without duplicates.
7. Test pane close/reopen, plugin reload, ATAK restart, BLE loss/reconnect and
   channel-change guards separately. Require reselecting a channel after reset,
   with Auto Off. Real TAK-server coexistence needs its own test; no automatic
   CoT dispatch is implemented.

Use live, narrowly filtered diagnostics. Android UI dumps may be staged at
`/data/local/tmp/relay-diagnostic-ui.xml`, read, then immediately removed. Do not
save location screenshots, Meshtastic packet logs, channel secrets or QR payloads.
ATAK's map/toolbars are partly canvas-based; empty UI text is not proof of a crash.
Check loading/screen wake state before interacting. Handle physical permission prompts on the phone; emulator provisioning is a
separate workflow.

## Compact point acceptance

Use 0.4.0 (4) on both SDK hosts. Verify the installed hash recorded in the
workstation inventory; a version name alone is not sufficient during development.

1. Keep both automatic PLI modes Off and select the existing private channel.
   Send one real manual PLI each way; require both application receipts.
2. Create a basic named spot/ground marker in ATAK. Use its normal Send control,
   choose the peer labelled `[Relay]` and Send. Do not choose an unrelated ordinary
   TAK contact. A stale Relay contact remains labelled STALE but can be attempted.
3. Open Hardline Relay on the sender. Require Last point: RECEIVED for that point
   and selected peer. On the recipient, inspect the actual map marker's name,
   location and symbol/color. It must not be labelled as live PLI. Repeat in reverse.
4. Edit/resend that source marker: require one updated recipient marker. Test
   another channel member separately for recipient filtering; two phones alone
   do not prove a third peer's behavior. JVM tests cover the filtering decision.
5. With no receiving plugin, send once and require UNCONFIRMED after 60 seconds,
   never a claim of proven loss. Resume reception and explicitly resend if needed.
   Do not use repeated automatic retries. Long names/unsupported selections must
   fail visibly. PLI traffic must not overwrite the last-point indicator.
6. Reload/restart and reconnect separately. Contacts are rebuilt from fresh PLI;
   auto sending remains Off. Received points and last-point status are currently
   session-only. Remove test source markers after acceptance; never log coordinates.

Core tests exercise packet bounds/UTF-8, recipient and receipt filtering, duplicate
re-ack, revision ordering, timeout/late receipt, failed imports and history bounds.
RadioSession tests cover rebuilding channel choices on a connected snapshot change
and rejecting old worker callbacks after session reset. PLI pending tokens are now
registered before submitting to the worker, preventing early receipt overwrite.
These tests do not substitute for host UI, RF, BLE or production-host acceptance.

## Output locations

- app/build/outputs/apk/debug/app-debug.apk
- app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
- core/build/reports/tests/test/index.html
- app/build/reports/lint-results-debug.html

Outputs are disposable and ignored. Build directories are regenerated; never
check in screenshots, logs, APKs, local.properties, debug keys, or session notes.
Use `./gradlew.bat clean` when removing stale build outputs.
Python tests: `python -m unittest discover -s tools -p 'test_*.py'`.

## Fixed compatibility

The Gradle files and wrapper own compiler/dependency versions. Do not accept
Android Studio upgrade suggestions or run dependency-update bots. Gradle wrapper
checks its distribution SHA-256; dependency lockfiles and
gradle/verification-metadata.xml pin resolved dependencies and artifact hashes.

Intentional upgrades require a reviewed compatibility change, both protocol
snapshots compared, both repositories' checks, and two-phone integration.
Do not run --write-locks or --write-verification-metadata in routine builds.
Initial verification hashes are trust-on-first-use from the configured HTTPS
repositories, not an independent upstream signature audit.

## IDE

`./tools/open-studio.ps1` configures this repository and opens Android Studio.
Use its embedded JBR for the IDE and the pinned Microsoft JDK 17 for Gradle.
Product source lives here. Agent onboarding and workstation notes live in the
umbrella workspace, with links into these human-maintained documents.

## Git

Work from current origin/develop on feature/<name>. Validate and push coherent
commits, then merge through develop. main is reserved for an authorized release.
CI validates main, develop and feature branches. Source publication does not publish
a production-signed APK or redistribute the ATAK SDK.
