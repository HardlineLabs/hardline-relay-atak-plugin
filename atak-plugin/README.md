# ATAK host integration

Target API: ATAK CIV 5.6.0. The exact matching SDK and development host APK must
be obtained from TAK.gov before this module is configured and verified.
The repository's :app module is a standalone display harness, not an ATAK plugin.

Next integration steps (after the SDK is available):
1. Freeze the exact SDK archive, host APK, and SHA-256 digests in the version inventory.
2. Use the matching SDK's plugin template and takdev Gradle plugin. Do not substitute 5.5 jars.
3. Implement the host toolbar/pane lifecycle, with the tested :core StatusPresenter.
4. Bind the host's supported Context to Meshtastic 2.7.13 IMeshService for read-only status.
5. Test loading with the SDK host. Production ATAK may require plugin signing through TAK.gov.
6. Keep normal TAK server connections untouched. No automatic CoT forwarding in the setup baseline.

No placeholder APK is presented as ATAK-compatible. tools/build-atak.ps1 fails
explicitly until the matching SDK integration is ready.
