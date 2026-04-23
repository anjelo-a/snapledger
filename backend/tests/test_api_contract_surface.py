from fastapi.testclient import TestClient

from app.main import app


client = TestClient(app)


EXPECTED_PATHS = {
    "/v1/receipts",
    "/v1/receipts/{receipt_id}",
    "/v1/receipts/process",
    "/v1/manual-entries",
    "/v1/categories",
    "/v1/categories/{category_id}",
    "/v1/budgets",
    "/v1/dashboard",
    "/v1/insights/generate",
    "/v1/sync/push",
    "/v1/sync/pull",
}


def test_openapi_contains_phase0_contract_paths() -> None:
    response = client.get("/openapi.json")
    assert response.status_code == 200
    paths = set(response.json()["paths"].keys())
    assert EXPECTED_PATHS.issubset(paths)
