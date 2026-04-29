# SnapLedger Plan

## Executive project decision
SnapLedger is built as a lean polished MVP first, then hardened in explicit later phases.

Why:
- The highest value is the quality of `manual/scan -> structured review -> save -> dashboard`.
- Offline-first correctness and UX are more important than early infrastructure complexity.
- This approach maximizes completion quality and portfolio signal.

## Scope split

### Must-have MVP
- Android-native app with Kotlin, Compose, MVVM.
- Manual expense entry.
- Single receipt scan with CameraX + ML Kit OCR.
- Immediate structured review after OCR.
- Editable fields: merchant, date, total, item names, item amounts, category.
- Save with merchant+date+total even with partial item extraction.
- Default categories + custom categories.
- Budgets: overall/category, weekly/monthly, thresholds at 70/90/100.
- Dashboard priority: budget status, trends, insights slot, categories, recent activity.
- History filters: date, merchant, category, amount range.
- Offline-first Room source of truth.
- FastAPI backend Phase 1 implemented for receipts/categories + contracts for later sync phases.

### Should-have after MVP
- WorkManager-based sync push/pull with retry and idempotency.
- Backend dashboard aggregation endpoint for cross-device consistency.
- One Gemini-powered polished insight from deterministic metrics.
- Sync tombstone propagation.

### Nice-to-have later
- Insight history persistence.
- Merchant alias normalization.
- Optional summary cache tables only when profiling proves need.
- Rescan overwrite workflow.

### Explicit non-goals
- Microservices, CQRS, Kafka, message brokers.
- Event-sourcing sync systems.
- LLM for parsing or budgets.
- Forecasting/anomaly detection before core reliability.
- Plugin/provider abstraction systems before concrete need.

## Assumptions locked
- MVP backend demos are local-only by default.
- Android data-at-rest baseline is device encryption + app sandboxing.
- No near-term public release during MVP.
- If private staging/public testers are introduced, auth/TLS/rate-limit/CORS controls move earlier.

## Phase plan

### Phase 0: planning/foundation
Objective:
- Lock scope, architecture, schema, contracts, and quality gates.

Deliverables:
- Architecture docs, API contracts, Room schema v1, category seeds, CI/lint/test scaffolding.

Acceptance criteria:
- Android and backend skeletons run.
- Docs approved and phase boundaries frozen.

Must not start:
- AI features, advanced analytics, sync complexity.

### Phase 1: first vertical slice
Objective:
- Manual entry -> save -> history entirely local.

Deliverables:
- Manual entry UI, validation, Room persistence, recent activity, basic filters.
- Backend Phase 1: receipts CRUD, manual-entries create proxy, category create/patch rules, cursor pagination, and test coverage.

Acceptance criteria:
- Offline create/edit/delete works end-to-end.
- Backend receipts/categories endpoints behave per API contracts and pass CI checks.

Must not start:
- OCR pipeline complexity and advanced backend features.

### Phase 2: scan and OCR pipeline
Objective:
- Production-ready scan -> structured review -> save flow.

Deliverables:
- CameraX capture, ML Kit extraction, deterministic parser, editable structured review.
- Lock parser contract as `ocr_lines + locale + currency_hint -> candidate fields + warnings`.
- Keep backend fallback optional so user-confirmed local save remains the primary success path.

Acceptance criteria:
- Save succeeds when merchant/date/total present even if items incomplete.
- Review always happens before save; parser output is never auto-persisted.
- Reviewed receipts are persisted locally first, with sync metadata queued separately.
- Backend parser fallback is optional and must not block a valid local save.
- No LLM parsing is introduced for receipts, including fallback behavior.

Must not start:
- Receipt version management and alias systems.

### Phase 3: dashboard and budgets
Objective:
- Deliver value from saved data.

Deliverables:
- Budget CRUD, threshold alerts, trends, categories, prioritized dashboard layout.

Acceptance criteria:
- Dashboard uses real saved data and budget math is correct.

Must not start:
- Forecasting, anomaly detection, analytics engine.

### Phase 4: offline/sync hardening
Objective:
- Robust bidirectional sync without breaking local-first behavior.

Deliverables:
- Sync queue, WorkManager workers, retry/backoff, idempotency, conflict policy.

Acceptance criteria:
- Offline mutations reconcile correctly on reconnect.
- Sync failures do not block local save.

Must not start:
- Event-driven or event-sourced sync architectures.

### Phase 5: AI insight
Objective:
- Add one polished insight generated from deterministic aggregates.

Deliverables:
- Backend Gemini integration, prompt template, fallback path, usage limits.

Acceptance criteria:
- Insight generation never affects deterministic finance logic.
- Client has no Gemini key exposure.

Must not start:
- AI categorization, AI budgeting, multi-insight feed systems.

### Phase 6+: advanced features by evidence
Objective:
- Introduce complexity only when measurable need exists.

Candidates:
- Merchant alias normalization.
- Optional summary cache tables.
- Controlled rescan overwrite flows.

Acceptance criteria:
- Feature has owner, test plan, measurable user value, and rollback plan.

## Quality gates
- Phase 1 gate: manual entry works end-to-end offline.
- Phase 2 gate: on-device scan/review/save works reliably.
- Phase 3 gate: dashboard and budgets reflect real data.
- Phase 4 gate: offline create/edit/delete syncs correctly.
- Phase 5 gate: Gemini insight added only after base numbers are proven correct.

Backend progress note (April 28, 2026):
- Phase 1 backend scope is complete.
- Phase 2 backend fallback parser is implemented as deterministic rule-based parsing only.
- Remaining backend work is Phase 3 (budgets/dashboard), Phase 4 (sync), and Phase 5 (insight).

Phase 2 contract lock (April 29, 2026):
- `ReceiptProcessRequest` is already present with `ocr_lines`, optional `locale`, and optional `currency_hint`.
- `ParsedReceiptCandidate` already carries `merchant`, `expense_date`, `total_amount`, `items`, and `warnings`.
- Optional backward-compatible metadata fields are allowed for review UX only; current contract adds
  `warning_codes` and `field_confidence`.
- No schema change in this contract may require Android or backend callers to send new required fields.

Phase 2 implementation note (April 30, 2026):
- Android scan/review/save is local-first: a valid reviewed receipt saves locally even when
  backend fallback is unavailable.
- The backend fallback parser remains optional and deterministic-only.
- Sync metadata is queued separately from the local receipt record.

## Implementation order
1. Lock docs and scope boundaries.
2. Define domain model and Room schema v1.
3. Build Android architecture shell.
4. Implement manual entry + validation.
5. Persist and list history from Room.
6. Implement history filters.
7. Build structured review editor.
8. Add CameraX capture.
9. Add ML Kit OCR extraction.
10. Add deterministic OCR parser.
11. Finalize scan/review/save flow.
12. Add budgets and thresholds.
13. Build dashboard aggregates and UI.
14. Scaffold backend models/schemas/migrations.
15. Implement receipts/categories/budgets/dashboard APIs.
16. Implement sync queue and workers.
17. Harden sync reliability and conflict policy.
18. Add backend Gemini insight service.
19. Integrate insight card UI.
20. Run performance and security hardening.
