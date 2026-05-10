# SnapLedger Backend

FastAPI + PostgreSQL backend service.

## Status (Phase 1 Backend Complete)

Implemented:
- API routes under `/v1` for receipts and categories (CRUD/mutation scope for Phase 1).
- Manual entries alias as a real create proxy via `POST /v1/manual-entries`.
- Reviewed scan confirmation alias via `POST /v1/receipts/confirm`.
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

## Run receipt extraction eval

```bash
cd backend
.venv/bin/python scripts/run_receipt_eval.py --mode eval --dataset evals/receipt_canary.jsonl --output-dir eval_artifacts
.venv/bin/python scripts/run_receipt_eval.py --mode eval --dataset evals/receipt_full.jsonl --output-dir eval_artifacts
.venv/bin/python scripts/run_receipt_eval.py --mode perf --dataset evals/receipt_full.jsonl --repeats 3 --output-dir eval_artifacts
```

## Curate real scan samples into eval datasets

```bash
cd backend
.venv/bin/python scripts/export_local_receipts_to_incoming.py --sqlite-db /path/to/review_local.db --output evals/incoming/new_scans.jsonl
.venv/bin/python scripts/manage_receipt_eval_staging.py ingest --input evals/incoming/new_scans.jsonl
.venv/bin/python scripts/manage_receipt_eval_staging.py stats
.venv/bin/python scripts/manage_receipt_eval_staging.py approve --all-clean
.venv/bin/python scripts/manage_receipt_eval_staging.py promote --target evals/receipt_full.jsonl --prefix real --limit 10
```

Notes:
- Export reads `local_receipts` + `local_receipt_items` from Android Room DB and reconstructs `ocr_lines`.
- Raw OCR text is not persisted by the app today, so exported rows are review-derived reconstruction.

## Run migrations

```bash
cd backend
alembic upgrade head
```
