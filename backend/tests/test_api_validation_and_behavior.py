from __future__ import annotations

import base64
import json
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker

from app.core.category_seeds import DEFAULT_CATEGORY_SEEDS
from app.db.base import Base
from app.db.session import get_db
from app.main import app
from app.models.category import Category
from app.models.expense import Expense


@pytest.fixture
def client(tmp_path: Path) -> TestClient:
    db_path = tmp_path / "test_api.db"
    engine = create_engine(
        f"sqlite:///{db_path}",
        connect_args={"check_same_thread": False},
        future=True,
    )
    TestingSessionLocal = sessionmaker(bind=engine, autoflush=False, autocommit=False, future=True)
    Base.metadata.create_all(bind=engine)
    with TestingSessionLocal() as seed_db:
        for idx, name in enumerate(DEFAULT_CATEGORY_SEEDS, start=1):
            seed_db.add(
                Category(
                    id=f"seed-{idx}",
                    name=name,
                    is_default=True,
                    is_archived=False,
                )
            )
        seed_db.commit()

    def override_get_db() -> Session:
        db = TestingSessionLocal()
        try:
            yield db
        finally:
            db.close()

    app.dependency_overrides[get_db] = override_get_db
    app.state.testing_session_local = TestingSessionLocal
    try:
        yield TestClient(app)
    finally:
        app.dependency_overrides.clear()
        del app.state.testing_session_local
        Base.metadata.drop_all(bind=engine)
        engine.dispose()


def _create_receipt_payload(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "source": "manual",
        "merchant": "Coffee Shop",
        "expense_date": "2026-04-24",
        "total_amount": "125.00",
        "currency": "PHP",
        "items": [
            {
                "name": "Latte",
                "amount": "125.00",
            }
        ],
    }
    payload.update(overrides)
    return payload


def _test_db_session(client: TestClient) -> Session:
    return client.app.state.testing_session_local()


def _sync_pull_cursor(updated_at: datetime, receipt_id: str) -> str:
    payload = {
        "updated_at": updated_at.astimezone(UTC).isoformat(),
        "id": receipt_id,
    }
    raw = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    return base64.urlsafe_b64encode(raw).decode("utf-8")


def _set_expense_updated_at(
    client: TestClient,
    receipt_id: str,
    updated_at: datetime,
) -> None:
    db = _test_db_session(client)
    try:
        expense = db.get(Expense, receipt_id)
        assert expense is not None
        expense.updated_at = updated_at.astimezone(UTC)
        db.commit()
    finally:
        db.close()


def test_receipt_create_rejects_unknown_field(client: TestClient) -> None:
    response = client.post(
        "/v1/receipts",
        json={
            **_create_receipt_payload(),
            "unexpected": "should-fail",
        },
    )
    assert response.status_code == 422


def test_receipt_create_accepts_valid_shape_and_persists(client: TestClient) -> None:
    response = client.post("/v1/receipts", json=_create_receipt_payload())
    assert response.status_code == 200
    payload = response.json()
    assert payload["merchant"] == "Coffee Shop"
    assert payload["source"] == "manual"
    assert len(payload["items"]) == 1


def test_receipt_create_rejects_invalid_source_enum(client: TestClient) -> None:
    response = client.post(
        "/v1/receipts",
        json=_create_receipt_payload(source="ai"),
    )
    assert response.status_code == 422


def test_manual_entries_alias_creates_receipt(client: TestClient) -> None:
    response = client.post("/v1/manual-entries", json=_create_receipt_payload(items=[]))
    assert response.status_code == 200
    payload = response.json()
    assert payload["merchant"] == "Coffee Shop"
    assert payload["source"] == "manual"


def test_manual_entries_alias_forces_manual_source(client: TestClient) -> None:
    response = client.post(
        "/v1/manual-entries",
        json=_create_receipt_payload(source="scan", merchant="Scan Payload"),
    )
    assert response.status_code == 200
    payload = response.json()
    assert payload["merchant"] == "Scan Payload"
    assert payload["source"] == "manual"


def test_manual_entries_alias_matches_direct_receipt_shape(client: TestClient) -> None:
    direct = client.post("/v1/receipts", json=_create_receipt_payload(items=[]))
    alias = client.post("/v1/manual-entries", json=_create_receipt_payload(items=[]))
    assert direct.status_code == 200
    assert alias.status_code == 200
    direct_json = direct.json()
    alias_json = alias.json()
    for field in (
        "source",
        "merchant",
        "expense_date",
        "total_amount",
        "currency",
        "category_id",
        "notes",
    ):
        assert alias_json[field] == direct_json[field]


