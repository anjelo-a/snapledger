from __future__ import annotations

import argparse
import json
import os
import statistics
import subprocess
import time
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from fastapi.testclient import TestClient

from app.main import app


@dataclass
class EvalCounts:
    total: int = 0
    response_200: int = 0
    schema_valid: int = 0
    http_422: int = 0
    http_429: int = 0
    http_5xx: int = 0
    http_other: int = 0
    total_non_null_predictions: int = 0
    total_non_null_correct: int = 0
    total_expected_uncertain: int = 0
    total_uncertain_nulled: int = 0
    warning_codes_expected: int = 0
    warning_codes_false_positive: int = 0


@dataclass
class PerfCounts:
    runs: int = 0
    timeout_count: int = 0
    rate_limit_count: int = 0
    non_200_count: int = 0


HIGH_IMPACT_WARNING_PATTERNS = (
    "_missing",
    "TOTAL_MISMATCH",
    "GEMINI_INVALID_",
    "GEMINI_PROMPT_INJECTION_DETECTED",
)
REQUIRED_KEYS = {
    "merchant",
    "expense_date",
    "total_amount",
    "items",
    "warnings",
    "warning_codes",
    "field_confidence",
}


def _load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for idx, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        try:
            payload = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"Invalid JSONL at line {idx}: {exc}") from exc
        if not isinstance(payload, dict):
            raise ValueError(f"Invalid JSONL at line {idx}: expected object")
        rows.append(payload)
    return rows


def _is_amount_equal(left: Any, right: Any) -> bool:
    if left is None or right is None:
        return left is None and right is None
    return str(left).strip() == str(right).strip()


def _pct(numerator: int, denominator: int) -> float:
    if denominator == 0:
        return 0.0
    return round((numerator / denominator) * 100, 2)


def _rate_string(numerator: int, denominator: int) -> str:
    return f"{_pct(numerator, denominator)}% ({numerator}/{denominator})"


def _load_commit_sha() -> str:
    env_sha = os.getenv("GITHUB_SHA")
    if env_sha:
        return env_sha
    try:
        output = subprocess.check_output(
            ["git", "rev-parse", "HEAD"],
            text=True,
            stderr=subprocess.DEVNULL,
        ).strip()
        return output or "unknown"
    except Exception:
        return "unknown"


def _is_high_impact_warning(code: str) -> bool:
    return any(token in code for token in HIGH_IMPACT_WARNING_PATTERNS)


def _percentile(latencies: list[float], percentile: float) -> float:
    if not latencies:
        return 0.0
    if len(latencies) == 1:
        return round(latencies[0], 2)
    ordered = sorted(latencies)
    index = int((len(ordered) - 1) * percentile)
    return round(ordered[index], 2)


def _classify_non_200(status_code: int, counts: EvalCounts) -> None:
    if status_code == 422:
        counts.http_422 += 1
    elif status_code == 429:
        counts.http_429 += 1
    elif status_code >= 500:
        counts.http_5xx += 1
    else:
        counts.http_other += 1


def _write_artifact(
    *,
    output_dir: Path,
    dataset_path: Path | None,
    mode: str,
    payload: dict[str, Any],
) -> tuple[Path, Path]:
    output_dir.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now(tz=UTC).strftime("%Y%m%dT%H%M%SZ")
    dataset_id = dataset_path.stem if dataset_path else "perf"
    base = f"{mode}_{dataset_id}_{timestamp}"
    json_path = output_dir / f"{base}.json"
    txt_path = output_dir / f"{base}.txt"
    json_path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    summary_lines = [
        f"mode={mode}",
        f"dataset={dataset_path}" if dataset_path else "dataset=n/a",
        f"commit_sha={payload.get('commit_sha')}",
        f"timestamp_utc={payload.get('timestamp_utc')}",
        "",
        "metrics:",
    ]
    for key, value in payload.get("metrics", {}).items():
        summary_lines.append(f"- {key}: {value}")
    txt_path.write_text("\n".join(summary_lines) + "\n", encoding="utf-8")
    return json_path, txt_path


