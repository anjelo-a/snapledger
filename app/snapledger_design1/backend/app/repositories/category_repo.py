from sqlalchemy import Select, case, select
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
