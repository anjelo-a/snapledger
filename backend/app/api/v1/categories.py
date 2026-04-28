from datetime import UTC, datetime
from typing import Annotated

from fastapi import APIRouter, Depends
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from app.api.error_handlers import to_http_exception
from app.core.errors import DomainError
from app.db.session import get_db
from app.repositories.category_repo import list_categories as list_categories_from_db
from app.repositories.category_repo import list_seed_categories
from app.schemas.category import CategoryCreate, CategoryListResponse, CategoryRead, CategoryUpdate
from app.services.category_service import CategoryService

router = APIRouter(prefix="/categories", tags=["categories"])


@router.get("", response_model=CategoryListResponse)
def list_categories(db: Annotated[Session, Depends(get_db)]) -> CategoryListResponse:
    now = datetime.now(UTC)
    try:
        categories = list_categories_from_db(db)
        if categories:
            return CategoryListResponse(
                items=[
                    CategoryRead(
                        id=item.id,
                        name=item.name,
                        is_default=item.is_default,
                        is_archived=item.is_archived,
                        created_at=item.created_at,
                        updated_at=item.updated_at,
                    )
                    for item in categories
                ]
            )
    except SQLAlchemyError:
        # Keep category listing functional before DB migration is applied locally.
        pass

    seed_items = [
        CategoryRead(**seed, created_at=now, updated_at=now)
        for seed in list_seed_categories()
    ]
    return CategoryListResponse(items=seed_items)


@router.post("", response_model=CategoryRead)
def create_category(
    payload: CategoryCreate,
    db: Annotated[Session, Depends(get_db)],
) -> CategoryRead:
    try:
        return CategoryService.create(db, payload)
    except DomainError as exc:
        raise to_http_exception(exc) from exc


@router.patch("/{category_id}", response_model=CategoryRead)
def patch_category(
    category_id: str,
    payload: CategoryUpdate,
    db: Annotated[Session, Depends(get_db)],
) -> CategoryRead:
    try:
        return CategoryService.patch(db, category_id, payload)
    except DomainError as exc:
        raise to_http_exception(exc) from exc
