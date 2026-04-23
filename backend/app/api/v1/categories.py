from datetime import datetime, timezone

from fastapi import APIRouter, HTTPException

from app.repositories.category_repo import list_seed_categories
from app.schemas.category import CategoryCreate, CategoryListResponse, CategoryRead, CategoryUpdate

router = APIRouter(prefix="/categories", tags=["categories"])


@router.get("", response_model=CategoryListResponse)
def list_categories() -> CategoryListResponse:
    now = datetime.now(timezone.utc)
    items = [
        CategoryRead(
            **seed,
            created_at=now,
            updated_at=now,
        )
        for seed in list_seed_categories()
    ]
    return CategoryListResponse(items=items)


@router.post("", response_model=CategoryRead)
def create_category(_payload: CategoryCreate) -> CategoryRead:
    raise HTTPException(status_code=501, detail="Custom categories are scheduled for Phase 1.")


@router.patch("/{category_id}", response_model=CategoryRead)
def patch_category(category_id: str, _payload: CategoryUpdate) -> CategoryRead:
    raise HTTPException(status_code=501, detail=f"Category patch not implemented for id={category_id}.")
