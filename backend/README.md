# SnapLedger Backend

FastAPI + PostgreSQL backend scaffold.

## Phase 0 status

Implemented in this scaffold:
- API contract-first routes under `/v1`.
- Pydantic request/response schemas with strict validation defaults.
- SQLAlchemy model skeleton for core entities.
- Migration directory scaffold in `app/db/migrations`.
- Default category seed surface via `GET /v1/categories`.
- Baseline tests for health and API contract path presence.

Deferred intentionally to later phases:
- Receipt CRUD business logic and persistence (Phase 1+).
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
pytest
```
