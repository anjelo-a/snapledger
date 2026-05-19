from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from eval_support import (
    isolated_test_client,
    load_commit_sha,
    load_jsonl,
    rate_string,
    write_artifact,
)

REQUIRED_ENVELOPE_KEYS = {
    "schema_version",
    "agent_name",
    "task_id",
    "status",
    "result",
    "warnings",
    "errors",
}


@dataclass
class InsightEvalCounts:
    total: int = 0
    response_200: int = 0
    schema_valid: int = 0
    status_expected: int = 0
    warnings_expected: int = 0
    prompt_source_expected: int = 0
    non_200: int = 0


def _contains_all(container: list[str], expected: list[str]) -> bool:
    return all(item in container for item in expected)


def _evaluate_row(client: Any, row: dict[str, Any], counts: InsightEvalCounts) -> None:
    request_payload = row.get("request")
    expected = row.get("expected", {})
    if not isinstance(request_payload, dict):
        raise ValueError("Each insight eval row requires object field: request")
    if not isinstance(expected, dict):
        raise ValueError("expected must be an object when present")

    response = client.post("/v1/insights/chat", json=request_payload)
    if response.status_code != 200:
        counts.non_200 += 1
        print(
            "[insight-eval] non-200 response "
            f"id={row.get('id', counts.total)} status={response.status_code}"
        )
        return
    counts.response_200 += 1

    body = response.json()
    if REQUIRED_ENVELOPE_KEYS.issubset(body.keys()):
        result = body.get("result")
        if isinstance(result, dict) and {"answer", "prompt_source"}.issubset(result.keys()):
            counts.schema_valid += 1

    expected_status = expected.get("status")
    if expected_status is None or body.get("status") == expected_status:
        counts.status_expected += 1

    expected_warnings = expected.get("warnings_contains", [])
    if not isinstance(expected_warnings, list):
        raise ValueError("expected.warnings_contains must be a list when present")
    if _contains_all(body.get("warnings", []), expected_warnings):
        counts.warnings_expected += 1

    expected_prompt_source = expected.get("prompt_source")
    prompt_source = body.get("result", {}).get("prompt_source")
    if expected_prompt_source is None or prompt_source == expected_prompt_source:
        counts.prompt_source_expected += 1


def run_eval(dataset_path: Path, output_dir: Path) -> int:
    if not dataset_path.exists():
        print(f"[insight-eval] dataset not found: {dataset_path}")
        return 2

    rows = load_jsonl(dataset_path)
    counts = InsightEvalCounts()
    db_path = output_dir / f"_tmp_insight_eval_{dataset_path.stem}.db"
    with isolated_test_client(db_path) as client:
        for row in rows:
            counts.total += 1
            _evaluate_row(client, row, counts)

    metrics = {
        "schema_valid_rate": rate_string(counts.schema_valid, counts.response_200),
        "status_match_rate": rate_string(counts.status_expected, counts.total),
        "warning_match_rate": rate_string(counts.warnings_expected, counts.total),
        "prompt_source_match_rate": rate_string(counts.prompt_source_expected, counts.total),
        "non_200": counts.non_200,
    }
    artifact_payload = {
        "mode": "insight_eval",
        "dataset": str(dataset_path),
        "timestamp_utc": datetime.now(tz=UTC).isoformat(),
        "commit_sha": load_commit_sha(),
        "counts": counts.__dict__,
        "metrics": metrics,
    }
    json_path, txt_path = write_artifact(
        output_dir=output_dir,
        dataset_path=dataset_path,
        mode="insight_eval",
        payload=artifact_payload,
    )
    print(f"[insight-eval] dataset={dataset_path}")
    print(f"[insight-eval] artifacts_json={json_path}")
    print(f"[insight-eval] artifacts_summary={txt_path}")
    print(f"[insight-eval] total={counts.total}")
    for key, value in metrics.items():
        print(f"[insight-eval] {key}={value}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Run SnapLedger insight safety/contract evals.")
    parser.add_argument("--dataset", required=True, help="Path to JSONL insight eval dataset.")
    parser.add_argument(
        "--output-dir",
        default="eval_artifacts",
        help="Directory for timestamped JSON and text summaries.",
    )
    args = parser.parse_args()
    return run_eval(Path(args.dataset), Path(args.output_dir))


if __name__ == "__main__":
    raise SystemExit(main())
