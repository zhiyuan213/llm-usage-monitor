# Desktop EXE HTTPS resource repair

- Reproduced the user's FileNotFoundError inside the packaged executable, before any HTTP connection. The build Python distribution's ssl.SSLContext initializer loads a supplemental PEM adjacent to ssl.py. PyInstaller did not collect that resource automatically.
- Packaged the supplemental PEM with the frozen module, preserved TLS certificate and hostname verification, and bundled a pinned certifi public CA bundle. No runtime or security verification was disabled. Added a reproducible Windows packaging script which collects supplemental SSL PEM resources.
- Added safe HTTP status diagnostics and an unauthenticated --self-test mode. Test URLs are fixed public endpoints; no model prompts, keys or payment calls are sent.
- `python -m unittest discover -s tools/model-tester -v`: 6 tests pass, including certificate validation configuration and HTTP error redaction.
- Frozen `MeterTrailTester.exe --self-test ...`: MeterTrail /api/me and DeepSeek /models both respond HTTP 401 as expected; no FileNotFoundError. Evidence: `.local/model-tester-fixed-network-v2.json`.
- Packaged Tk window opens and responds normally. Deliverable: `.local/MeterTrailTester-Windows-v2.zip`. No genuine authenticated model inference tested because user credentials are not available to the test.
