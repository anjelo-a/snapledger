# SnapLedger Phase Reports

Last updated: May 1, 2026

## How to use this document
- Keep one section per phase.
- Use this as the source of truth for what was actually delivered (not just planned).
- For each phase, capture evidence (tests/migrations/contracts) and what is intentionally deferred.

## Phase 0 Report (Planning/Foundation)

### Goals
- Lock scope, architecture boundaries, API contracts, and quality gates.
- Establish backend foundations: schemas, migrations, CI, and test scaffolding.

### Delivered
- FastAPI app skeleton and versioned router structure under `/v1`.
- Pydantic schemas with strict validation defaults (`extra="forbid"`).
- SQLAlchemy models for core entities (`categories`, `expenses`, `expense_items`, `budgets`, `insights`).
- Alembic setup and foundational schema migration:
  - `0001_phase0_schema`
- Default category seeds via migration.
- CI workflow for backend lint/tests/migration smoke/dependency audit.
- Baseline backend tests for health and API contract surface.

### Evidence
- Migration tests pass for core tables, indexes, and seeded categories.
- OpenAPI contract path tests present and passing.
- Backend CI pipeline configured in GitHub Actions.

### Deferred intentionally
- Real receipts/category mutation behavior.
- Sync implementation.
- Insight generation integration.

### Risks / Notes
- Phase 0 intentionally focused on contract-first scaffolding; most business endpoints were stubs at this stage.

---

## Phase 1 Backend Report (Implemented)

### Goals
- Implement backend behavior for manual-entry-adjacent flows and category management while preserving deterministic finance logic.
- Replace contract stubs with real service/repository-backed behavior.

### Delivered

#### API behavior
- Receipts:
  - `POST /v1/receipts` implemented (receipt + optional items in one transaction)
  - `GET /v1/receipts/{id}` implemented (active-only read, 404 when missing/deleted)
  - `GET /v1/receipts` implemented (filters + forward cursor pagination)
  - `PATCH /v1/receipts/{id}` implemented (partial updates; `items` means full replacement)
  - `DELETE /v1/receipts/{id}` implemented (soft delete)
- Manual entries alias:
  - `POST /v1/manual-entries` changed from hint endpoint to real create proxy
  - Enforces `source=manual`
  - Returns canonical receipt payload
- Categories:
  - `POST /v1/categories` implemented (custom create)
  - `PATCH /v1/categories/{id}` implemented (custom rename/archive)
  - Default categories protected from rename/archive
  - Case-insensitive trimmed uniqueness enforced

#### Layering and architecture
- Repository implementations added for receipts and category mutation lookups/writes.
- Service implementations added for receipts and categories with transaction/rollback handling.
- Router handlers kept thin and mapped to service/domain outcomes.

#### Error handling and contracts
- Shared domain errors introduced:
  - `NotFoundError`, `ConflictError`, `InvalidOperationError`, `ServiceUnavailableError`
- Centralized domain-error to HTTP mapping.
- Global error envelope handlers for:
  - HTTP exceptions
  - validation exceptions
  - unhandled exceptions

#### Data integrity
- Added migration:
  - `0002_category_normalized_name_unique`
- Adds DB-level uniqueness index for normalized active category names (`lower(trim(name))` with `deleted_at IS NULL`) to reduce race-condition risk.

#### Security/ops controls (env-toggle)
- API key middleware (`REQUIRE_API_KEY`, `API_KEY`)
- CORS allowlist (`CORS_ALLOWED_ORIGINS`)
- In-memory rate limiting (`RATE_LIMIT_ENABLED`, `RATE_LIMIT_REQUESTS`, `RATE_LIMIT_WINDOW_SECONDS`)
- HTTPS enforcement (`ENFORCE_HTTPS`)

### Test and quality summary
- Added/expanded tests for:
  - Receipts CRUD + validation + filter/pagination behavior
  - Cursor traversal correctness and boundary behavior
  - Manual entries alias behavior and canonical response parity
  - Category create/rename/archive + default protection + normalized uniqueness
  - Repository semantics (soft-delete scoping, item replacement integrity)
  - Service-level domain error and rollback behavior
  - Error envelope and security middleware behavior
- Current status at implementation handoff:
  - `ruff check app tests`: passing
  - `pytest -q`: passing (`35 passed`)

### Deferred intentionally (next phases)
- Phase 2: OCR parser hardening and scan pipeline refinements.
- Phase 3: budgets write flows and dashboard aggregate behavior.
- Phase 4: sync push/pull implementation and conflict policy rollout.
- Phase 5: insight generation integration.

