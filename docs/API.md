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
- Response summary: paged receipt summaries + next cursor (`next_cursor=null` when exhausted).
- Scope: MVP.

`PATCH /v1/receipts/{id}`
- Purpose: edit receipt and optional item list replacement.
- Scope: MVP.

`DELETE /v1/receipts/{id}`
- Purpose: soft-delete receipt for sync-safe behavior.
- Scope: MVP.

`POST /v1/manual-entries`
- Purpose: explicit manual entry endpoint alias; performs create and returns canonical receipt payload.
- Behavior: enforces `source=manual` server-side.
- Scope: MVP.

### OCR processing
`POST /v1/receipts/process`
- Purpose: optional server-side deterministic parsing fallback for OCR text lines in the scan review flow.
- Request contract:
  - `ocr_lines: string[]` required, non-empty, max 500 lines.
  - `locale: string | null` optional.
  - `currency_hint: string | null` optional 3-letter currency hint.
- Response contract:
  - `merchant: string | null`
  - `expense_date: string(date) | null`
  - `total_amount: decimal string | null`
  - `items: {name, amount}[]`
  - `warnings: string[]`
  - `warning_codes: string[]` always present machine-readable warning metadata for review UX; `[]`
    when there are no warning codes.
  - `field_confidence: {merchant?, expense_date?, total_amount?, items?} | null` always present
    0..1 confidence hints; `null` when unavailable.
- Contract rules:
  - Backward-compatible additions are allowed only as optional response fields.
  - No image bytes are posted to this endpoint; the contract starts after OCR as normalized text lines.
  - This endpoint never mutates saved receipts.
  - Android local save must not depend on this endpoint being available.
  - Parser behavior is deterministic and rule-based only; no LLM, prompt, AI endpoint, or
    generative cleanup path is allowed.
- Current status:
  - The route, strict request validation, deterministic parser, warnings, warning codes, and field
    confidence hints are implemented.
  - Malformed, blank, unknown-field, overlong-line, and overlong-total-text payloads return
    explicit validation errors.
  - Backend fallback remains optional; reviewed Android local save is the primary success path and
    is local-first.
- Scope: Phase 2 optional fallback.

### Categories
`GET /v1/categories`
- Purpose: list default and custom categories.
- Scope: MVP.

`POST /v1/categories`
- Purpose: create custom category.
- Scope: MVP.

`PATCH /v1/categories/{id}`
- Purpose: rename/archive custom category.
- Behavior: default categories are immutable for rename/archive.
- Validation: category names are case-insensitive trimmed unique among active categories.
- Scope: MVP.

## Implemented error/status notes (Phase 1 backend)
- `400` for invalid operation or invalid filter ranges.
- `404` for active-record not found.
- `409` for category name conflicts.
- `422` for schema validation errors.
- `503` for DB/service availability failures.

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
