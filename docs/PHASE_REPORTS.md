# SnapLedger Phase Reports

Last updated: April 30, 2026

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

## Phase 2 Report (Template)

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

## Phase 4 Report (Template)

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