def run_eval(dataset_path: Path, output_dir: Path) -> int:
    if not dataset_path.exists():
        print(f"[receipt-eval] dataset not found: {dataset_path}")
        return 2

    rows = _load_jsonl(dataset_path)
    counts = EvalCounts()

    with TestClient(app) as client:
        for row in rows:
            counts.total += 1
            request_payload = row.get("request")
            ground_truth = row.get("ground_truth", {})
            if not isinstance(request_payload, dict):
                raise ValueError("Each row requires object field: request")
            if not isinstance(ground_truth, dict):
                raise ValueError("ground_truth must be an object when present")

            response = client.post("/v1/receipts/process", json=request_payload)
            if response.status_code != 200:
                _classify_non_200(response.status_code, counts)
                print(
                    "[receipt-eval] non-200 response "
                    f"index={counts.total} status={response.status_code}"
                )
                continue
            counts.response_200 += 1

            body = response.json()
            if REQUIRED_KEYS.issubset(body.keys()):
                counts.schema_valid += 1

            expected_total = ground_truth.get("total_amount")
            predicted_total = body.get("total_amount")
            uncertain_total = bool(ground_truth.get("total_amount_uncertain", False))
            if uncertain_total:
                counts.total_expected_uncertain += 1
                if predicted_total is None:
                    counts.total_uncertain_nulled += 1
            elif predicted_total is not None:
                counts.total_non_null_predictions += 1
                if _is_amount_equal(predicted_total, expected_total):
                    counts.total_non_null_correct += 1

            expected_warning_codes = set(ground_truth.get("expected_warning_codes", []))
            predicted_warning_codes = set(body.get("warning_codes", []))
            for code in predicted_warning_codes:
                if not _is_high_impact_warning(code):
                    continue
                counts.warning_codes_expected += 1
                if code not in expected_warning_codes:
                    counts.warning_codes_false_positive += 1

    metrics = {
        "schema_valid_rate": _rate_string(counts.schema_valid, counts.response_200),
        "total_amount_non_null_precision": _rate_string(
            counts.total_non_null_correct,
            counts.total_non_null_predictions,
        ),
        "total_amount_null_on_uncertainty_recall": _rate_string(
            counts.total_uncertain_nulled,
            counts.total_expected_uncertain,
        ),
        "warning_code_false_positive_rate": _rate_string(
            counts.warning_codes_false_positive,
            counts.warning_codes_expected,
        ),
        "non_200_422": counts.http_422,
        "non_200_429": counts.http_429,
        "non_200_5xx": counts.http_5xx,
        "non_200_other": counts.http_other,
    }
    artifact_payload = {
        "mode": "eval",
        "dataset": str(dataset_path),
        "timestamp_utc": datetime.now(tz=UTC).isoformat(),
        "commit_sha": _load_commit_sha(),
        "counts": counts.__dict__,
        "metrics": metrics,
    }
    json_path, txt_path = _write_artifact(
        output_dir=output_dir,
        dataset_path=dataset_path,
        mode="eval",
        payload=artifact_payload,
    )

    print(f"[receipt-eval] dataset={dataset_path}")
    print(f"[receipt-eval] artifacts_json={json_path}")
    print(f"[receipt-eval] artifacts_summary={txt_path}")
    print(f"[receipt-eval] total={counts.total}")
    print(f"[receipt-eval] response_200={counts.response_200}/{counts.total}")
    print(f"[receipt-eval] schema_valid={metrics['schema_valid_rate']}")
    print(
        "[receipt-eval] total_amount_non_null_precision="
        f"{metrics['total_amount_non_null_precision']}"
    )
    print(
        "[receipt-eval] total_amount_null_on_uncertainty_recall="
        f"{metrics['total_amount_null_on_uncertainty_recall']}"
    )
    print(
        "[receipt-eval] warning_code_false_positive_rate="
        f"{metrics['warning_code_false_positive_rate']}"
    )
    print(
        "[receipt-eval] non_200_breakdown="
        f"422={counts.http_422},429={counts.http_429},5xx={counts.http_5xx},other={counts.http_other}"
    )
    return 0


