from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


EXPECTED_PATHS = {
    "/v1/receipts",
    "/v1/receipts/confirm",
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

EXPECTED_METHODS = {
    "/v1/receipts": {"get", "post"},
    "/v1/receipts/confirm": {"post"},
    "/v1/receipts/{receipt_id}": {"get", "patch", "delete"},
    "/v1/receipts/process": {"post"},
    "/v1/manual-entries": {"post"},
    "/v1/categories": {"get", "post"},
    "/v1/categories/{category_id}": {"patch"},
    "/v1/budgets": {"get", "post"},
    "/v1/dashboard": {"get"},
    "/v1/insights/generate": {"post"},
    "/v1/sync/push": {"post"},
    "/v1/sync/pull": {"get"},
}


def test_openapi_contains_phase0_contract_paths() -> None:
    response = client.get("/openapi.json")
    assert response.status_code == 200
    paths = set(response.json()["paths"].keys())
    assert EXPECTED_PATHS.issubset(paths)


def test_openapi_contains_expected_methods_per_path() -> None:
    response = client.get("/openapi.json")
    assert response.status_code == 200
    paths = response.json()["paths"]
    for path, methods in EXPECTED_METHODS.items():
        assert path in paths
        assert methods.issubset(set(paths[path].keys()))