def test_receipts_confirm_alias_creates_receipt(client: TestClient) -> None:
    response = client.post("/v1/receipts/confirm", json=_create_receipt_payload(items=[]))
    assert response.status_code == 200
    payload = response.json()
    assert payload["merchant"] == "Coffee Shop"
    assert payload["source"] == "scan"


def test_receipts_confirm_alias_forces_scan_source(client: TestClient) -> None:
    response = client.post(
        "/v1/receipts/confirm",
        json=_create_receipt_payload(source="manual", merchant="Reviewed Scan"),
    )
    assert response.status_code == 200
    payload = response.json()
    assert payload["merchant"] == "Reviewed Scan"
    assert payload["source"] == "scan"


def test_receipts_confirm_alias_matches_direct_receipt_shape(client: TestClient) -> None:
    direct = client.post("/v1/receipts", json=_create_receipt_payload(source="scan", items=[]))
    alias = client.post("/v1/receipts/confirm", json=_create_receipt_payload(items=[]))
    assert direct.status_code == 200
    assert alias.status_code == 200
    direct_json = direct.json()
    alias_json = alias.json()
    for field in (
        "source",
        "merchant",
        "expense_date",
        "total_amount",
        "currency",
        "category_id",
        "notes",
    ):
        assert alias_json[field] == direct_json[field]


def test_receipt_process_returns_locked_phase2_candidate_shape(client: TestClient) -> None:
    response = client.post(
        "/v1/receipts/process",
        json={
            "ocr_lines": ["ACME MART", "TOTAL 123.45"],
            "locale": "en-PH",
            "currency_hint": "PHP",
        },
    )
    assert response.status_code == 200
    payload = response.json()
    assert {
        "merchant",
        "expense_date",
        "total_amount",
        "items",
        "warnings",
        "warning_codes",
        "field_confidence",
    }.issubset(payload.keys())
    assert isinstance(payload["items"], list)
    assert isinstance(payload["warnings"], list)
    assert isinstance(payload["warning_codes"], list)
    assert payload["field_confidence"] is None or isinstance(payload["field_confidence"], dict)


def test_receipt_process_rejects_unknown_request_field(client: TestClient) -> None:
    response = client.post(
        "/v1/receipts/process",
        json={
            "ocr_lines": ["ACME MART"],
            "timestamp": "2026-04-29T00:00:00Z",
        },
    )
    assert response.status_code == 422


def test_receipt_process_rejects_blank_ocr_line(client: TestClient) -> None:
    response = client.post(
        "/v1/receipts/process",
        json={
            "ocr_lines": ["   "],
        },
    )
    assert response.status_code == 422
    payload = response.json()
    assert payload["error"]["code"] == "validation_error"
    ctx = payload["error"]["details"][0].get("ctx", {})
    assert isinstance(ctx.get("error"), str)


def test_receipt_process_rejects_malformed_ocr_lines_payload(client: TestClient) -> None:
    response = client.post(
        "/v1/receipts/process",
        json={
            "ocr_lines": "ACME MART",
        },
    )
    assert response.status_code == 422
    payload = response.json()
    assert payload["error"]["code"] == "validation_error"


def test_receipt_process_rejects_overlong_ocr_line(client: TestClient) -> None:
    response = client.post(
        "/v1/receipts/process",
        json={
            "ocr_lines": ["A" * 501],
        },
    )
    assert response.status_code == 422
    payload = response.json()
    assert payload["error"]["code"] == "validation_error"


def test_receipt_process_rejects_overlong_total_text(client: TestClient) -> None:
    response = client.post(
        "/v1/receipts/process",
        json={
            "ocr_lines": ["A" * 500 for _ in range(41)],
        },
    )
    assert response.status_code == 422
    payload = response.json()
    assert payload["error"]["code"] == "validation_error"


