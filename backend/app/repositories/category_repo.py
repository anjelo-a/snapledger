from sqlalchemy import Select, case, func, select
from sqlalchemy.orm import Session

from app.core.category_seeds import DEFAULT_CATEGORY_SEEDS
from app.models.category import Category


def list_seed_categories() -> list[dict[str, object]]:
    return [
        {
            "id": f"seed-{idx}",
            "name": name,
            "is_default": True,
            "is_archived": False,
        }
        for idx, name in enumerate(DEFAULT_CATEGORY_SEEDS, start=1)
    ]


def list_categories(db: Session) -> list[Category]:
    ordering = (
        case((Category.is_default.is_(True), 0), else_=1),
        Category.name.asc(),
    )
    stmt: Select[tuple[Category]] = (
        select(Category).where(Category.deleted_at.is_(None)).order_by(*ordering)
    )
    return list(db.scalars(stmt).all())


def create_category(db: Session, *, name: str) -> Category:
    category = Category(name=name, is_default=False, is_archived=False)
    db.add(category)
    db.flush()
    db.refresh(category)
    return category


def get_active_category_by_id(db: Session, category_id: str) -> Category | None:
    stmt: Select[tuple[Category]] = select(Category).where(
        Category.id == category_id,
        Category.deleted_at.is_(None),
    )
    return db.scalar(stmt)


def find_active_by_normalized_name(
    db: Session,
    *,
    normalized_name: str,
    exclude_category_id: str | None = None,
) -> Category | None:
    stmt: Select[tuple[Category]] = select(Category).where(
        Category.deleted_at.is_(None),
        func.lower(func.trim(Category.name)) == normalized_name,
    )
    if exclude_category_id:
        stmt = stmt.where(Category.id != exclude_category_id)
    return db.scalar(stmt)
