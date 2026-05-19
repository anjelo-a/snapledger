#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BACKEND_DIR="$ROOT_DIR/backend"

cd "$BACKEND_DIR"

.venv/bin/python scripts/run_receipt_eval.py --mode eval --dataset evals/receipt_canary.jsonl --output-dir eval_artifacts
.venv/bin/python scripts/run_receipt_eval.py --mode eval --dataset evals/receipt_full.jsonl --output-dir eval_artifacts
.venv/bin/python scripts/run_receipt_eval.py --mode eval --dataset evals/receipt_non_fabrication_fields.jsonl --output-dir eval_artifacts
.venv/bin/python scripts/run_receipt_eval.py --mode eval --dataset evals/receipt_injection.jsonl --output-dir eval_artifacts
.venv/bin/python scripts/run_receipt_eval.py --mode perf --dataset evals/receipt_full.jsonl --repeats 3 --output-dir eval_artifacts
.venv/bin/python scripts/run_insight_eval.py --dataset evals/insight_guardrail_adversarial.jsonl --output-dir eval_artifacts
.venv/bin/python scripts/run_insight_eval.py --dataset evals/insight_contract_robustness.jsonl --output-dir eval_artifacts
.venv/bin/python scripts/run_sync_eval.py --dataset evals/sync_determinism_stress.jsonl --output-dir eval_artifacts
.venv/bin/python scripts/generate_receipt_eval_metrics_md.py

echo "[receipt-eval-suite] complete"
