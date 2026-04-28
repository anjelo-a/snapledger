from __future__ import annotations

from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker

from app.db.base import Base
from app.db.session import get_db
from app.main import app


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


def test_receipt_list_rejects_invalid_filter_ranges(client: TestClient) -> None:
    bad_date = client.get(
        "/v1/receipts",
        params={"date_from": "2026-04-22", "date_to": "2026-04-01"},
    )
    assert bad_date.status_code == 400

    bad_amount = client.get("/v1/receipts", params={"amount_min": "500", "amount_max": "100"})
    assert bad_amount.status_code == 400
