# Desktop v3 configuration persistence and executable flow validation

- Saves configuration to LOCALAPPDATA/MeterTrailTester/config.json explicitly via Save configuration and automatically before a question. Model key and MeterTrail key use Windows DPAPI with UI forbidden; ordinary configuration is JSON. Writes are atomic. Restarts restore values. Pending events remain in memory, with the existing exit warning.
- Version v3.0 is visible in the window title and distributable filename to distinguish earlier binaries.
- `build-windows.ps1` executed successfully using the isolated local build environment; includes the SSL runtime's supplemental PEM and certifi CA bundle.
- Six unit tests pass, covering TLS verification, error redaction, provider usage, unknown usage and stable retry identities.
- The actual frozen executable was run with `--integration-test`. Its Tk UI was driven against a synthetic localhost HTTP server: two independent model calls accumulate before one 4-event POST; HTTP 202 receipt is followed by a batch-result GET; manual flush of one pending call works; a 401 model error is displayed without producing events. Real DPAPI save/load and a new Tk application instance restore both keys and all settings. Plaintext keys are absent from config.json. Result: `.local/model-tester-frozen-v3.json`, passed.
- The same frozen executable's `--self-test` checks public MeterTrail and DeepSeek HTTPS endpoints without credentials or inference. This does not claim authenticated paid model inference was tested.
- Distributable `.local/MeterTrailTester-Windows-v3.0.zip`; no website/backend changes or deployment required.
