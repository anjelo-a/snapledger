# SnapLedger Testing Strategy

## Testing goals
- Prioritize confidence in money-critical correctness and local-first reliability.
- Avoid test theater and brittle snapshot-heavy strategies.

## Android tests

### UI instrumentation tests
- Manual entry hero flow end-to-end.
- Scan -> review -> save flow on emulator/device.
- Phase 2 receipt smoke test may mock scan input and OCR lines, but must exercise deterministic
  parse, review validation, and local save.
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
- Backend or sync dispatch failure must not block a valid local reviewed receipt save.

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
- Phase 1 backend coverage now includes receipts CRUD, manual-entry alias behavior, category mutation rules, and cursor pagination regression checks.

### Domain/service tests
- Parser rules with known receipt fixtures.
- Parser output remains deterministic-only and never invokes LLM-based extraction.
- Backend `/v1/receipts/process` fallback parser remains optional, deterministic, and structured.
- Budget calculations for weekly/monthly windows and threshold boundaries.
- Dashboard aggregation correctness with seeded datasets.
- Sync merge and idempotency behavior.
- Phase 1 backend service tests include domain error mapping and rollback behavior for receipts/categories flows.

## OCR and sync smoke tests
- OCR smoke set with representative receipt images.
- Assert that extracted fields remain editable and savable even with partial items.
- Assert `/v1/receipts/process` contract stability for `merchant`, `expense_date`,
  `total_amount`, `items`, `warnings`, and optional metadata fields.
- Assert local review/save still works when backend parser fallback is unavailable.
- Assert local receipt persistence and sync metadata queueing are separate outcomes.
- Offline create/edit/delete then reconnect sync reconciliation tests.
- Phase 4 sync contract tests must cover receipts-first push/pull shapes before sync logic ships.
- Push contract tests should assert `accepted`, `rejected`, and per-mutation `results[]`.
- Unsupported `budget` and `category` mutations should produce per-mutation
  `unsupported_entity_phase4` results once sync logic is implemented.
- Pull contract tests should assert opaque cursor handling and `expense` changes with
  `upsert`/`delete` operations.
- Android sync tests must continue proving sync failure does not block valid local receipt save.

## AI-related tests
- Test prompt-builder inputs from deterministic metrics.
- Mock Gemini transport and failure modes.
- Validate response shape and fallback handling.
- Do not assert exact full generated text snapshots.

## CI requirements by phase
- Phase 1: unit + Room tests mandatory.
- Phase 2: add OCR flow smoke tests plus parser contract regression tests.
- Phase 3: add dashboard and budget aggregation suites.
- Phase 4: sync reliability suites mandatory.
- Phase 5: AI service contract/fallback suites mandatory.
