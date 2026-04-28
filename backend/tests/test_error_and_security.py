from __future__ import annotations

import pytest
from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient

from app.api.error_handlers import (
    http_exception_handler,
    unhandled_exception_handler,
    validation_exception_handler,
)
from app.core.config import get_settings
from app.core.middleware import (
    ApiKeyMiddleware,
    HttpsEnforcementMiddleware,
    InMemoryRateLimitMiddleware,
)


@pytest.fixture(autouse=True)
def _reset_settings_cache() -> None:
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def _build_test_app() -> FastAPI:
    app = FastAPI()
    app.add_exception_handler(HTTPException, http_exception_handler)
    from fastapi.exceptions import RequestValidationError

    app.add_exception_handler(RequestValidationError, validation_exception_handler)
    app.add_exception_handler(Exception, unhandled_exception_handler)
    app.add_middleware(ApiKeyMiddleware)
    app.add_middleware(HttpsEnforcementMiddleware)
    app.add_middleware(InMemoryRateLimitMiddleware)

    @app.get("/ok")
    def ok() -> dict[str, bool]:
        return {"ok": True}

    @app.get("/boom")
    def boom() -> dict[str, bool]:
        raise RuntimeError("unexpected")

    return app


def test_http_exception_is_wrapped_in_error_envelope() -> None:
    app = _build_test_app()

    @app.get("/missing")
    def missing() -> None:
        raise HTTPException(status_code=404, detail="Not here")

    client = TestClient(app)
    response = client.get("/missing")
    assert response.status_code == 404
    payload = response.json()
    assert payload["error"]["code"] == "http_error"
    assert payload["error"]["message"] == "Not here"


def test_validation_error_is_wrapped_in_error_envelope() -> None:
    app = _build_test_app()

    @app.post("/validate")
    def validate(payload: dict[str, int]) -> dict[str, int]:
        return payload

    client = TestClient(app)
    response = client.post("/validate", json={"bad": "type"})
    assert response.status_code == 422
    payload = response.json()
    assert payload["error"]["code"] == "validation_error"


def test_api_key_middleware_blocks_without_key(monkeypatch) -> None:
    monkeypatch.setenv("REQUIRE_API_KEY", "true")
    monkeypatch.setenv("API_KEY", "secret")
    get_settings.cache_clear()

    client = TestClient(_build_test_app())
    response = client.get("/ok")
    assert response.status_code == 401

    allowed = client.get("/ok", headers={"x-api-key": "secret"})
    assert allowed.status_code == 200

def test_rate_limit_middleware_blocks_after_threshold(monkeypatch) -> None:
    monkeypatch.setenv("RATE_LIMIT_ENABLED", "true")
    monkeypatch.setenv("RATE_LIMIT_REQUESTS", "2")
    monkeypatch.setenv("RATE_LIMIT_WINDOW_SECONDS", "60")
    get_settings.cache_clear()

    client = TestClient(_build_test_app())
    assert client.get("/ok").status_code == 200
    assert client.get("/ok").status_code == 200
    blocked = client.get("/ok")
    assert blocked.status_code == 429

def test_https_enforcement_blocks_http(monkeypatch) -> None:
    monkeypatch.setenv("ENFORCE_HTTPS", "true")
    get_settings.cache_clear()

    client = TestClient(_build_test_app(), base_url="http://testserver")
    blocked = client.get("/ok")
    assert blocked.status_code == 400