def _sync_expense_create_mutation(
    *,
    idempotency_key: str = "sync-create-001",
    receipt_id: str = "client-receipt-001",
    merchant: str = "Synced Cafe",
) -> dict[str, object]:
    return {
        "idempotency_key": idempotency_key,
        "entity": "expense",
        "operation": "create",
        "occurred_at": "2026-05-01T00:00:00Z",
        "payload": {
            "id": receipt_id,
            "source": "scan",
            "merchant": merchant,
            "expense_date": "2026-05-01",
            "total_amount": "42.50",
            "currency": "PHP",
            "items": [
                {
                    "name": "Coffee",
                    "amount": "42.50",
                }
            ],
        },
    }


def test_sync_push_creates_receipt_with_client_id(client: TestClient) -> None:
    receipt_id = "client-receipt-create"
    response = client.post(
        "/v1/sync/push",
        json={
            "mutations": [
                _sync_expense_create_mutation(
                    idempotency_key="sync-create-client-id",
                    receipt_id=receipt_id,
                )
            ],
        },
    )

    assert response.status_code == 200
    payload = response.json()
    assert payload["accepted"] == 1
    assert payload["rejected"] == 0
    assert payload["results"][0]["status"] == "accepted"
    assert payload["results"][0]["entity_id"] == receipt_id

    created = client.get(f"/v1/receipts/{receipt_id}")
    assert created.status_code == 200
    assert created.json()["id"] == receipt_id
    assert created.json()["merchant"] == "Synced Cafe"


def test_sync_push_duplicate_idempotency_key_returns_original_result(
    client: TestClient,
) -> None:
    first = client.post(
        "/v1/sync/push",
        json={
            "mutations": [
                _sync_expense_create_mutation(
                    idempotency_key="sync-duplicate-001",
                    receipt_id="client-receipt-original",
                    merchant="Original Merchant",
                )
            ],
        },
    )
    assert first.status_code == 200

    duplicate = client.post(
        "/v1/sync/push",
        json={
            "mutations": [
                _sync_expense_create_mutation(
                    idempotency_key="sync-duplicate-001",
                    receipt_id="client-receipt-second",
                    merchant="Second Merchant",
                )
            ],
        },
    )

    assert duplicate.status_code == 200
    assert duplicate.json()["results"] == first.json()["results"]
    original = client.get("/v1/receipts/client-receipt-original")
    second = client.get("/v1/receipts/client-receipt-second")
    assert original.status_code == 200
    assert second.status_code == 404


def test_sync_push_updates_receipt_fields_and_items(client: TestClient) -> None:
    receipt_id = "client-receipt-update"
    create = client.post(
        "/v1/sync/push",
        json={
            "mutations": [
                _sync_expense_create_mutation(
                    idempotency_key="sync-update-create",
                    receipt_id=receipt_id,
                )
            ],
        },
    )
    assert create.status_code == 200

    update = client.post(
        "/v1/sync/push",
        json={
            "mutations": [
                {
                    "idempotency_key": "sync-update-001",
                    "entity": "expense",
                    "operation": "update",
                    "occurred_at": "2026-05-01T00:00:00Z",
                    "payload": {
                        "id": receipt_id,
                        "source": "scan",
                        "merchant": "Updated Cafe",
                        "expense_date": "2026-05-02",
                        "total_amount": "88.00",
                        "currency": "PHP",
                        "items": [{"name": "Brunch", "amount": "88.00"}],
                    },
                }
            ],
        },
    )

    assert update.status_code == 200
    assert update.json()["accepted"] == 1
    updated = client.get(f"/v1/receipts/{receipt_id}")
    assert updated.status_code == 200
    payload = updated.json()
    assert payload["merchant"] == "Updated Cafe"
    assert payload["expense_date"] == "2026-05-02"
    assert payload["total_amount"] == "88.00"
    assert len(payload["items"]) == 1
    assert payload["items"][0]["name"] == "Brunch"
    assert payload["items"][0]["amount"] == "88.00"


def test_sync_push_rejects_unsupported_budget_and_category_per_mutation(
    client: TestClient,
) -> None:
    response = client.post(
        "/v1/sync/push",
        json={
            "mutations": [
                {
                    "idempotency_key": "sync-budget-001",
                    "entity": "budget",
                    "operation": "create",
                    "occurred_at": "2026-05-01T00:00:00Z",
                    "payload": {"id": "budget-1"},
                },
                {
                    "idempotency_key": "sync-category-001",
                    "entity": "category",
                    "operation": "update",
                    "occurred_at": "2026-05-01T00:00:00Z",
                    "payload": {"id": "category-1"},
                },
            ],
        },
    )

    assert response.status_code == 200
    payload = response.json()
    assert payload["accepted"] == 0
    assert payload["rejected"] == 2
    assert {result["code"] for result in payload["results"]} == {
        "unsupported_entity_phase4"
    }


