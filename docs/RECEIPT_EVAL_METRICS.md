# Receipt Eval Metrics

Status date: 2026-05-19.
Scope: tracked metrics for receipt extraction eval and perf runs.
Auto-generated at: `2026-05-19T15:46:00.467002+00:00`.

## Metric definitions

### Eval quality metrics
- `schema_valid_rate`: percent of `200` responses that contain required response keys.
- `total_amount_non_null_precision`: when model emits non-null total for certain receipts, percent matching ground truth.
- `total_amount_null_on_uncertainty_recall`: when ground truth marks total as uncertain, percent where model returns `null`.
- `warning_code_false_positive_rate`: percent of predicted high-impact warning codes not expected by ground truth.
- `non_200_422|429|5xx|other`: non-200 response breakdown.

### Perf metrics
- `latency_p50_ms`, `latency_p95_ms`, `latency_p99_ms`, `latency_avg_ms`
- `timeout_rate`
- `rate_limit_429_rate`
- `non_200_rate`
- `status_counts`

### Insight eval metrics
- `schema_valid_rate`, `status_match_rate`, `warning_match_rate`, `prompt_source_match_rate`, `non_200`

### Sync eval metrics
- `pass_rate`, `failed`

## Current recorded metrics

### Canary eval (latest)
Source artifact: `backend/eval_artifacts/eval_receipt_canary_20260519T154249Z.json`
- Commit SHA: `e88c6c12edc35a45cee3696f960d77d8574ada59`
- Dataset: `evals/receipt_canary.jsonl`
- `schema_valid_rate`: `100.0% (8/8)`
- `total_amount_non_null_precision`: `100.0% (6/6)`
- `total_amount_null_on_uncertainty_recall`: `100.0% (2/2)`
- `warning_code_false_positive_rate`: `50.0% (2/4)`
- Non-200: `422=0, 429=0, 5xx=0, other=0`

### Full eval (latest available)
Source artifact: `backend/eval_artifacts/eval_receipt_full_20260519T154250Z.json`
- Commit SHA: `e88c6c12edc35a45cee3696f960d77d8574ada59`
- Dataset: `evals/receipt_full.jsonl`
- `schema_valid_rate`: `100.0% (40/40)`
- `total_amount_non_null_precision`: `100.0% (34/34)`
- `total_amount_null_on_uncertainty_recall`: `100.0% (6/6)`
- `warning_code_false_positive_rate`: `62.5% (10/16)`
- Non-200: `422=0, 429=0, 5xx=0, other=0`

### Receipt non-fabrication fields (latest)
Source artifact: `backend/eval_artifacts/eval_receipt_non_fabrication_fields_20260519T154250Z.json`
- Commit SHA: `e88c6c12edc35a45cee3696f960d77d8574ada59`
- Dataset: `evals/receipt_non_fabrication_fields.jsonl`
- `schema_valid_rate`: `100.0% (3/3)`
- `total_amount_non_null_precision`: `100.0% (3/3)`
- `total_amount_null_on_uncertainty_recall`: `0.0% (0/0)`
- `warning_code_false_positive_rate`: `25.0% (1/4)`
- Non-200: `422=0, 429=0, 5xx=0, other=0`

### Receipt prompt injection (latest)
Source artifact: `backend/eval_artifacts/eval_receipt_injection_20260519T154250Z.json`
- Commit SHA: `e88c6c12edc35a45cee3696f960d77d8574ada59`
- Dataset: `evals/receipt_injection.jsonl`
- `schema_valid_rate`: `100.0% (2/2)`
- `total_amount_non_null_precision`: `0.0% (0/0)`
- `total_amount_null_on_uncertainty_recall`: `100.0% (2/2)`
- `warning_code_false_positive_rate`: `0.0% (0/2)`
- Non-200: `422=0, 429=0, 5xx=0, other=0`

### Full perf (latest available)
Source artifact: `backend/eval_artifacts/perf_receipt_full_20260519T154251Z.json`
- Commit SHA: `e88c6c12edc35a45cee3696f960d77d8574ada59`
- Dataset: `evals/receipt_full.jsonl`
- Repeats: `3` (120 total runs)
- `latency_p50_ms`: `0.93`
- `latency_p95_ms`: `1.16`
- `latency_p99_ms`: `1.85`
- `latency_avg_ms`: `1.03`
- `timeout_rate`: `0.0% (0/120)`
- `rate_limit_429_rate`: `0.0% (0/120)`
- `non_200_rate`: `0.0% (0/120)`
- `status_counts`: `200=120`

### Insight guardrail adversarial (latest)
Source artifact: `backend/eval_artifacts/insight_eval_insight_guardrail_adversarial_20260519T154251Z.json`
- Commit SHA: `e88c6c12edc35a45cee3696f960d77d8574ada59`
- Dataset: `evals/insight_guardrail_adversarial.jsonl`
- `schema_valid_rate`: `100.0% (3/3)`
- `status_match_rate`: `100.0% (3/3)`
- `warning_match_rate`: `100.0% (3/3)`
- `prompt_source_match_rate`: `100.0% (3/3)`
- `non_200`: `0`

### Insight contract robustness (latest)
Source artifact: `backend/eval_artifacts/insight_eval_insight_contract_robustness_20260519T154253Z.json`
- Commit SHA: `e88c6c12edc35a45cee3696f960d77d8574ada59`
- Dataset: `evals/insight_contract_robustness.jsonl`
- `schema_valid_rate`: `100.0% (3/3)`
- `status_match_rate`: `100.0% (3/3)`
- `warning_match_rate`: `100.0% (3/3)`
- `prompt_source_match_rate`: `100.0% (3/3)`
- `non_200`: `0`

### Sync determinism stress (latest)
Source artifact: `backend/eval_artifacts/sync_eval_sync_determinism_stress_20260519T154253Z.json`
- Commit SHA: `e88c6c12edc35a45cee3696f960d77d8574ada59`
- Dataset: `evals/sync_determinism_stress.jsonl`
- `pass_rate`: `100.0% (4/4)`
- `failed`: `0`

## Merge-gate thresholds
- No regression in `total_amount_non_null_precision`.
- No regression in `total_amount_null_on_uncertainty_recall`.
- Warning-code false-positive increase limits:
- +2 percentage points overall.
- +5 percentage points for any single high-impact warning code.
- p95 latency regression limit: `+15%` unless explicitly approved.

## Update workflow
1. Run eval/perf commands from `docs/RECEIPT_EVAL_RUNBOOK.md`.
2. Regenerate this file with `cd backend && .venv/bin/python scripts/generate_receipt_eval_metrics_md.py`.
3. Include metric deltas vs previous accepted baseline in PR notes.
