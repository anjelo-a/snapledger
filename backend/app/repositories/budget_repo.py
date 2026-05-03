from __future__ import annotations

from sqlalchemy import Select, select
from sqlalchemy.orm import Session

from app.models.budget import Budget
from app.schemas.budget import BudgetWrite


class BudgetRepository:
    """DB-backed budget operations for active (non-deleted) records."""

    @staticmethod
    def list_active(db: Session) -> list[Budget]:
        stmt: Select[tuple[Budget]] = (
            select(Budget)
            .where(Budget.deleted_at.is_(None))
            .order_by(
                Budget.scope.asc(),
                Budget.period.asc(),
                Budget.category_id.asc().nullsfirst(),
                Budget.created_at.asc(),
                Budget.id.asc(),
            )
        )
        return list(db.scalars(stmt).all())

    @staticmethod
    def get_active_by_key(
        db: Session,
        *,
        scope: str,
        period: str,
        category_id: str | None,
    ) -> Budget | None:
        stmt: Select[tuple[Budget]] = select(Budget).where(
            Budget.deleted_at.is_(None),
            Budget.scope == scope,
            Budget.period == period,
            Budget.category_id == category_id,
        )
        return db.scalar(stmt)

    @staticmethod
    def upsert_active(db: Session, payload: BudgetWrite) -> Budget:
        budget = BudgetRepository.get_active_by_key(
            db,
            scope=payload.scope,
            period=payload.period,
            category_id=payload.category_id,
        )
        if budget is None:
            budget = Budget(
                scope=payload.scope,
                period=payload.period,
                amount_limit=payload.amount_limit,
                category_id=payload.category_id,
            )
            db.add(budget)
            db.flush()
            db.refresh(budget)
            return budget

        budget.amount_limit = payload.amount_limit
        db.flush()
        db.refresh(budget)
        return budget
