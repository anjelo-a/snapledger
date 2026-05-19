from __future__ import annotations

import json
from datetime import UTC, datetime
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
ARTIFACTS_DIR = ROOT / "backend" / "eval_artifacts"
OUTPUT_PATH = ROOT / "docs" / "RECEIPT_EVAL_METRICS.md"


def _latest(pattern: str) -> Path | None:
    files = sorted(ARTIFACTS_DIR.glob(pattern))
    if not files:
        return None
    return files[-1]


def _load_json(path: Path | None) -> dict[str, Any] | None:
    if path is None or not path.exists():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def _line_or_na(label: str, value: Any) -> str:
    rendered = "n/a" if value is None else str(value)
    return f"- `{label}`: `{rendered}`"


def _section_eval(title: str, artifact_path: Path | None, payload: dict[str, Any] | None) -> list[str]:
    lines = [f"### {title}"]
    if artifact_path is None or payload is None:
        lines.append("No artifact found.")
        lines.append("")
        return lines

    metrics = payload.get("metrics", {})
    lines.append(f"Source artifact: `backend/eval_artifacts/{artifact_path.name}`")
    lines.append(f"- Commit SHA: `{payload.get('commit_sha', 'unknown')}`")
    lines.append(f"- Dataset: `{payload.get('dataset', 'unknown')}`")
    lines.append(_line_or_na("schema_valid_rate", metrics.get("schema_valid_rate")))
    lines.append(_line_or_na("total_amount_non_null_precision", metrics.get("total_amount_non_null_precision")))
    lines.append(
        _line_or_na(
            "total_amount_null_on_uncertainty_recall",
            metrics.get("total_amount_null_on_uncertainty_recall"),
        )
    )
    lines.append(_line_or_na("warning_code_false_positive_rate", metrics.get("warning_code_false_positive_rate")))
    lines.append(
        "- Non-200: "
        f"`422={metrics.get('non_200_422', 'n/a')}, "
        f"429={metrics.get('non_200_429', 'n/a')}, "
        f"5xx={metrics.get('non_200_5xx', 'n/a')}, "
        f"other={metrics.get('non_200_other', 'n/a')}`"
    )
    lines.append("")
    return lines


def _section_perf(artifact_path: Path | None, payload: dict[str, Any] | None) -> list[str]:
    lines = ["### Full perf (latest available)"]
    if artifact_path is None or payload is None:
        lines.append("No artifact found.")
        lines.append("")
        return lines

    metrics = payload.get("metrics", {})
    status_counts = payload.get("status_counts", {})
    status_line = ", ".join(f"{k}={v}" for k, v in sorted(status_counts.items(), key=lambda x: str(x[0]))) or "n/a"

    lines.append(f"Source artifact: `backend/eval_artifacts/{artifact_path.name}`")
    lines.append(f"- Commit SHA: `{payload.get('commit_sha', 'unknown')}`")
    lines.append(f"- Dataset: `{payload.get('dataset', 'unknown')}`")
    lines.append(f"- Repeats: `{payload.get('repeats', 'n/a')}` ({payload.get('counts', {}).get('runs', 'n/a')} total runs)")
    lines.append(_line_or_na("latency_p50_ms", metrics.get("latency_p50_ms")))
    lines.append(_line_or_na("latency_p95_ms", metrics.get("latency_p95_ms")))
    lines.append(_line_or_na("latency_p99_ms", metrics.get("latency_p99_ms")))
    lines.append(_line_or_na("latency_avg_ms", metrics.get("latency_avg_ms")))
    lines.append(_line_or_na("timeout_rate", metrics.get("timeout_rate")))
    lines.append(_line_or_na("rate_limit_429_rate", metrics.get("rate_limit_429_rate")))
    lines.append(_line_or_na("non_200_rate", metrics.get("non_200_rate")))
    lines.append(f"- `status_counts`: `{status_line}`")
    lines.append("")
    return lines


def _section_insight(artifact_path: Path | None, payload: dict[str, Any] | None, title: str) -> list[str]:
    lines = [f"### {title}"]
    if artifact_path is None or payload is None:
        lines.append("No artifact found.")
        lines.append("")
        return lines

    metrics = payload.get("metrics", {})
    lines.append(f"Source artifact: `backend/eval_artifacts/{artifact_path.name}`")
    lines.append(f"- Commit SHA: `{payload.get('commit_sha', 'unknown')}`")
    lines.append(f"- Dataset: `{payload.get('dataset', 'unknown')}`")
    lines.append(_line_or_na("schema_valid_rate", metrics.get("schema_valid_rate")))
    lines.append(_line_or_na("status_match_rate", metrics.get("status_match_rate")))
    lines.append(_line_or_na("warning_match_rate", metrics.get("warning_match_rate")))
    lines.append(_line_or_na("prompt_source_match_rate", metrics.get("prompt_source_match_rate")))
    lines.append(_line_or_na("non_200", metrics.get("non_200")))
    lines.append("")
    return lines


