from typing import Annotated

from fastapi import APIRouter, Depends, Query
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
) -> SyncPushResponse | JSONResponse:
    try:
        return SyncService.push(db, payload)
    except SyncPayloadValidationError as exc:
        return _validation_error_response(str(exc))
    except DomainError as exc:
        raise to_http_exception(exc) from exc


@router.get("/pull", response_model=SyncPullResponse)
def pull_sync(
    db: Annotated[Session, Depends(get_db)],
    cursor: str = Query(default="0"),
) -> SyncPullResponse | JSONResponse:
    try:
        return SyncService.pull(db, cursor)
    except SyncPayloadValidationError as exc:
        return _validation_error_response(str(exc))
    except DomainError as exc:
        raise to_http_exception(exc) from exc
