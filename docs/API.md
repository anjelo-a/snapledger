# SnapLedger API

## API design rules
- Keep API small and directly tied to owned features.
- Deterministic finance logic remains deterministic server-side.
- No AI endpoint is allowed to mutate core finance records.
- Do not expose internal debug or prompt internals.

## MVP endpoints

### Receipts
`POST /v1/receipts`
- Purpose: create scanned or manual-confirmed expense.
- Request summary: source, merchant, date, total, category_id, optional items.
- Response summary: canonical receipt with item list and timestamps.
- Scope: MVP.

`GET /v1/receipts/{id}`
- Purpose: fetch one receipt detail.
- Response summary: receipt + items + category.
- Scope: MVP.

`GET /v1/receipts`
- Purpose: history listing with filtering and pagination.
- Request summary: date range, merchant query, category, amount min/max, cursor, limit.
- Response summary: paged receipt summaries + next cursor.
- Scope: MVP.

`PATCH /v1/receipts/{id}`
- Purpose: edit receipt and optional item list replacement.
- Scope: MVP.

`DELETE /v1/receipts/{id}`
- Purpose: soft-delete receipt for sync-safe behavior.
- Scope: MVP.

`POST /v1/manual-entries`
- Purpose: explicit manual entry endpoint alias.
- Scope: MVP.

### OCR processing
`POST /v1/receipts/process`
- Purpose: optional server-side deterministic normalization of OCR text lines.
- Request summary: OCR lines, locale/currency hints, timestamp.
- Response summary: candidate merchant/date/total/items and parse warnings.
- Scope: Phase 2+ optional fallback.

### Categories
`GET /v1/categories`
- Purpose: list default and custom categories.
- Scope: MVP.

`POST /v1/categories`
- Purpose: create custom category.
- Scope: MVP.

`PATCH /v1/categories/{id}`
- Purpose: rename/archive custom category.
- Scope: MVP.

### Budgets
`GET /v1/budgets`
- Purpose: list budgets by scope/period.
- Scope: MVP.

`POST /v1/budgets`
- Purpose: create or update budget.
- Request summary: scope, category_id nullable, period, amount_limit.
- Scope: MVP.

### Dashboard
`GET /v1/dashboard`
- Purpose: return budget status, trends, category breakdown, recent activity.
- Scope: MVP.

### Insight
`POST /v1/insights/generate`
- Purpose: generate one polished insight text.
- Request summary: period and optional focus category.
- Response summary: insight text + metrics echo + timestamp.
- Scope: Phase 5.

### Sync
`POST /v1/sync/push`
- Purpose: upload local mutation batch.
- Scope: Phase 4.

`GET /v1/sync/pull`
- Purpose: fetch remote deltas by cursor.
- Scope: Phase 4.

## Not exposed yet
- Event-stream endpoints.
- Receipt version history endpoints.
- Raw Gemini prompts/responses.
- Admin or internal diagnostics endpoints.
- Bulk destructive endpoints.
