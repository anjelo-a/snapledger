from typing import Annotated

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.api.error_handlers import to_http_exception
from app.core.errors import DomainError
from app.db.session import get_db
from app.schemas.insight import (
    InsightChatRequest,
    InsightChatResponse,
    InsightGenerateRequest,
    InsightGenerateResponse,
)
from app.services.insight_service import InsightService

router = APIRouter(prefix="/insights", tags=["insights"])


@router.post("/generate", response_model=InsightGenerateResponse)
def generate_insight(
    payload: InsightGenerateRequest,
    db: Annotated[Session, Depends(get_db)],
) -> InsightGenerateResponse:
    try:
        return InsightService.generate(db, payload)
    except DomainError as exc:
        raise to_http_exception(exc) from exc


@router.post("/chat", response_model=InsightChatResponse)
def chat_insight(
    payload: InsightChatRequest,
    db: Annotated[Session, Depends(get_db)],
) -> InsightChatResponse:
    try:
        return InsightService.chat(db, payload)
    except DomainError as exc:
        raise to_http_exception(exc) from exc