def _section_sync(artifact_path: Path | None, payload: dict[str, Any] | None) -> list[str]:
    lines = ["### Sync determinism stress (latest)"]
    if artifact_path is None or payload is None:
        lines.append("No artifact found.")
        lines.append("")
        return lines

    metrics = payload.get("metrics", {})
    lines.append(f"Source artifact: `backend/eval_artifacts/{artifact_path.name}`")
    lines.append(f"- Commit SHA: `{payload.get('commit_sha', 'unknown')}`")
    lines.append(f"- Dataset: `{payload.get('dataset', 'unknown')}`")
    lines.append(_line_or_na("pass_rate", metrics.get("pass_rate")))
    lines.append(_line_or_na("failed", metrics.get("failed")))
    lines.append("")
    return lines


def main() -> int:
    canary_path = _latest("eval_receipt_canary_*.json")
    full_eval_path = _latest("eval_receipt_full_*.json")
    non_fab_path = _latest("eval_receipt_non_fabrication_fields_*.json")
    injection_path = _latest("eval_receipt_injection_*.json")
    perf_path = _latest("perf_receipt_full_*.json")
    insight_guardrail_path = _latest("insight_eval_insight_guardrail_adversarial_*.json")
    insight_contract_path = _latest("insight_eval_insight_contract_robustness_*.json")
    sync_path = _latest("sync_eval_sync_determinism_stress_*.json")

    canary = _load_json(canary_path)
    full_eval = _load_json(full_eval_path)
    non_fab = _load_json(non_fab_path)
    injection = _load_json(injection_path)
    perf = _load_json(perf_path)
    insight_guardrail = _load_json(insight_guardrail_path)
    insight_contract = _load_json(insight_contract_path)
    sync_eval = _load_json(sync_path)

    generated_at = datetime.now(tz=UTC).isoformat()

    lines: list[str] = [
        "# Receipt Eval Metrics",
        "",
        f"Status date: {datetime.now(tz=UTC).date().isoformat()}.",
        "Scope: tracked metrics for receipt extraction eval and perf runs.",
        f"Auto-generated at: `{generated_at}`.",
        "",
        "## Metric definitions",
        "",
        "### Eval quality metrics",
        "- `schema_valid_rate`: percent of `200` responses that contain required response keys.",
        "- `total_amount_non_null_precision`: when model emits non-null total for certain receipts, percent matching ground truth.",
        "- `total_amount_null_on_uncertainty_recall`: when ground truth marks total as uncertain, percent where model returns `null`.",
        "- `warning_code_false_positive_rate`: percent of predicted high-impact warning codes not expected by ground truth.",
        "- `non_200_422|429|5xx|other`: non-200 response breakdown.",
        "",
        "### Perf metrics",
        "- `latency_p50_ms`, `latency_p95_ms`, `latency_p99_ms`, `latency_avg_ms`",
        "- `timeout_rate`",
        "- `rate_limit_429_rate`",
        "- `non_200_rate`",
        "- `status_counts`",
        "",
        "### Insight eval metrics",
        "- `schema_valid_rate`, `status_match_rate`, `warning_match_rate`, `prompt_source_match_rate`, `non_200`",
        "",
        "### Sync eval metrics",
        "- `pass_rate`, `failed`",
        "",
        "## Current recorded metrics",
        "",
    ]

    lines.extend(_section_eval("Canary eval (latest)", canary_path, canary))
    lines.extend(_section_eval("Full eval (latest available)", full_eval_path, full_eval))
    lines.extend(_section_eval("Receipt non-fabrication fields (latest)", non_fab_path, non_fab))
    lines.extend(_section_eval("Receipt prompt injection (latest)", injection_path, injection))
    lines.extend(_section_perf(perf_path, perf))
    lines.extend(
        _section_insight(
            insight_guardrail_path,
            insight_guardrail,
            "Insight guardrail adversarial (latest)",
        )
    )
    lines.extend(
        _section_insight(
            insight_contract_path,
            insight_contract,
            "Insight contract robustness (latest)",
        )
    )
    lines.extend(_section_sync(sync_path, sync_eval))

    lines.extend(
        [
            "## Merge-gate thresholds",
            "- No regression in `total_amount_non_null_precision`.",
            "- No regression in `total_amount_null_on_uncertainty_recall`.",
            "- Warning-code false-positive increase limits:",
            "- +2 percentage points overall.",
            "- +5 percentage points for any single high-impact warning code.",
            "- p95 latency regression limit: `+15%` unless explicitly approved.",
            "",
            "## Update workflow",
            "1. Run eval/perf commands from `docs/RECEIPT_EVAL_RUNBOOK.md`.",
            "2. Regenerate this file with `cd backend && .venv/bin/python scripts/generate_receipt_eval_metrics_md.py`.",
            "3. Include metric deltas vs previous accepted baseline in PR notes.",
            "",
        ]
    )

    OUTPUT_PATH.write_text("\n".join(lines), encoding="utf-8")
    print(f"[receipt-metrics] wrote {OUTPUT_PATH}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