def test_sync_push_delete_uses_soft_delete_behavior(client: TestClient) -> None:
    receipt_id = "client-receipt-delete"
    create = client.post(
        "/v1/sync/push",
        json={
            "mutations": [
                _sync_expense_create_mutation(
                    idempotency_key="sync-delete-create",
                    receipt_id=receipt_id,
                )
            ],
        },
    )
    assert create.status_code == 200

    delete = client.post(
        "/v1/sync/push",
        json={
            "mutations": [
                {
                    "idempotency_key": "sync-delete-001",
                    "entity": "expense",
                    "operation": "delete",
                    "occurred_at": "2026-05-01T00:00:00Z",
                    "payload": {"id": receipt_id},
                }
            ],
        },
    )

    assert delete.status_code == 200
    assert delete.json()["accepted"] == 1
    assert delete.json()["results"][0]["status"] == "accepted"
    assert client.get(f"/v1/receipts/{receipt_id}").status_code == 404


def test_sync_push_invalid_expense_payload_returns_4xx(client: TestClient) -> None:
    response = client.post(
        "/v1/sync/push",
        json={
            "mutations": [
                {
                    "idempotency_key": "sync-invalid-001",
                    "entity": "expense",
                    "operation": "create",
                    "occurred_at": "2026-05-01T00:00:00Z",
                    "payload": {
                        "id": "client-invalid",
                        "source": "scan",
                        "expense_date": "2026-05-01",
                        "total_amount": "42.50",
                    },
                }
            ],
        },
    )

    assert response.status_code == 422
    assert response.json()["error"]["code"] == "validation_error"


def test_sync_pull_returns_upsert_changes_after_cursor(client: TestClient) -> None:
    first_id = "pull-upsert-001"
    second_id = "pull-upsert-002"
    created = client.post(
        "/v1/sync/push",
        json={
            "mutations": [
                _sync_expense_create_mutation(
                    idempotency_key="pull-upsert-create-001",
                    receipt_id=first_id,
                    merchant="First Pull Merchant",
                ),
                _sync_expense_create_mutation(
                    idempotency_key="pull-upsert-create-002",
                    receipt_id=second_id,
                    merchant="Second Pull Merchant",
                ),
            ],
        },
    )
    assert created.status_code == 200

    first_updated_at = datetime(2026, 5, 1, 0, 0, tzinfo=UTC)
    second_updated_at = datetime(2026, 5, 1, 0, 5, tzinfo=UTC)
    _set_expense_updated_at(client, first_id, first_updated_at)
    _set_expense_updated_at(client, second_id, second_updated_at)

    response = client.get(
        "/v1/sync/pull",
        params={"cursor": _sync_pull_cursor(first_updated_at, first_id)},
    )

    assert response.status_code == 200
    payload = response.json()
    assert payload["has_more"] is False
    assert len(payload["changes"]) == 1
    change = payload["changes"][0]
    assert change["entity"] == "expense"
    assert change["operation"] == "upsert"
    assert change["id"] == second_id
    assert change["payload"]["merchant"] == "Second Pull Merchant"
    assert change["payload"]["items"][0]["name"] == "Coffee"


def test_sync_pull_returns_delete_tombstones_after_cursor(client: TestClient) -> None:
    receipt_id = "pull-delete-001"
    created = client.post(
        "/v1/sync/push",
        json={
            "mutations": [
                _sync_expense_create_mutation(
                    idempotency_key="pull-delete-create",
                    receipt_id=receipt_id,
                )
            ],
        },
    )
    assert created.status_code == 200

    created_updated_at = datetime(2026, 5, 1, 1, 0, tzinfo=UTC)
    deleted_updated_at = datetime(2026, 5, 1, 1, 5, tzinfo=UTC)
    _set_expense_updated_at(client, receipt_id, created_updated_at)

    deleted = client.post(
        "/v1/sync/push",
        json={
            "mutations": [
                {
                    "idempotency_key": "pull-delete-mutation",
                    "entity": "expense",
                    "operation": "delete",
                    "occurred_at": "2026-05-01T01:05:00Z",
                    "payload": {"id": receipt_id},
                }
            ],
        },
    )
    assert deleted.status_code == 200
    _set_expense_updated_at(client, receipt_id, deleted_updated_at)

    response = client.get(
        "/v1/sync/pull",
        params={"cursor": _sync_pull_cursor(created_updated_at, receipt_id)},
    )

    assert response.status_code == 200
    payload = response.json()
    assert payload["has_more"] is False
    assert len(payload["changes"]) == 1
    change = payload["changes"][0]
    assert change["entity"] == "expense"
    assert change["operation"] == "delete"
    assert change["id"] == receipt_id
    assert change["payload"] is None


