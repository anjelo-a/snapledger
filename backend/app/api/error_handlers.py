from __future__ import annotations

from fastapi import HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.core.errors import (
    ConflictError,
    DomainError,
    InvalidOperationError,
    NotFoundError,
    ServiceUnavailableError,
)


def to_http_exception(error: DomainError) -> HTTPException:
    if isinstance(error, NotFoundError):
        return HTTPException(status_code=404, detail=error.message)
    if isinstance(error, ConflictError):
        return HTTPException(status_code=409, detail=error.message)
    if isinstance(error, InvalidOperationError):
        return HTTPException(status_code=400, detail=error.message)
    if isinstance(error, ServiceUnavailableError):
        return HTTPException(status_code=503, detail=error.message)
    return HTTPException(status_code=500, detail=error.message)


def _error_payload(*, code: str, message: str, details: object | None = None) -> dict[str, object]:
    payload: dict[str, object] = {
        "error": {
            "code": code,
            "message": message,
        }
    }
    if details is not None:
        payload["error"]["details"] = details
    return payload


def _json_serializable_validation_details(details: object) -> object:
    if isinstance(details, BaseException):
        return str(details)
    if isinstance(details, dict):
        return {
            str(key): _json_serializable_validation_details(value)
            for key, value in details.items()
        }
    if isinstance(details, list | tuple):
        return [_json_serializable_validation_details(value) for value in details]
    return details


async def http_exception_handler(_: Request, exc: HTTPException) -> JSONResponse:
    detail = exc.detail if isinstance(exc.detail, str) else "Request failed"
    return JSONResponse(
        status_code=exc.status_code,
        content=_error_payload(code="http_error", message=detail),
    )


async def validation_exception_handler(_: Request, exc: RequestValidationError) -> JSONResponse:
    return JSONResponse(
        status_code=422,
        content=_error_payload(
            code="validation_error",
            message="Request validation failed",
            details=_json_serializable_validation_details(exc.errors()),
        ),
    )


async def unhandled_exception_handler(_: Request, exc: Exception) -> JSONResponse:
    return JSONResponse(
        status_code=500,
        content=_error_payload(code="internal_error", message=str(exc) or "Internal server error"),
    )