### Risks / Notes
- Security middleware is env-toggle based; production posture depends on deployment configuration.
- Performance hardening (benchmark gates/load profiling) remains a follow-up track, not a Phase 1 functional blocker.

---

## Phase 2 Report (Implemented)

### Goals
- Deliver scan -> OCR -> deterministic parse -> structured review -> local-first save.
- Keep backend parser fallback optional.
- Preserve deterministic-only receipt parsing with no LLM/AI parsing.

### Delivered
- Android scan/review feature structure exists in the active `:app` module.
- CameraX capture flow includes permission handling, preview, capture, retry, and failure states.
- ML Kit OCR extraction returns normalized OCR lines plus capture metadata.
- Android deterministic parser is aligned with backend missing-total behavior.
- Structured Compose review/edit screen supports merchant, date, total, and items.
- Save gate requires merchant, expense date, and total amount.
- Local-first receipt persistence writes the reviewed receipt locally and queues sync metadata
  separately.
- Backend `POST /v1/receipts/process` implements deterministic fallback parsing with strict
  validation and structured candidate response.
- Validation exception serialization converts Pydantic/FastAPI validation `ctx.error` exception
  objects into JSON-safe strings.
- Android instrumentation smoke test uses mocked OCR input to exercise parse -> review -> save.

### Evidence
- Android verification passed:
  - `./android/gradlew -p android :app:assembleDebug`
  - `./android/gradlew -p android :app:assembleDebugAndroidTest`
  - `./android/gradlew -p android :app:testDebugUnitTest --tests com.snapledger.feature.scan.parser.DeterministicReceiptParserServiceTest`
- Backend parser fixture tests exist for:
  - clean receipt
  - noisy receipt
  - missing total
  - standalone amount without total
  - ambiguous merchant
  - multiline total
- API validation tests cover malformed, blank, unknown-field, overlong-line, and
  overlong-total-text OCR payloads.
- `./android/gradlew -p android :app:connectedDebugAndroidTest` could not execute locally because no connected
  device/emulator was available.

### Deferred intentionally
- Real CameraX instrumentation on a device farm or connected emulator.
- OCR performance benchmark measurements from `docs/PERFORMANCE.md`.
- Final sync worker implementation and backend sync protocol rollout.
- Receipt versioning, merchant alias systems, category/budget logic integration, and AI insights.

### Risks / Notes
- Phase 2 is implementation-complete, but device execution of the instrumentation smoke test still
  needs a connected emulator/device.
- Backend parser fallback remains optional; Android local save is the primary success path.
- Parser output is always reviewable/editable and never auto-persisted.
- No LLM, prompt, AI endpoint, or generative parsing path is introduced.

---

## Phase 3 Report (Completed: Backend Scope)

Status: Completed on April 30, 2026 for backend scope (`/v1/budgets`, `/v1/dashboard`).

### Goals
- Implement deterministic backend budgets and dashboard behavior.
- Replace Phase 0/1 stubs for `GET/POST /v1/budgets` and `GET /v1/dashboard`.

### Delivered
- Budgets:
  - Repository-level active list and key-based upsert logic implemented.
  - Service-level scope/category coherence rules implemented:
    - `scope=overall` requires `category_id=null`
    - `scope=category` requires active, non-archived category
  - Router now uses service/domain flow and no longer returns Phase 0 `501` stub.
- Dashboard:
  - Deterministic aggregates implemented for:
    - `budget_statuses`
    - `trends`
    - `category_breakdown`
    - `recent_activity`
  - Threshold levels are deterministic and locked to `normal|warning|critical|exceeded` based on 70/90/100 ratio boundaries.
  - Soft-deleted expenses are excluded from aggregates.

### Evidence
- Backend test suite includes dedicated Phase 3 API regression coverage:
  - budget create/list/upsert semantics
  - scope/category validation behavior
  - threshold boundary transitions
  - dashboard aggregate shape and soft-delete exclusion
- Quality checks passing at Sprint B handoff:
  - `backend/.venv/bin/ruff check app tests`
  - `backend/.venv/bin/pytest -q`

### Deferred intentionally
- Phase 4 sync push/pull conflict policy and reliability hardening.
- Phase 5 insight generation integration and fallback handling.

### Risks / Notes
- Dashboard trend windows are deterministic and month-based in current implementation.
- Additional performance profiling for larger datasets remains part of later hardening work.

---

## Phase 4 Report (Implemented)

### Goals
- Deliver receipts-first offline sync hardening without breaking Android local-first save.
- Implement deterministic backend push/pull sync contracts for `expense` mutations only.
- Add durable Android queueing, WorkManager-based dispatch, retry/backoff, and safe remote
  apply behavior.