def test_sync_pull_cursor_pagination_is_deterministic(client: TestClient) -> None:
    mutations = [
        _sync_expense_create_mutation(
            idempotency_key=f"pull-page-create-{idx:03d}",
            receipt_id=f"pull-page-{idx:03d}",
            merchant=f"Pull Page {idx:03d}",
        )
        for idx in range(105)
    ]
    created = client.post("/v1/sync/push", json={"mutations": mutations})
    assert created.status_code == 200

    shared_updated_at = datetime(2026, 5, 1, 2, 0, tzinfo=UTC)
    db = _test_db_session(client)
    try:
        for idx in range(105):
            expense = db.get(Expense, f"pull-page-{idx:03d}")
            assert expense is not None
            expense.updated_at = shared_updated_at
        db.commit()
    finally:
        db.close()

    first_page = client.get("/v1/sync/pull")
    assert first_page.status_code == 200
    first_payload = first_page.json()
    first_ids = [change["id"] for change in first_payload["changes"]]
    assert first_payload["has_more"] is True
    assert len(first_ids) == 100
    assert first_ids == [f"pull-page-{idx:03d}" for idx in range(100)]

    second_page = client.get(
        "/v1/sync/pull",
        params={"cursor": first_payload["cursor"]},
    )
    assert second_page.status_code == 200
    second_payload = second_page.json()
    second_ids = [change["id"] for change in second_payload["changes"]]
    assert second_payload["has_more"] is False
    assert second_ids == [f"pull-page-{idx:03d}" for idx in range(100, 105)]
    assert set(first_ids).isdisjoint(second_ids)


def test_sync_pull_invalid_cursor_returns_4xx(client: TestClient) -> None:
    response = client.get("/v1/sync/pull", params={"cursor": "not-a-real-cursor"})

    assert response.status_code == 422
    assert response.json()["error"]["code"] == "validation_error"


def test_categories_returns_seeded_shape(client: TestClient) -> None:
    response = client.get("/v1/categories")
    assert response.status_code == 200
    payload = response.json()
    assert "items" in payload
    assert isinstance(payload["items"], list)
    assert len(payload["items"]) >= 10
    first = payload["items"][0]
    assert {
        "id",
        "name",
        "is_default",
        "is_archived",
        "created_at",
        "updated_at",
    }.issubset(first.keys())


def test_create_custom_category_success(client: TestClient) -> None:
    response = client.post("/v1/categories", json={"name": "Dining Out"})
    assert response.status_code == 200
    payload = response.json()
    assert payload["name"] == "Dining Out"
    assert payload["is_default"] is False
    assert payload["is_archived"] is False


def test_create_category_rejects_case_insensitive_duplicate(client: TestClient) -> None:
    created = client.post("/v1/categories", json={"name": "Dining Out"})
    assert created.status_code == 200

    duplicate = client.post("/v1/categories", json={"name": "  dining out  "})
    assert duplicate.status_code == 409


def test_create_category_rejects_duplicate_of_archived_category(client: TestClient) -> None:
    created = client.post("/v1/categories", json={"name": "ArchivedDup"})
    assert created.status_code == 200
    category_id = created.json()["id"]
    archived = client.patch(f"/v1/categories/{category_id}", json={"is_archived": True})
    assert archived.status_code == 200

    duplicate = client.post("/v1/categories", json={"name": " archiveddup "})
    assert duplicate.status_code == 409


