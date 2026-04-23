from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_receipt_create_rejects_unknown_field() -> None:
    response = client.post(
        "/v1/receipts",
        json={
            "source": "manual",
            "merchant": "Coffee Shop",
            "expense_date": "2026-04-24",
            "total_amount": "125.00",
            "items": [],
            "unexpected": "should-fail",
        },
    )
    assert response.status_code == 422


def test_receipt_create_accepts_valid_shape_then_returns_phase_not_implemented() -> None:
    response = client.post(
        "/v1/receipts",
        json={
            "source": "manual",
            "merchant": "Coffee Shop",
            "expense_date": "2026-04-24",
            "total_amount": "125.00",
            "items": [],
        },
    )
    assert response.status_code == 501
    assert "Phase 1" in response.json()["detail"]


def test_receipt_create_rejects_invalid_source_enum() -> None:
    response = client.post(
        "/v1/receipts",
        json={
            "source": "ai",
            "merchant": "Coffee Shop",
            "expense_date": "2026-04-24",
            "total_amount": "125.00",
            "items": [],
        },
    )
    assert response.status_code == 422


def test_manual_entries_alias_is_exposed() -> None:
    response = client.post("/v1/manual-entries")
    assert response.status_code == 200
    assert "Use POST /v1/receipts" in response.json()["detail"]


def test_categories_returns_seeded_shape() -> None:
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
