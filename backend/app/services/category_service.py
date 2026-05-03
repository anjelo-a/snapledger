from __future__ import annotations

from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from sqlalchemy.orm import Session

from app.core.errors import (
    ConflictError,
    InvalidOperationError,
    NotFoundError,
    ServiceUnavailableError,
)
from app.repositories.category_repo import (
    create_category as create_category_record,
)
from app.repositories.category_repo import (
    find_active_by_normalized_name,
    get_active_category_by_id,
)
from app.schemas.category import CategoryCreate, CategoryRead, CategoryUpdate


class CategoryService:
    """Custom category create/update business rules."""

    @staticmethod
    def create(db: Session, payload: CategoryCreate) -> CategoryRead:
        normalized_name = _normalize_name(payload.name)
        try:
            existing = find_active_by_normalized_name(db, normalized_name=normalized_name)
            if existing is not None:
                raise ConflictError("Category name already exists.")

            category = create_category_record(db, name=payload.name.strip())
            db.commit()
            db.refresh(category)
            return _to_read(category)
        except ConflictError:
            db.rollback()
            raise
        except IntegrityError as exc:
            db.rollback()
            raise ConflictError("Category name already exists.") from exc
        except SQLAlchemyError as exc:
            db.rollback()
            raise ServiceUnavailableError(
                "Database operation failed while creating category."
            ) from exc

    @staticmethod
    def patch(db: Session, category_id: str, payload: CategoryUpdate) -> CategoryRead:
        try:
            category = get_active_category_by_id(db, category_id)
            if category is None:
                raise NotFoundError(f"Category not found for id={category_id}.")

            if category.is_default:
                raise InvalidOperationError(
                    "Default categories cannot be renamed or archived."
                )

            updates = payload.model_dump(exclude_unset=True)
            if "name" in updates:
                normalized_name = _normalize_name(updates["name"])
                existing = find_active_by_normalized_name(
                    db,
                    normalized_name=normalized_name,
                    exclude_category_id=category_id,
                )
                if existing is not None:
                    raise ConflictError("Category name already exists.")
                category.name = updates["name"].strip()

            if "is_archived" in updates:
                category.is_archived = updates["is_archived"]

            db.commit()
            db.refresh(category)
            return _to_read(category)
        except (NotFoundError, InvalidOperationError, ConflictError):
            db.rollback()
            raise
        except IntegrityError as exc:
            db.rollback()
            raise ConflictError("Category name already exists.") from exc
        except SQLAlchemyError as exc:
            db.rollback()
            raise ServiceUnavailableError(
                "Database operation failed while updating category."
            ) from exc


def _normalize_name(name: str) -> str:
    return name.strip().lower()


def _to_read(category) -> CategoryRead:
    return CategoryRead(
        id=category.id,
        name=category.name,
        is_default=category.is_default,
        is_archived=category.is_archived,
        created_at=category.created_at,
        updated_at=category.updated_at,
    )