- Keep categories/budgets out of Phase 4 mutation dispatch and avoid event-sourcing, message
  brokers, or AI parsing.

### Delivered
- Backend `POST /v1/sync/push` implemented for receipts/expenses only.
- Backend `GET /v1/sync/pull` implemented for receipts/expenses only.
- Backend sync supports:
  - `entity="expense"`
  - push operations `create | update | delete`
  - pull operations `upsert | delete`
  - per-mutation rejection for unsupported `budget` and `category` with
    `unsupported_entity_phase4`
  - duplicate `idempotency_key` replay using stored mutation results
  - soft-delete tombstones in pull responses
  - opaque cursor pagination using base64-encoded cursor state
- Backend sync mutation log added via Alembic migration `0003_sync_mutation_log`, keyed by
  `idempotency_key`.
- Android network sync layer added with:
  - `android.permission.INTERNET`
  - Retrofit/Moshi receipts-only sync client
  - debug backend URL defaulting to `http://10.0.2.2:8000/`
- Android durable receipt sync queue added in Room with:
  - idempotency key
  - receipt id
  - operation
  - payload snapshot
  - status
  - attempt count
  - last error
  - queued time
  - next retry time
- Android Room migrations added for:
  - queue durability schema
  - sync cursor state persistence
- Review save path remains local-first and is now atomic for:
  - local receipt write
  - sync queue enqueue
- WorkManager push worker implemented:
  - schedules one-time work after local save
  - pushes bounded due mutations
  - marks accepted records as synced
  - records retryable failures with backoff metadata
  - performs pull after successful push
- Android pull/apply behavior implemented:
  - applies remote receipt upserts when no pending local mutation exists
  - skips remote changes for receipts with pending local mutations
  - applies safe tombstone deletes
  - persists pull cursor
- Terminal failure handling added:
  - validation errors are terminal
  - malformed payloads are terminal
  - unsupported entities are terminal
  - network/server failures remain retryable
- Legacy queue compatibility hardened:
  - incomplete migrated payload snapshots do not crash sync
  - invalid legacy rows are marked terminal with clear debug errors
  - invalid rows do not retry forever

### Evidence
- Android verification currently passed:
  - `./android/gradlew -p android -g /Users/aa./snapledger/.gradle-local :app:testDebugUnitTest --tests com.snapledger.core.sync.ReceiptSyncMapperTest --tests com.snapledger.core.sync.ReceiptSyncPushProcessorTest --tests com.snapledger.core.sync.ReceiptSyncPullProcessorTest --tests com.snapledger.feature.review.domain.LocalFirstReviewRepositoryTest`
- Android instrumentation/migration evidence present in repo:
  - Room migration tests for queue schema and sync cursor state
  - receipt flow smoke test still exists from Phase 2
- Backend evidence present in repo:
  - migration tests verify `sync_mutation_log` table, indexes, and `idempotency_key` primary key
  - API tests cover:
    - push create/update/delete
    - duplicate idempotency replay
    - unsupported entity rejection
    - invalid payload `4xx`
    - pull upserts
    - pull tombstones
    - deterministic cursor pagination
    - invalid cursor `4xx`
- backend pytest could not be executed in the current local environment because `pytest` is not
  installed.
- Connected Android instrumentation execution remains unverified locally unless a
  device/emulator is attached.

### Deferred intentionally
- Category and budget sync mutations on Android and backend.
- Conflict resolution UI beyond skip-local-pending behavior.
- Cross-entity sync policies outside receipts/expenses.
- Device-farm or connected-emulator execution of Android instrumentation sync coverage.
- Phase 5 insight generation and any AI-assisted behavior.
- Event-sourced sync architecture, message brokers, and non-deterministic merge logic.

### Risks / Notes
- Phase 4 is implemented as a receipts-first slice, not full cross-entity sync.
- Android local save remains the source-of-truth path and does not depend on backend
  availability.
- Sync failures remain visible via persisted queue state and `lastError`.
- Unsupported entities are intentionally rejected rather than silently ignored.
- No AI, prompt-based parsing, or generative merge behavior is introduced in Phase 4.
- Local connected instrumentation for sync behavior is still an environment-dependent
  verification gap if no emulator/device is attached.

---

## Phase 5 Report (Template)

### Goals
- _TBD_

### Delivered
- _TBD_

### Evidence
- _TBD_

### Deferred intentionally
- _TBD_

### Risks / Notes
- _TBD_
