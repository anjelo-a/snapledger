# Receipt Eval Datasets

The extraction baseline has two versioned datasets:
- `receipt_canary.jsonl`: frozen regression guardrail, run on every backend PR.
- `receipt_full.jsonl`: working benchmark set, run on extraction-sensitive PRs and nightly.

## Scale and composition
- `canary`: 8 receipts.
- `full`: 40 receipts.
  - 24 core (`clean`/`medium`/`hard`)
  - 8 adversarial safety cases
  - 8 frozen canary cases

## JSONL schema
One object per line:

Required:
- `id: string`
- `request: object` (payload for `POST /v1/receipts/process`)

Optional:
- `tier: string` (for dataset organization)
- `ground_truth.total_amount: string|null`
- `ground_truth.total_amount_uncertain: boolean`
- `ground_truth.expected_warning_codes: string[]`

Example:

```json
{
  "id": "canary-01-clean",
  "tier": "clean",
  "request": {"ocr_lines": ["ACME", "TOTAL 123.45"]},
  "ground_truth": {
    "total_amount": "123.45",
    "total_amount_uncertain": false,
    "expected_warning_codes": []
  }
}
```

## Governance
- Canary is immutable by default.
- Replace canary rows only with explicit PR rationale.
- Add new hard cases only after real regressions or close-call failures.
- For uncertain/illegible totals, set `total_amount_uncertain=true` and `total_amount=null`.

## Staging pipeline (auto-convert + manual promote)
- Use `scripts/manage_receipt_eval_staging.py` for safe ingestion from local scan exports.
- Pipeline:
- ingest raw rows into private staging (`evals/staging/receipt_staging.jsonl`)
- automatic redaction + dedupe
- manual approval (`approve`)
- controlled promotion (`promote`) into `receipt_full.jsonl` or `receipt_canary.jsonl`
- Guardrail: rows flagged with PII are blocked from promotion.

### Staging commands
```bash
cd backend
.venv/bin/python scripts/export_local_receipts_to_incoming.py --sqlite-db /path/to/review_local.db --output evals/incoming/new_scans.jsonl
.venv/bin/python scripts/manage_receipt_eval_staging.py ingest --input evals/incoming/new_scans.jsonl
.venv/bin/python scripts/manage_receipt_eval_staging.py stats
.venv/bin/python scripts/manage_receipt_eval_staging.py approve --all-clean
.venv/bin/python scripts/manage_receipt_eval_staging.py promote --target evals/receipt_full.jsonl --prefix real --limit 10
```
