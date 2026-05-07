# SnapLedger Architecture

## System shape
- Android app is local-first and remains usable with no network.
- Backend is a single FastAPI service with PostgreSQL.
- Gemini is backend-only and used for receipt extraction plus one narrative insight.

## Android frontend architecture
Responsibilities:
- Capture receipt image and submit for extraction.
- Provide structured review/edit experience before any save.
- Persist all user actions locally first.
- Render dashboard/history from local data.

Boundaries:
- Compose renders state only.
- ViewModels orchestrate intents and state.
- Repositories encapsulate data source access.
- Domain logic lives outside Composables.
- Scan flow is `capture -> backend extraction candidate -> user review -> local save`.
- Review is the approval boundary: parser output is always editable and never saved directly.

Must not couple:
- Business rules in Compose UI.
- Direct Gemini calls from Android.
- OCR pipeline writing directly to UI-only models without repository/domain boundary.
- Backend parser availability blocking local save after user review.

## Backend architecture
Responsibilities:
- Canonical cloud schema and CRUD.
- Receipt extraction orchestration using Gemini vision with deterministic schema/business validation.
- Budget and dashboard deterministic calculations.
- Insight generation orchestration.
- Sync protocol support.

Boundaries:
- Routers: HTTP mapping + validation only.
- Services: business rules.
- Repositories: DB access only.
- Domain errors are mapped consistently to HTTP responses.

Must not couple:
- SQL in API routers.
- Budget logic with AI pipeline.
- AI outputs with deterministic finance logic.
- Receipt extraction output bypassing deterministic validation or non-fabrication gates.

Current backend implementation notes:
- Receipts and category mutation endpoints are implemented through service/repository layers.
- Receipts list uses opaque cursor pagination with stable ordering.
- Global error envelope handlers are registered for HTTP/validation/unhandled exceptions.
- Optional security middleware gates are available via env config: API key, CORS allowlist, in-memory rate limiting, HTTPS enforcement.
- `POST /v1/receipts/process` implements the Phase 2 deterministic parser fallback with strict
  image/OCR input validation and structured candidate output.
- Backend receipt processing is optional; Android review/save persists locally first and never waits
  on backend availability to complete a valid reviewed save.

## Data/storage architecture
Local:
- Room is source of truth for UX.
- Local writes always happen immediately.
- Local sync queue tracks pending mutations.
- Reviewed receipt save writes the local receipt first, then queues sync metadata separately.

Remote:
- PostgreSQL is canonical for synced state.
- Soft delete fields retained for sync safety.

Must not couple:
- Sync metadata mixed into UI domain models beyond status projection.
- Dashboard UI requiring remote availability.

## Sync/offline architecture
- WorkManager runs sync when constraints allow.
- Push local mutation queue with idempotency keys.
- Pull server deltas and merge deterministically.
- Retries with exponential backoff.
- Phase 4 starts receipts-first: only `expense` mutations are supported end-to-end.
- `budget` and `category` sync mutations are rejected per mutation with
  `unsupported_entity_phase4` until their local Android stores are ready.
- Pull cursors are opaque to clients; server cursors are base64 JSON containing `updated_at` and
  `id`.

Conflict policy (initial):
- Local-first for unsynced local edits.
- Server updates applied when no pending local mutation exists.
- Keep policy explicit and test-covered.

Must not couple:
- Core CRUD flow depending on sync completion.
- Sync errors blocking local save.
- Sync implementation depending on event sourcing, message brokers, AI parsing, or Phase 5 insight
  workflows.

## AI architecture
- InsightService on backend receives deterministic metrics.
- Prompt is template-based and minimal.
- Response is one polished insight text + optional short action tip.
- Insight failures return fallback response; never block dashboard.
- Receipt extraction can use backend Gemini vision but must output strict structured fields and
  return null on uncertainty with warning metadata.

Must not couple:
- AI to budget thresholds or category math.
- AI output directly to persistence without review and deterministic validation boundaries.

## Android module/package structure
- `app/src/main/java/com/snapledger/core/*` shared data/network/sync utilities.
- `app/src/main/java/com/snapledger/feature/*` feature-first folders.
- `feature` packages: `entry`, `scan`, `review`, `budgets`, `dashboard`, `history`, `categories`, `insight`.

## Backend module structure
- `backend/app/api/v1` routes.
- `backend/app/services` domain logic.
- `backend/app/repositories` persistence layer.
- `backend/app/models` SQLAlchemy entities.
- `backend/app/schemas` Pydantic contracts.
- `backend/app/db/migrations` Alembic migrations.
