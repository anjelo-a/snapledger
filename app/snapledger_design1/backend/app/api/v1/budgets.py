from fastapi import APIRouter, HTTPException

from app.schemas.budget import BudgetListResponse, BudgetRead, BudgetWrite

router = APIRouter(prefix="/budgets", tags=["budgets"])


@router.get("", response_model=BudgetListResponse)
def list_budgets() -> BudgetListResponse:
    return BudgetListResponse(items=[])


@router.post("", response_model=BudgetRead)
def upsert_budget(_payload: BudgetWrite) -> BudgetRead:
    raise HTTPException(
        status_code=501,
        detail="Budget write operations are scheduled for Phase 3.",
    )
