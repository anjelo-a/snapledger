# SnapLedger Testing Strategy

## Testing goals
- Prioritize confidence in money-critical correctness and local-first reliability.
- Avoid test theater and brittle snapshot-heavy strategies.

## Android tests

### UI instrumentation tests
- Manual entry hero flow end-to-end.
- Scan -> review -> save flow on emulator/device.
- Budget creation/edit and threshold display.
- History filtering by date/merchant/category/amount.

### ViewModel tests
- State transitions for loading/success/error.
- Input validation rules.
- Retry and error-handling behavior.
- One-shot effect behavior (navigation/snackbar events).

### Repository/data tests
- Local-first write behavior.
- Room + fake remote integration paths.
- Sync queue enqueue behavior on mutations.

### Room tests
- DAO query correctness.
- Migration tests for every schema change.
- Index-backed filter behavior under realistic seeded data.

## Backend tests

### API integration tests
- Endpoint contract validation.
- Error paths and validation failures.
- Pagination/filter behavior.
- Auth/security behavior when enabled for exposed deployments.

### Domain/service tests
- Parser rules with known receipt fixtures.
- Budget calculations for weekly/monthly windows and threshold boundaries.
- Dashboard aggregation correctness with seeded datasets.
- Sync merge and idempotency behavior.

## OCR and sync smoke tests
- OCR smoke set with representative receipt images.
- Assert that extracted fields remain editable and savable even with partial items.
- Offline create/edit/delete then reconnect sync reconciliation tests.

## AI-related tests
- Test prompt-builder inputs from deterministic metrics.
- Mock Gemini transport and failure modes.
- Validate response shape and fallback handling.
- Do not assert exact full generated text snapshots.

## CI requirements by phase
- Phase 1: unit + Room tests mandatory.
- Phase 2: add OCR flow smoke tests.
- Phase 3: add dashboard and budget aggregation suites.
- Phase 4: sync reliability suites mandatory.
- Phase 5: AI service contract/fallback suites mandatory.
