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


@dataclass
class SyncEvalCounts:
    total: int = 0
    passed: int = 0
    failed: int = 0


def _expense_mutation(
    *,
    idempotency_key: str,
    receipt_id: str,
    operation: str = "create",
    merchant: str = "Eval Cafe",
    total_amount: str = "42.50",
) -> dict[str, Any]:
    payload: dict[str, Any] = {"id": receipt_id}
    if operation in {"create", "update"}:
        payload.update(
            {
                "source": "scan",
                "merchant": merchant,
                "expense_date": "2026-05-01",
                "total_amount": total_amount,
                "currency": "PHP",
                "items": [{"name": "Coffee", "amount": total_amount}],
            }
        )
    return {
        "idempotency_key": idempotency_key,
        "entity": "expense",
        "operation": operation,
        "occurred_at": "2026-05-01T00:00:00Z",
        "payload": payload,
    }


def _push(client: Any, mutations: list[dict[str, Any]]) -> dict[str, Any]:
    response = client.post("/v1/sync/push", json={"mutations": mutations})
    if response.status_code != 200:
        raise AssertionError(f"sync push returned {response.status_code}: {response.text}")
    return response.json()


def _pull(client: Any, cursor: str = "0") -> dict[str, Any]:
    response = client.get("/v1/sync/pull", params={"cursor": cursor})
    if response.status_code != 200:
        raise AssertionError(f"sync pull returned {response.status_code}: {response.text}")
    return response.json()


def _case_idempotency_replay(client: Any, row: dict[str, Any]) -> None:
    receipt_id = row.get("receipt_id", "sync-eval-idempotency")
    key = row.get("idempotency_key", "sync-eval-idempotency-key")
    first = _push(
        client,
        [_expense_mutation(idempotency_key=key, receipt_id=receipt_id, merchant="Original")],
    )
    duplicate = _push(
        client,
        [_expense_mutation(idempotency_key=key, receipt_id=f"{receipt_id}-duplicate")],
    )
    if duplicate["results"] != first["results"]:
        raise AssertionError("Duplicate idempotency key did not replay the original result.")
    pulled = _pull(client)
    ids = [change["id"] for change in pulled["changes"]]
    if receipt_id not in ids or f"{receipt_id}-duplicate" in ids:
        raise AssertionError("Duplicate idempotency replay created an extra receipt.")


def _case_unsupported_entities(client: Any, row: dict[str, Any]) -> None:
    payload = _push(
        client,
        [
            {
                "idempotency_key": "sync-eval-budget-unsupported",
                "entity": "budget",
                "operation": "create",
                "occurred_at": "2026-05-01T00:00:00Z",
                "payload": {"id": "budget-1"},
            },
            {
                "idempotency_key": "sync-eval-category-unsupported",
                "entity": "category",
                "operation": "update",
                "occurred_at": "2026-05-01T00:00:00Z",
                "payload": {"id": "category-1"},
            },
        ],
    )
    codes = {result.get("code") for result in payload["results"]}
    if payload["accepted"] != 0 or payload["rejected"] != 2:
        raise AssertionError("Unsupported entity batch did not reject per mutation.")
    if codes != {"unsupported_entity_phase4"}:
        raise AssertionError(f"Unexpected unsupported entity rejection codes: {codes}")


def _case_order_retry_storm(client: Any, row: dict[str, Any]) -> None:
    receipt_id = row.get("receipt_id", "sync-eval-order")
    create = _expense_mutation(
        idempotency_key="sync-eval-order-create",
        receipt_id=receipt_id,
        merchant="Create Merchant",
        total_amount="10.00",
    )
    update = _expense_mutation(
        idempotency_key="sync-eval-order-update",
        receipt_id=receipt_id,
        operation="update",
        merchant="Updated Merchant",
        total_amount="20.00",
    )
    _push(client, [create, update, create, update])
    pulled = _pull(client)
    matching = [change for change in pulled["changes"] if change["id"] == receipt_id]
    if len(matching) != 1:
        raise AssertionError("Retry storm produced duplicate pull changes.")
    payload = matching[0]["payload"]
    if payload["merchant"] != "Updated Merchant" or payload["total_amount"] != "20.00":
        raise AssertionError("Retry storm did not converge to deterministic latest state.")


def _case_delete_tombstone(client: Any, row: dict[str, Any]) -> None:
    receipt_id = row.get("receipt_id", "sync-eval-delete")
    _push(
        client,
        [_expense_mutation(idempotency_key="sync-eval-delete-create", receipt_id=receipt_id)],
    )
    _push(
        client,
        [
            _expense_mutation(
                idempotency_key="sync-eval-delete-delete",
                receipt_id=receipt_id,
                operation="delete",
            )
        ],
    )
    pulled = _pull(client)
    tombstones = [
        change
        for change in pulled["changes"]
        if change["id"] == receipt_id and change["operation"] == "delete"
    ]
    if len(tombstones) != 1:
        raise AssertionError("Delete did not produce exactly one tombstone.")
    if tombstones[0]["payload"] is not None:
        raise AssertionError("Delete tombstone should not include an upsert payload.")


CASES = {
    "idempotency_replay": _case_idempotency_replay,
    "unsupported_entities": _case_unsupported_entities,
    "order_retry_storm": _case_order_retry_storm,
    "delete_tombstone": _case_delete_tombstone,
}


def run_eval(dataset_path: Path, output_dir: Path) -> int:
    if not dataset_path.exists():
        print(f"[sync-eval] dataset not found: {dataset_path}")
        return 2

    rows = load_jsonl(dataset_path)
    counts = SyncEvalCounts()
    failures: list[dict[str, str]] = []
    db_path = output_dir / f"_tmp_sync_eval_{dataset_path.stem}.db"
    with isolated_test_client(db_path) as client:
        for row in rows:
            counts.total += 1
            case_name = row.get("case")
            case = CASES.get(case_name)
            if case is None:
                raise ValueError(f"Unknown sync eval case: {case_name}")
            try:
                case(client, row)
                counts.passed += 1
            except AssertionError as exc:
                counts.failed += 1
                failures.append({"id": str(row.get("id", counts.total)), "error": str(exc)})
                print(f"[sync-eval] failed id={row.get('id', counts.total)} error={exc}")

    metrics = {
        "pass_rate": rate_string(counts.passed, counts.total),
        "failed": counts.failed,
    }
    artifact_payload = {
        "mode": "sync_eval",
        "dataset": str(dataset_path),
        "timestamp_utc": datetime.now(tz=UTC).isoformat(),
        "commit_sha": load_commit_sha(),
        "counts": counts.__dict__,
        "metrics": metrics,
        "failures": failures,
    }
    json_path, txt_path = write_artifact(
        output_dir=output_dir,
        dataset_path=dataset_path,
        mode="sync_eval",
        payload=artifact_payload,
    )
    print(f"[sync-eval] dataset={dataset_path}")
    print(f"[sync-eval] artifacts_json={json_path}")
    print(f"[sync-eval] artifacts_summary={txt_path}")
    print(f"[sync-eval] total={counts.total}")
    for key, value in metrics.items():
        print(f"[sync-eval] {key}={value}")
    return 1 if counts.failed else 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Run SnapLedger sync determinism evals.")
    parser.add_argument("--dataset", required=True, help="Path to JSONL sync eval dataset.")
    parser.add_argument(
        "--output-dir",
        default="eval_artifacts",
        help="Directory for timestamped JSON and text summaries.",
    )
    args = parser.parse_args()
    return run_eval(Path(args.dataset), Path(args.output_dir))


if __name__ == "__main__":
    raise SystemExit(main())
