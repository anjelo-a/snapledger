from __future__ import annotations

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
    try:
        yield TestClient(app)
    finally:
        app.dependency_overrides.clear()
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
