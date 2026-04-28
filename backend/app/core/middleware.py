from __future__ import annotations

import time
from collections import defaultdict, deque

from fastapi import Request
from fastapi.responses import JSONResponse
from starlette.middleware.base import BaseHTTPMiddleware

from app.core.config import get_settings


class ApiKeyMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        settings = get_settings()
        if not settings.require_api_key:
            return await call_next(request)

        if request.url.path == "/health":
            return await call_next(request)

        configured_key = settings.api_key
        request_key = request.headers.get("x-api-key")
        if not configured_key or request_key != configured_key:
            return JSONResponse(
                status_code=401,
                content={"error": {"code": "http_error", "message": "Unauthorized"}},
            )

        return await call_next(request)


class HttpsEnforcementMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        settings = get_settings()
        if request.url.path == "/health":
            return await call_next(request)
        if settings.enforce_https and request.url.scheme != "https":
            return JSONResponse(
                status_code=400,
                content={"error": {"code": "http_error", "message": "HTTPS is required"}},
            )
        return await call_next(request)


class InMemoryRateLimitMiddleware(BaseHTTPMiddleware):
    _windows: dict[str, deque[float]] = defaultdict(deque)

    async def dispatch(self, request: Request, call_next):
        settings = get_settings()
        if not settings.rate_limit_enabled:
            return await call_next(request)
        if request.url.path == "/health":
            return await call_next(request)

        client_host = request.client.host if request.client else "unknown"
        key = f"{client_host}:{request.url.path}"
        now = time.time()
        window = self._windows[key]

        while window and now - window[0] > settings.rate_limit_window_seconds:
            window.popleft()

        if len(window) >= settings.rate_limit_requests:
            return JSONResponse(
                status_code=429,
                content={"error": {"code": "http_error", "message": "Rate limit exceeded"}},
            )

        window.append(now)
        return await call_next(request)
