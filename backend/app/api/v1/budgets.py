from typing import Annotated

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.api.error_handlers import to_http_exception
from app.core.errors import DomainError
from app.db.session import get_db
from app.schemas.budget import BudgetListResponse, BudgetRead, BudgetWrite
from app.services.budget_service import BudgetService

router = APIRouter(prefix="/budgets", tags=["budgets"])


@router.get("", response_model=BudgetListResponse)
def list_budgets(db: Annotated[Session, Depends(get_db)]) -> BudgetListResponse:
    try:
        return BudgetService.list(db)
    except DomainError as exc:
        raise to_http_exception(exc) from exc


@router.post("", response_model=BudgetRead)
def upsert_budget(
    payload: BudgetWrite,
    db: Annotated[Session, Depends(get_db)],
) -> BudgetRead:
    try:
        return BudgetService.upsert(db, payload)
    except DomainError as exc:
        raise to_http_exception(exc) from exc