def test_patch_custom_category_rename_and_archive(client: TestClient) -> None:
    created = client.post("/v1/categories", json={"name": "Subscriptions"})
    category_id = created.json()["id"]

    rename = client.patch(f"/v1/categories/{category_id}", json={"name": "Streaming"})
    assert rename.status_code == 200
    assert rename.json()["name"] == "Streaming"

    archive = client.patch(f"/v1/categories/{category_id}", json={"is_archived": True})
    assert archive.status_code == 200
    assert archive.json()["is_archived"] is True


def test_patch_category_rejects_duplicate_name(client: TestClient) -> None:
    created_a = client.post("/v1/categories", json={"name": "Travel"})
    created_b = client.post("/v1/categories", json={"name": "Leisure"})
    assert created_a.status_code == 200
    assert created_b.status_code == 200
    category_b_id = created_b.json()["id"]

    duplicate = client.patch(f"/v1/categories/{category_b_id}", json={"name": " travel "})
    assert duplicate.status_code == 409


def test_patch_default_category_is_rejected(client: TestClient) -> None:
    categories = client.get("/v1/categories")
    default_category = next(
        item for item in categories.json()["items"] if item["is_default"] is True
    )

    rename = client.patch(f"/v1/categories/{default_category['id']}", json={"name": "Renamed"})
    assert rename.status_code == 400

    archive = client.patch(f"/v1/categories/{default_category['id']}", json={"is_archived": True})
    assert archive.status_code == 400


def test_patch_category_rejects_whitespace_only_name(client: TestClient) -> None:
    created = client.post("/v1/categories", json={"name": "Whitespace Check"})
    assert created.status_code == 200
    category_id = created.json()["id"]
    invalid = client.patch(f"/v1/categories/{category_id}", json={"name": "   "})
    assert invalid.status_code == 422


def test_receipt_get_patch_delete_flow(client: TestClient) -> None:
    create_response = client.post("/v1/receipts", json=_create_receipt_payload())
    receipt_id = create_response.json()["id"]

    get_response = client.get(f"/v1/receipts/{receipt_id}")
    assert get_response.status_code == 200
    assert get_response.json()["id"] == receipt_id

    patch_response = client.patch(
        f"/v1/receipts/{receipt_id}",
        json={
            "merchant": "Updated Merchant",
            "items": [
                {"name": "Americano", "amount": "90.00"},
                {"name": "Cookie", "amount": "35.00"},
            ],
        },
    )
    assert patch_response.status_code == 200
    patched = patch_response.json()
    assert patched["merchant"] == "Updated Merchant"
    assert len(patched["items"]) == 2

    delete_response = client.delete(f"/v1/receipts/{receipt_id}")
    assert delete_response.status_code == 200
    assert delete_response.json()["status"] == "deleted"

    get_after_delete = client.get(f"/v1/receipts/{receipt_id}")
    assert get_after_delete.status_code == 404


def test_budget_post_and_list_roundtrip(client: TestClient) -> None:
    create = client.post(
        "/v1/budgets",
        json={
            "scope": "overall",
            "period": "monthly",
            "amount_limit": "5000.00",
            "category_id": None,
        },
    )
    assert create.status_code == 200
    payload = create.json()
    assert payload["scope"] == "overall"
    assert payload["period"] == "monthly"
    assert payload["amount_limit"] == "5000.00"
    assert payload["category_id"] is None

    listed = client.get("/v1/budgets")
    assert listed.status_code == 200
    items = listed.json()["items"]
    assert len(items) == 1
    assert items[0]["id"] == payload["id"]


def test_budget_upsert_updates_existing_budget_record(client: TestClient) -> None:
    first = client.post(
        "/v1/budgets",
        json={
            "scope": "overall",
            "period": "weekly",
            "amount_limit": "1000.00",
            "category_id": None,
        },
    )
    assert first.status_code == 200
    first_payload = first.json()

    second = client.post(
        "/v1/budgets",
        json={
            "scope": "overall",
            "period": "weekly",
            "amount_limit": "1200.00",
            "category_id": None,
        },
    )
    assert second.status_code == 200
    second_payload = second.json()
    assert second_payload["id"] == first_payload["id"]
    assert second_payload["amount_limit"] == "1200.00"

    listed = client.get("/v1/budgets")
    items = listed.json()["items"]
    assert len(items) == 1
    assert items[0]["amount_limit"] == "1200.00"


