# SnapLedger Backend

FastAPI + PostgreSQL backend service.

## Status (Phase 1 Backend Complete)

Implemented:
- API routes under `/v1` for receipts and categories (CRUD/mutation scope for Phase 1).
- Manual entries alias as a real create proxy via `POST /v1/manual-entries`.
- Cursor-based receipt listing with stable sort and opaque `next_cursor`.
- Soft-delete behavior for receipts and receipt items.
- Category mutation rules:
  - default categories immutable for rename/archive
  - case-insensitive, trimmed uniqueness for active categories
- Pydantic strict validation defaults (`extra="forbid"`).
- Service/repository layering with standardized domain error to HTTP mapping.
- Global error envelope handlers.
- Optional exposure hardening toggles:
  - API key gate
  - CORS allowlist
  - in-memory rate limiting
  - HTTPS enforcement
- Alembic migrations:
  - `0001_phase0_schema`
  - `0002_category_normalized_name_unique`
- Test suite coverage for API contracts, validation, receipts/categories behavior, repository semantics, service errors, and security middleware.

Deferred intentionally to later phases:
- Budget write flows and dashboard aggregates (Phase 3).
- Sync workflows (Phase 4).
- AI insight generation integration (Phase 5).

## Run locally

```bash
cd backend
python -m uvicorn app.main:app --reload
```

## Run tests

```bash
cd backend
pytest -q
```

## Run migrations

```bash
cd backend
alembic upgrade head
```
