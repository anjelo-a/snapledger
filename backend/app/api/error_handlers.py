from __future__ import annotations

from fastapi import HTTPException

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