def test_budget_rejects_scope_category_mismatch(client: TestClient) -> None:
    mismatch = client.post(
        "/v1/budgets",
        json={
            "scope": "overall",
            "period": "monthly",
            "amount_limit": "1000.00",
            "category_id": "seed-1",
        },
    )
    assert mismatch.status_code == 400

    missing_category = client.post(
        "/v1/budgets",
        json={
            "scope": "category",
            "period": "monthly",
            "amount_limit": "1000.00",
            "category_id": None,
        },
    )
    assert missing_category.status_code == 400


def test_budget_rejects_inactive_or_missing_category_scope(client: TestClient) -> None:
    missing = client.post(
        "/v1/budgets",
        json={
            "scope": "category",
            "period": "monthly",
            "amount_limit": "1000.00",
            "category_id": "missing-cat",
        },
    )
    assert missing.status_code == 400

    archived = client.post("/v1/categories", json={"name": "Dormant"})
    assert archived.status_code == 200
    archived_id = archived.json()["id"]
    archived_patch = client.patch(f"/v1/categories/{archived_id}", json={"is_archived": True})
    assert archived_patch.status_code == 200

    archived_budget = client.post(
        "/v1/budgets",
        json={
            "scope": "category",
            "period": "monthly",
            "amount_limit": "1000.00",
            "category_id": archived_id,
        },
    )
    assert archived_budget.status_code == 400


def test_dashboard_threshold_levels_and_aggregates(client: TestClient) -> None:
    today = datetime.now(UTC).date()
    old_date = (today - timedelta(days=75)).isoformat()
    today_text = today.isoformat()

    groceries = client.post("/v1/categories", json={"name": "Groceries Extra"})
    assert groceries.status_code == 200
    groceries_id = groceries.json()["id"]

    client.post(
        "/v1/receipts",
        json=_create_receipt_payload(
            merchant="Recent A",
            expense_date=today_text,
            total_amount="50.00",
            category_id=groceries_id,
            items=[],
        ),
    )
    client.post(
        "/v1/receipts",
        json=_create_receipt_payload(
            merchant="Recent B",
            expense_date=today_text,
            total_amount="40.00",
            category_id=groceries_id,
            items=[],
        ),
    )
    client.post(
        "/v1/receipts",
        json=_create_receipt_payload(
            merchant="Old Expense",
            expense_date=old_date,
            total_amount="30.00",
            category_id=None,
            items=[],
        ),
    )

    normal_budget = client.post(
        "/v1/budgets",
        json={
            "scope": "overall",
            "period": "monthly",
            "amount_limit": "200.00",
            "category_id": None,
        },
    )
    assert normal_budget.status_code == 200
    budget_id = normal_budget.json()["id"]

    def _dashboard_for_budget() -> dict[str, object]:
        dashboard = client.get("/v1/dashboard")
        assert dashboard.status_code == 200
        payload = dashboard.json()
        status = next(item for item in payload["budget_statuses"] if item["budget_id"] == budget_id)
        return {"status": status, "payload": payload}

    normal = _dashboard_for_budget()["status"]
    assert normal["threshold_level"] == "normal"

    warning_upsert = client.post(
        "/v1/budgets",
        json={
            "scope": "overall",
            "period": "monthly",
            "amount_limit": "128.57",
            "category_id": None,
        },
    )
    assert warning_upsert.status_code == 200
    warning = _dashboard_for_budget()["status"]
    assert warning["threshold_level"] == "warning"

    critical_upsert = client.post(
        "/v1/budgets",
        json={
            "scope": "overall",
            "period": "monthly",
            "amount_limit": "100.00",
            "category_id": None,
        },
    )
    assert critical_upsert.status_code == 200
    critical = _dashboard_for_budget()["status"]
    assert critical["threshold_level"] == "critical"

    exceeded_upsert = client.post(
        "/v1/budgets",
        json={
            "scope": "overall",
            "period": "monthly",
            "amount_limit": "90.00",
            "category_id": None,
        },
    )
    assert exceeded_upsert.status_code == 200
    dashboard_payload = _dashboard_for_budget()["payload"]
    exceeded = next(
        item for item in dashboard_payload["budget_statuses"] if item["budget_id"] == budget_id
    )
    assert exceeded["threshold_level"] == "exceeded"
    assert exceeded["spent"] == "90.00"

    assert any(point["amount"] == "90.00" for point in dashboard_payload["trends"])
    assert any(
        row["category_name"] == "Groceries Extra"
        for row in dashboard_payload["category_breakdown"]
    )
    assert dashboard_payload["recent_activity"][0]["merchant"] in {"Recent A", "Recent B"}


