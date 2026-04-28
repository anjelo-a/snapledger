# SnapLedger Architecture

## System shape
- Android app is local-first and remains usable with no network.
- Backend is a single FastAPI service with PostgreSQL.
- Gemini is backend-only and used only for one narrative insight.

## Android frontend architecture
Responsibilities:
- Capture receipt image and OCR text.
- Provide structured review/edit experience.
- Persist all user actions locally first.
- Render dashboard/history from local data.

Boundaries:
- Compose renders state only.
- ViewModels orchestrate intents and state.
- Repositories encapsulate data source access.
- Domain logic lives outside Composables.

Must not couple:
- Business rules in Compose UI.
- Direct Gemini calls from Android.
- OCR pipeline writing directly to UI-only models without repository/domain boundary.

## Backend architecture
Responsibilities:
- Canonical cloud schema and CRUD.
- Deterministic parsing helpers (optional server fallback).
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

Current backend implementation notes:
- Receipts and category mutation endpoints are implemented through service/repository layers.
- Receipts list uses opaque cursor pagination with stable ordering.
- Global error envelope handlers are registered for HTTP/validation/unhandled exceptions.
- Optional security middleware gates are available via env config: API key, CORS allowlist, in-memory rate limiting, HTTPS enforcement.

## Data/storage architecture
Local:
- Room is source of truth for UX.
- Local writes always happen immediately.
- Local sync queue tracks pending mutations.

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

Conflict policy (initial):
- Local-first for unsynced local edits.
- Server updates applied when no pending local mutation exists.
- Keep policy explicit and test-covered.

Must not couple:
- Core CRUD flow depending on sync completion.
- Sync errors blocking local save.

## AI architecture
- InsightService on backend receives deterministic metrics.
- Prompt is template-based and minimal.
- Response is one polished insight text + optional short action tip.
- Insight failures return fallback response; never block dashboard.

Must not couple:
- AI to parsing, totals, budget thresholds, or category math.

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