def run_perf(
    *,
    dataset_path: Path,
    output_dir: Path,
    repeats: int,
) -> int:
    if not dataset_path.exists():
        print(f"[receipt-perf] dataset not found: {dataset_path}")
        return 2
    if repeats < 1:
        raise ValueError("--repeats must be >= 1")

    rows = _load_jsonl(dataset_path)
    latencies_ms: list[float] = []
    perf = PerfCounts()
    status_counts: dict[int, int] = {}

    with TestClient(app) as client:
        for _ in range(repeats):
            for row in rows:
                request_payload = row.get("request")
                if not isinstance(request_payload, dict):
                    raise ValueError("Each row requires object field: request")
                started = time.perf_counter()
                response = client.post("/v1/receipts/process", json=request_payload)
                elapsed_ms = (time.perf_counter() - started) * 1000
                latencies_ms.append(elapsed_ms)
                perf.runs += 1
                status_counts[response.status_code] = status_counts.get(response.status_code, 0) + 1
                if response.status_code != 200:
                    perf.non_200_count += 1
                if response.status_code == 429:
                    perf.rate_limit_count += 1
                if response.status_code == 504:
                    perf.timeout_count += 1

    p50 = _percentile(latencies_ms, 0.50)
    p95 = _percentile(latencies_ms, 0.95)
    p99 = _percentile(latencies_ms, 0.99)
    metrics = {
        "latency_p50_ms": p50,
        "latency_p95_ms": p95,
        "latency_p99_ms": p99,
        "latency_avg_ms": round(statistics.fmean(latencies_ms), 2) if latencies_ms else 0.0,
        "timeout_rate": _rate_string(perf.timeout_count, perf.runs),
        "rate_limit_429_rate": _rate_string(perf.rate_limit_count, perf.runs),
        "non_200_rate": _rate_string(perf.non_200_count, perf.runs),
    }
    artifact_payload = {
        "mode": "perf",
        "dataset": str(dataset_path),
        "timestamp_utc": datetime.now(tz=UTC).isoformat(),
        "commit_sha": _load_commit_sha(),
        "repeats": repeats,
        "status_counts": status_counts,
        "counts": perf.__dict__,
        "metrics": metrics,
    }
    json_path, txt_path = _write_artifact(
        output_dir=output_dir,
        dataset_path=dataset_path,
        mode="perf",
        payload=artifact_payload,
    )
    print(f"[receipt-perf] dataset={dataset_path} repeats={repeats}")
    print(f"[receipt-perf] artifacts_json={json_path}")
    print(f"[receipt-perf] artifacts_summary={txt_path}")
    print(
        "[receipt-perf] latency_ms="
        f"p50={p50},p95={p95},p99={p99},avg={metrics['latency_avg_ms']}"
    )
    print(f"[receipt-perf] timeout_rate={metrics['timeout_rate']}")
    print(f"[receipt-perf] rate_limit_429_rate={metrics['rate_limit_429_rate']}")
    print(f"[receipt-perf] non_200_rate={metrics['non_200_rate']}")
    print(f"[receipt-perf] status_counts={status_counts}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Run receipt extraction eval/perf baselines.")
    parser.add_argument(
        "--dataset",
        required=True,
        help="Path to JSONL dataset with request + ground_truth fields.",
    )
    parser.add_argument(
        "--mode",
        choices=("eval", "perf"),
        default="eval",
        help="Run quality eval metrics or performance metrics.",
    )
    parser.add_argument(
        "--repeats",
        type=int,
        default=1,
        help="Number of full-dataset passes for perf mode.",
    )
    parser.add_argument(
        "--output-dir",
        default="eval_artifacts",
        help="Directory for timestamped JSON and text summaries.",
    )
    args = parser.parse_args()
    dataset_path = Path(args.dataset)
    output_dir = Path(args.output_dir)
    if args.mode == "perf":
        return run_perf(dataset_path=dataset_path, output_dir=output_dir, repeats=args.repeats)
    return run_eval(dataset_path, output_dir)


if __name__ == "__main__":
    raise SystemExit(main())
