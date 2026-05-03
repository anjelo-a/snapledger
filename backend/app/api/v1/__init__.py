from typing import Annotated

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.api.error_handlers import to_http_exception
from app.api.v1.budgets import router as budgets_router
from app.api.v1.categories import router as categories_router
from app.api.v1.dashboard import router as dashboard_router
from app.api.v1.insights import router as insights_router
from app.api.v1.receipts import router as receipts_router
from app.api.v1.sync import router as sync_router
from app.core.errors import DomainError
from app.db.session import get_db
from app.schemas.expense import ExpenseRead, ExpenseWrite
from app.services.receipt_service import ReceiptService

api_router = APIRouter()
api_router.include_router(receipts_router)
api_router.include_router(categories_router)
api_router.include_router(budgets_router)
api_router.include_router(dashboard_router)
api_router.include_router(insights_router)
api_router.include_router(sync_router)


@api_router.post("/manual-entries", response_model=ExpenseRead)
def create_manual_entry_alias(
    payload: ExpenseWrite,
    db: Annotated[Session, Depends(get_db)],
) -> ExpenseRead:
    manual_payload = payload.model_copy(update={"source": "manual"})
    try:
        return ReceiptService.create(db, manual_payload)
    except DomainError as exc:
        raise to_http_exception(exc) from exc
