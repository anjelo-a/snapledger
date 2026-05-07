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
- Purpose: optional server-side receipt extraction for the scan review flow using backend Gemini
  vision with strict structured output validation.
- Request contract:
  - `image_base64: string` required for image-based extraction.
  - `image_mime_type: string` required (for example `image/jpeg` or `image/png`).
  - `ocr_lines: string[]` optional backward-compatibility path.
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
  - Image payload must be validated for size and type before model invocation.
  - This endpoint never mutates saved receipts.
  - Android local save must not depend on this endpoint being available.
  - Non-fabrication is mandatory: uncertain merchant/date/total/item amount fields return `null`
    and include warning codes.
  - Model output must be constrained to structured fields and pass deterministic
    schema/business validation before response.
- Current status:
  - The route, strict request validation, warnings, warning codes, and field confidence hints are implemented.
  - Malformed, blank, unknown-field, overlong-line, and overlong-total-text payloads return
    explicit validation errors.
  - Backend processing remains optional; reviewed Android local save is the primary success path and
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
- Current behavior:
  - Returns active budgets with deterministic stable ordering.
- Scope: MVP.

`POST /v1/budgets`
- Purpose: create or update budget.
- Request summary: scope, category_id nullable, period, amount_limit.
- Current behavior:
  - Upsert by `(scope, period, category_id)` among active budgets.
  - Validation rules:
    - `scope=overall` requires `category_id=null`
    - `scope=category` requires active non-archived category
- Scope: MVP.

### Dashboard
`GET /v1/dashboard`
- Purpose: return budget status, trends, category breakdown, recent activity.
- Current behavior:
  - `budget_statuses` uses deterministic spent/limit/ratio with threshold levels:
    `normal`, `warning`, `critical`, `exceeded`.
  - `trends` returns deterministic month buckets.
  - Soft-deleted expenses are excluded from all aggregates.
- Scope: MVP.

### Insight
`POST /v1/insights/generate`
- Purpose: generate one polished insight text.
- Request summary: period and optional focus category.
- Response summary: insight text + metrics echo + timestamp.
- Scope: Phase 5.

### Sync
`POST /v1/sync/push`
- Purpose: upload a local mutation batch without blocking local-first Android saves.
- Scope: Phase 4 receipts-first contract.
- Request contract:
  - `mutations: SyncMutation[]` required, 1..200 entries.
  - `idempotency_key: string` required per mutation, 8..128 chars.
  - `entity: "expense" | "budget" | "category"`.
  - `operation: "create" | "update" | "delete"`.
  - `payload: object` carries the mutation body.
  - `occurred_at: string(date-time)` records the local mutation time.
- Phase 4 entity support:
  - Only `entity="expense"` is supported in this receipts-first slice.
  - `budget` and `category` mutations are syntactically valid but must be rejected per mutation
    with `code="unsupported_entity_phase4"`.
- Response contract:
  - `accepted: number`.
  - `rejected: number`.
  - `results: {idempotency_key, entity, operation, status, code?, message?, entity_id?}[]`.
- Contract rules:
  - Duplicate idempotency keys must not create duplicate remote records once sync logic is added.
  - Sync failures must never roll back or block a valid reviewed receipt save on Android.
  - No event sourcing, message brokers, AI parsing, or Phase 5 insight behavior belongs here.

`GET /v1/sync/pull`
- Purpose: fetch remote receipt deltas by opaque cursor.
- Scope: Phase 4 receipts-first contract.
- Request contract:
  - `cursor: string` optional query value; clients must treat it as opaque.
  - Cursor encoding is base64 JSON containing `updated_at` and `id`.
- Response contract:
  - `cursor: string`.
  - `has_more: boolean`.
  - `changes: {entity, operation, id, updated_at, payload?}[]`.
- Change rules:
  - `entity` is always `"expense"` for this Phase 4 slice.
  - `operation` is `"upsert"` or `"delete"`.
  - `payload` is present for `upsert` and may be omitted/null for `delete`.
- Contract rules:
  - Android applies remote receipt changes only when doing so does not override pending local
    receipt mutations.
  - Pull is deterministic cursor pagination, not an event stream.

## Not exposed yet
- Event-stream endpoints.
- Receipt version history endpoints.
- Raw Gemini prompts/responses.
- Admin or internal diagnostics endpoints.
- Bulk destructive endpoints.
