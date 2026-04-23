from fastapi import APIRouter

from app.schemas.dashboard import DashboardResponse

router = APIRouter(prefix="/dashboard", tags=["dashboard"])


@router.get("", response_model=DashboardResponse)
def get_dashboard() -> DashboardResponse:
    return DashboardResponse(
        budget_statuses=[],
        trends=[],
        category_breakdown=[],
        recent_activity=[],
    )
