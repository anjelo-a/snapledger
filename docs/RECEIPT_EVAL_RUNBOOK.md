# Receipt Eval Runbook

Status date: May 19, 2026.
Scope: SnapLedger backend receipt extraction eval and perf runs.

## Goal
Provide one repeatable process for running receipt extraction evaluations, comparing current runs to prior baselines, and applying merge gates.

## Phase Alignment
This runbook supports the Phase 2 contract lock and extraction safety posture documented in:
- `docs/PLAN.md`
- `docs/TESTING.md`
- `backend/evals/README.md`

## Datasets
Two versioned datasets are maintained under `backend/evals/`:
- `receipt_canary.jsonl`: frozen regression guardrail set (8 receipts).
- `receipt_full.jsonl`: working benchmark set (40 receipts).

Full set composition:
- 24 core (`clean`/`medium`/`hard`)
- 8 adversarial safety cases
- 8 frozen canary cases

## When to run which eval
- Run `canary` on every backend PR touching `backend/**`.
- Run `full` before merge for PRs that change:
- `backend/app/services/parser_service.py`
- prompt/extraction mapping logic
- extraction request/response schema (`backend/app/schemas/expense.py`)
- extraction route behavior (`POST /v1/receipts/process`)
- Run `full` nightly in CI to catch drift.
- Run perf sweep nightly with repeated passes.

## Prerequisites
1. Backend dependencies installed and virtual env ready.
2. Run from backend directory.
3. Ensure required env vars are available when your local route depends on them (for example Gemini settings).

## Commands

### One-shot suite (recommended)
```bash
# from repo root
backend/scripts/run_receipt_eval_suite.sh
```

This runs canary + full + perf and then auto-regenerates:
- `docs/RECEIPT_EVAL_METRICS.md`

### Quality eval: canary
```bash
cd backend
.venv/bin/python scripts/run_receipt_eval.py --mode eval --dataset evals/receipt_canary.jsonl --output-dir eval_artifacts
```

### Quality eval: full
```bash
cd backend
.venv/bin/python scripts/run_receipt_eval.py --mode eval --dataset evals/receipt_full.jsonl --output-dir eval_artifacts
```

### Performance eval: full dataset repeated
```bash
cd backend
.venv/bin/python scripts/run_receipt_eval.py --mode perf --dataset evals/receipt_full.jsonl --repeats 3 --output-dir eval_artifacts
```

## What the runner outputs
Each run writes timestamped artifacts into `backend/eval_artifacts/`:
- JSON artifact: machine-readable metrics and counts
- TXT artifact: human summary

Artifact payload includes:
- `mode` (`eval` or `perf`)
- `dataset`
- `timestamp_utc`
- `commit_sha` (from `GITHUB_SHA` or local `git rev-parse HEAD`)
- `counts`
- `metrics`

## Metrics to compare between runs
For eval mode:
- `schema_valid_rate`
- `total_amount_non_null_precision`
- `total_amount_null_on_uncertainty_recall`
- `warning_code_false_positive_rate`
- non-200 breakdown (`422`, `429`, `5xx`, `other`)

For perf mode:
- `latency_p50_ms`
- `latency_p95_ms`
- `latency_p99_ms`
- `latency_avg_ms`
- `timeout_rate`
- `rate_limit_429_rate`
- `non_200_rate`
- `status_counts`

## Merge gate policy
For extraction-sensitive changes:
- No regression in `total_amount` non-null precision.
- No regression in null-on-uncertainty recall.
- Warning-code false-positive rate increase must stay within:
- +2 percentage points overall.
- +5 percentage points for any single high-impact warning code.
- p95 `/v1/receipts/process` latency regression must stay within +15% unless explicitly approved.

## Reporting rule for small datasets
- Always report metric with denominator (example: `87.5% (14/16)`).
- Treat sub-5 percentage point deltas as noise unless confirmed.
- Repeat suspicious deltas across at least 2 independent runs before claiming regression or fix.

## Recommended comparison workflow
1. Run canary/full/perf commands.
2. Open latest `eval_artifacts/*.json` files.
3. Compare against prior accepted baseline (typically previous merge commit).
4. Record pass/fail against merge gates in PR notes.
5. If regression is suspected, rerun at least once to confirm.

## Dataset curation workflow
Use this only when adding real scan samples.

### Export + ingest + review + promote
```bash
cd backend
.venv/bin/python scripts/export_local_receipts_to_incoming.py --sqlite-db /path/to/review_local.db --output evals/incoming/new_scans.jsonl
.venv/bin/python scripts/manage_receipt_eval_staging.py ingest --input evals/incoming/new_scans.jsonl
.venv/bin/python scripts/manage_receipt_eval_staging.py stats
.venv/bin/python scripts/manage_receipt_eval_staging.py approve --all-clean
.venv/bin/python scripts/manage_receipt_eval_staging.py promote --target evals/receipt_full.jsonl --prefix real --limit 10
```

### Curation guardrails
- Canary dataset is immutable by default.
- Replace canary rows only with explicit PR rationale.
- Add new hard cases only after real regressions or close-call failures.
- Rows flagged with PII are blocked from promotion.
- For uncertain/illegible totals: set `total_amount_uncertain=true` and `total_amount=null`.

## CI cadence
- Every relevant backend PR: canary.
- Extraction-sensitive PR pre-merge: full.
- Nightly CI: full + perf (repeated).
- Archive metrics by merge commit SHA for auditability.

## Troubleshooting
- Dataset not found: verify `--dataset` path relative to `backend/`.
- Non-200 spikes: inspect status code breakdown and recent route/schema changes.
- 429 increases: check local/CI rate-limit settings and environment drift.
- Perf variance: rerun and compare multiple runs before concluding regression.

## Source references
- `docs/TESTING.md`
- `backend/README.md`
- `backend/evals/README.md`
- `backend/scripts/run_receipt_eval.py`
- `backend/scripts/manage_receipt_eval_staging.py`
- `backend/scripts/export_local_receipts_to_incoming.py`
