from typing import Annotated

from fastapi import APIRouter, Depends, Header, Query
from fastapi.responses import JSONResponse
from sqlalchemy.orm import Session

from app.api.error_handlers import to_http_exception
from app.core.errors import DomainError
from app.db.session import get_db
from app.schemas.sync import SyncPullResponse, SyncPushRequest, SyncPushResponse
from app.services.sync_service import SyncPayloadValidationError, SyncService

router = APIRouter(prefix="/sync", tags=["sync"])


def _validation_error_response(message: str) -> JSONResponse:
    return JSONResponse(
        status_code=422,
        content={
            "error": {
                "code": "validation_error",
                "message": "Request validation failed",
                "details": [{"message": message}],
            }
        },
    )


@router.post("/push", response_model=SyncPushResponse)
def push_sync(
    payload: SyncPushRequest,
    db: Annotated[Session, Depends(get_db)],
    owner_key: Annotated[str | None, Header(alias="x-sync-owner")] = None,
) -> SyncPushResponse | JSONResponse:
    try:
        return SyncService.push(db, payload, owner_key=_normalize_owner_key(owner_key))
    except SyncPayloadValidationError as exc:
        return _validation_error_response(str(exc))
    except DomainError as exc:
        raise to_http_exception(exc) from exc


@router.get("/pull", response_model=SyncPullResponse)
def pull_sync(
    db: Annotated[Session, Depends(get_db)],
    cursor: str = Query(default="0"),
    owner_key: Annotated[str | None, Header(alias="x-sync-owner")] = None,
) -> SyncPullResponse | JSONResponse:
    try:
        return SyncService.pull(db, cursor, owner_key=_normalize_owner_key(owner_key))
    except SyncPayloadValidationError as exc:
        return _validation_error_response(str(exc))
    except DomainError as exc:
        raise to_http_exception(exc) from exc


def _normalize_owner_key(owner_key: str | None) -> str:
    normalized = (owner_key or "public").strip()
    if not normalized:
        return "public"
    if len(normalized) > 191:
        raise SyncPayloadValidationError("Sync owner key must be at most 191 characters.")
    return normalized
