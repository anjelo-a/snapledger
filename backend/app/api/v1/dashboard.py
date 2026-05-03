from typing import Annotated

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.api.error_handlers import to_http_exception
from app.core.errors import DomainError
from app.db.session import get_db
from app.schemas.dashboard import DashboardResponse
from app.services.dashboard_service import DashboardService

router = APIRouter(prefix="/dashboard", tags=["dashboard"])


@router.get("", response_model=DashboardResponse)
def get_dashboard(db: Annotated[Session, Depends(get_db)]) -> DashboardResponse:
    try:
        return DashboardService.get(db)
    except DomainError as exc:
        raise to_http_exception(exc) from exc
