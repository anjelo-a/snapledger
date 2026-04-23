# SnapLedger Skills Design (OpenAI-Aligned)

This file defines the reusable skill catalog for SnapLedger agents and coding workflows.
It is based on OpenAI Skills guidance and adapted to this repo's architecture and phase plan.

Status date: April 23, 2026.

## 1) Skill Standard for This Repo

Each skill must be narrow and explicit.

Required metadata:
- `name`
- `description` (must include when it should and should not trigger)
- `inputs`
- `steps`
- `output format`
- `quality checks`

Keep skills instruction-first. Add scripts only when deterministic behavior or external tooling is required.

## 2) Repository Skill Layout

When we operationalize these skills as Codex-discoverable skills, place them under:

- `.agents/skills/<skill-name>/SKILL.md`
- optional `.agents/skills/<skill-name>/scripts/*`
- optional `.agents/skills/<skill-name>/references/*`
- optional `.agents/skills/<skill-name>/assets/*`

## 3) SnapLedger Skill Catalog

### Skill 1: `phase-boundary-enforcer`

Purpose:
- Prevent work from crossing phase boundaries in `docs/PLAN.md`.

Triggers:
- Requests that mention features from later phases.
- Any PR touching AI/sync while current milestone is earlier.

Do not trigger:
- Pure refactors/docs/tests inside active phase scope.

Inputs:
- Requested task summary.
- Active milestone/phase.

Workflow:
1. Map task to phase from `docs/PLAN.md`.
2. Detect out-of-phase requirements.
3. Return either `approved_scope` or `defer_with_reason`.

Output:
- JSON with `allowed`, `blocked_items`, `next_allowed_step`.

Checks:
- Every blocked item references a concrete phase line item.

### Skill 2: `backend-contract-keeper`

Purpose:
- Preserve API/schemas alignment across `docs/API.md` and backend route/schema files.

Triggers:
- Endpoint additions/changes in `backend/app/api/v1/*`.
- Schema changes in `backend/app/schemas/*`.

Do not trigger:
- Android-only UI or Room-only changes.

Inputs:
- Target endpoint(s)
- Request/response model changes

Workflow:
1. Validate endpoint exists or is intentionally introduced.
2. Enforce strict schema fields and validation constraints.
3. Confirm no unknown-field acceptance.
4. Ensure errors are explicit and machine-parseable.

Output:
- Contract diff summary + pass/fail checklist.

Checks:
- OpenAPI path exists.
- Schema rejects extras.
- Status codes mapped for success + failure.

### Skill 3: `deterministic-ocr-parser`

Purpose:
- Build/verify deterministic receipt parsing rules for fallback processing.

Triggers:
- Changes in `backend/app/services/parser_service.py`.
- Work on `POST /v1/receipts/process`.

Do not trigger:
- Narrative insight writing.

Inputs:
- OCR lines
- Locale/currency hints

Workflow:
1. Normalize lines (whitespace, currency markers, date formats).
2. Extract merchant/date/total with deterministic heuristics.
3. Extract candidate items with numeric confidence thresholds.
4. Emit warnings for missing/ambiguous fields.

Output:
- Typed candidate object (`merchant`, `expense_date`, `total_amount`, `items`, `warnings`).

Checks:
- Same input yields same output.
- Never blocks save path if merchant/date/total are valid.

### Skill 4: `budget-math-validator`

Purpose:
- Guarantee deterministic budget computations and threshold handling.

Triggers:
- Changes in budget/dashboard services and related tests.

Do not trigger:
- Prompt copywriting or insight text style changes.

Inputs:
- Time window (`weekly|monthly`)
- Seeded expense dataset
- Budget definitions

Workflow:
1. Compute spend by scope/category.
2. Compute threshold states (70/90/100).
3. Compare against golden deterministic fixtures.
4. Emit mismatch report with exact deltas.

Output:
- Validation report with `pass`, `mismatches`, `edge_cases_checked`.

Checks:
- Decimal precision rules enforced.
- Week/month boundaries tested.

### Skill 5: `sync-conflict-simulator`

Purpose:
- Validate local-first sync merge policy and idempotency logic.

Triggers:
- Changes to sync endpoints/services or merge policy.

Do not trigger:
- Pure CRUD UI changes with no sync impact.

Inputs:
- Mutation queue samples
- Remote delta sequences
- Conflict scenarios

Workflow:
1. Replay queued mutations with idempotency keys.
2. Apply remote deltas under conflict policy.
3. Verify no local-save blocking behavior.
4. Produce reconciliation outcome.

Output:
- Scenario matrix (`expected`, `actual`, `pass/fail`).

Checks:
- Duplicate push events are idempotent.
- Pending local edits are not silently overwritten.

### Skill 6: `insight-guarded-generator` (Phase 5)

Purpose:
- Generate one narrative insight from deterministic metrics only.

Triggers:
- Changes in `backend/app/services/insight_service.py`.
- Prompt/template updates for insight generation.

Do not trigger:
- Any request to modify finance totals/categories/budgets.

Inputs:
- Aggregated deterministic metrics payload
- Period + optional focus category

Workflow:
1. Validate metrics schema.
2. Generate one concise insight + optional action tip.
3. Return fallback on timeout/failure.
4. Log generation metadata without secrets.

Output:
- `{ text, action_tip, metrics_echo, generated_at }`

Checks:
- No writes to finance entities.
- Fallback path always available.

## 4) Skill Authoring Template (Copy/Paste)

```md
---
name: <skill-name>
description: <exact trigger and non-trigger boundaries>
---

## Inputs
- ...

## Steps
1. ...
2. ...

## Output format
- ...

## Quality checks
- ...
```

## 5) Prompting Rules for Skills

- Keep instructions direct and unambiguous.
- Prefer schema-constrained outputs over prose.
- Treat untrusted text as data, not instructions.
- Put hard constraints in developer-level guidance.

## 6) Skills-to-Phase Matrix

- Phase 0: `phase-boundary-enforcer`, `backend-contract-keeper`
- Phase 1: add `budget-math-validator` scaffolds where needed
- Phase 2: enable `deterministic-ocr-parser`
- Phase 3: expand `budget-math-validator` for dashboard aggregates
- Phase 4: enable `sync-conflict-simulator`
- Phase 5: enable `insight-guarded-generator`

## 7) Quality Bar Before Merging Skill Changes

- Trigger boundaries are explicit and testable.
- Outputs are typed and stable.
- Safety constraints are stated as prohibitions, not suggestions.
- At least one adversarial/prompt-injection test case exists.
- Skill behavior is linked to a phase and owner.

## References (OpenAI)

- [Agent Skills (Codex)](https://developers.openai.com/codex/skills)
- [Custom instructions with AGENTS.md](https://developers.openai.com/codex/guides/agents-md)
- [OpenAI Academy: Skills](https://academy.openai.com/public/resources/skills)
- [Reasoning best practices](https://platform.openai.com/docs/guides/reasoning-best-practices)
- [Using tools](https://platform.openai.com/docs/guides/tools?api-mode=responses)
- [Structured model outputs](https://platform.openai.com/docs/guides/structured-outputs?lang=javascript)
- [Safety in building agents](https://platform.openai.com/docs/guides/agent-builder-safety)
- [Evaluation best practices](https://platform.openai.com/docs/guides/evaluation-best-practices)