def test_dashboard_excludes_soft_deleted_expenses(client: TestClient) -> None:
    today = datetime.now(UTC).date().isoformat()
    created = client.post(
        "/v1/receipts",
        json=_create_receipt_payload(
            merchant="Delete Me",
            expense_date=today,
            total_amount="300.00",
            items=[],
        ),
    )
    assert created.status_code == 200
    receipt_id = created.json()["id"]

    budget = client.post(
        "/v1/budgets",
        json={
            "scope": "overall",
            "period": "monthly",
            "amount_limit": "500.00",
            "category_id": None,
        },
    )
    assert budget.status_code == 200
    budget_id = budget.json()["id"]

    before_delete = client.get("/v1/dashboard")
    assert before_delete.status_code == 200
    before_status = next(
        item for item in before_delete.json()["budget_statuses"] if item["budget_id"] == budget_id
    )
    assert before_status["spent"] == "300.00"

    deleted = client.delete(f"/v1/receipts/{receipt_id}")
    assert deleted.status_code == 200

    after_delete = client.get("/v1/dashboard")
    assert after_delete.status_code == 200
    after_status = next(
        item for item in after_delete.json()["budget_statuses"] if item["budget_id"] == budget_id
    )
    assert after_status["spent"] == "0.00"


def test_receipt_list_filters_and_cursor_pagination(client: TestClient) -> None:
    client.post(
        "/v1/receipts",
        json=_create_receipt_payload(
            merchant="Alpha",
            expense_date="2026-04-20",
            total_amount="100.00",
            items=[],
        ),
    )
    client.post(
        "/v1/receipts",
        json=_create_receipt_payload(
            merchant="Beta",
            expense_date="2026-04-21",
            total_amount="200.00",
            items=[],
        ),
    )
    client.post(
        "/v1/receipts",
        json=_create_receipt_payload(
            merchant="Gamma",
            expense_date="2026-04-22",
            total_amount="300.00",
            items=[],
        ),
    )

    filtered = client.get("/v1/receipts", params={"merchant_query": "be"})
    assert filtered.status_code == 200
    filtered_items = filtered.json()["items"]
    assert len(filtered_items) == 1
    assert filtered_items[0]["merchant"] == "Beta"

    page_1 = client.get("/v1/receipts", params={"limit": 2})
    assert page_1.status_code == 200
    body_1 = page_1.json()
    assert len(body_1["items"]) == 2
    assert body_1["next_cursor"] is not None

    page_2 = client.get("/v1/receipts", params={"limit": 2, "cursor": body_1["next_cursor"]})
    assert page_2.status_code == 200
    body_2 = page_2.json()
    assert len(body_2["items"]) == 1
    assert body_2["next_cursor"] is None


def test_receipt_cursor_pagination_no_duplicate_or_skip(client: TestClient) -> None:
    created_ids: list[str] = []
    for idx in range(5):
        response = client.post(
            "/v1/receipts",
            json=_create_receipt_payload(
                merchant=f"Cursor-{idx}",
                expense_date="2026-04-24",
                total_amount=f"{100 + idx}.00",
                items=[],
            ),
        )
        assert response.status_code == 200
        created_ids.append(response.json()["id"])

    seen_ids: list[str] = []
    cursor: str | None = None
    for _ in range(6):
        params = {"limit": 2}
        if cursor is not None:
            params["cursor"] = cursor
        page = client.get("/v1/receipts", params=params)
        assert page.status_code == 200
        payload = page.json()
        seen_ids.extend(item["id"] for item in payload["items"])
        cursor = payload["next_cursor"]
        if cursor is None:
            break

    assert len(seen_ids) == len(set(seen_ids))
    assert set(created_ids).issubset(set(seen_ids))


def test_receipt_list_rejects_invalid_filter_ranges(client: TestClient) -> None:
    bad_date = client.get(
        "/v1/receipts",
        params={"date_from": "2026-04-22", "date_to": "2026-04-01"},
    )
    assert bad_date.status_code == 400

    bad_amount = client.get("/v1/receipts", params={"amount_min": "500", "amount_max": "100"})
    assert bad_amount.status_code == 400
