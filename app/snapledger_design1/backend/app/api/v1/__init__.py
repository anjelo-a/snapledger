from fastapi import APIRouter

from app.api.v1.budgets import router as budgets_router
from app.api.v1.categories import router as categories_router
from app.api.v1.dashboard import router as dashboard_router
from app.api.v1.insights import router as insights_router
from app.api.v1.receipts import router as receipts_router
from app.api.v1.sync import router as sync_router

api_router = APIRouter()
api_router.include_router(receipts_router)
api_router.include_router(categories_router)
api_router.include_router(budgets_router)
api_router.include_router(dashboard_router)
api_router.include_router(insights_router)
api_router.include_router(sync_router)


@api_router.post("/manual-entries")
def create_manual_entry_alias() -> dict[str, str]:
    return {
        "detail": (
            "Use POST /v1/receipts with source=manual. "
            "Dedicated alias logic is scheduled for Phase 1."
        )
    }
