# SnapLedger

SnapLedger is an Android-first, local-first personal finance system with a FastAPI backend for sync, receipt processing, and deterministic budget/dashboard logic.

## Project Status

Status date: **May 24, 2026**

- Core Android and backend foundation: **complete**
- Deterministic finance services (budgets, dashboard aggregates, sync contract behavior): **complete**
- Receipt extraction contract and validation gate (`POST /v1/receipts/process`): **complete**
- Insight service contract and guardrails: **complete**
- Phase state: **Phase 2 contract lock achieved; system is in stabilized engineering state with eval gates in place**

## Eval Run Results (Highlighted)

Latest consolidated metrics source: [`docs/RECEIPT_EVAL_METRICS.md`](docs/RECEIPT_EVAL_METRICS.md)
Artifact timestamp set: **2026-05-19T15:42:49Z to 2026-05-19T15:42:53Z**

- Receipt canary eval: **100% schema validity (8/8)**, **100% total precision (6/6)**
- Receipt full eval: **100% schema validity (40/40)**, **100% total precision (34/34)**, **100% uncertainty nulling recall (6/6)**
- Receipt non-fabrication eval lane: **100% schema validity (3/3)**, **100% total precision (3/3)**
- Receipt prompt-injection eval lane: **100% schema validity (2/2)**, **100% uncertainty nulling recall (2/2)**
- Receipt perf run (120 executions): **0.93ms p50**, **1.16ms p95**, **0% timeouts**, **0% non-200**
- Insight guardrail adversarial eval: **100% schema/status/warning/prompt-source match (3/3)**
- Insight contract robustness eval: **100% schema/status/warning/prompt-source match (3/3)**
- Sync determinism stress eval: **100% pass rate (4/4), 0 failed**

## Repository Structure

- `app/` Android application (Jetpack Compose, local-first UX, sync client)
- `backend/` FastAPI backend (API contracts, deterministic services, parser/inference orchestration)
- `docs/` Architecture, API, planning, testing, and eval runbooks
- `scripts/` Repository utility scripts
- `gradle/` Gradle wrapper support files

## Key Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [API Contract](docs/API.md)
- [Development Setup](docs/DEVELOPMENT_SETUP.md)
- [Testing Strategy](docs/TESTING.md)
- [Receipt Eval Runbook](docs/RECEIPT_EVAL_RUNBOOK.md)
- [Receipt Eval Metrics](docs/RECEIPT_EVAL_METRICS.md)

## Local Development

### Android

Open the repository root in Android Studio and run the `app` module.

### Backend

```bash
cd backend
python -m venv .venv
source .venv/bin/activate
pip install -e .[dev]
uvicorn app.main:app --reload
```

## Guardrails

- Deterministic money logic remains deterministic and source-of-truth.
- AI output is schema-validated and cannot bypass business validation.
- Uncertain receipt fields must be nulled (never fabricated).
- Backend or model failures must not block Android local-save flows.
