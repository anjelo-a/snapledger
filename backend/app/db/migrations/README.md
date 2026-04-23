# Database migrations

This folder contains Alembic migrations for SnapLedger backend.

## Phase 0 baseline

- `0001_phase0_schema` creates core backend tables.
- Seeds default categories used by `GET /v1/categories`.
- Adds foundation indexes for common filtering paths.

## Commands

Run from `backend/`:

```bash
alembic upgrade head
alembic downgrade -1
```
