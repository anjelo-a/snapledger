from __future__ import annotations

from dataclasses import dataclass

from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from sqlalchemy.orm import Session

from app.core.errors import (
    ConflictError,
    InvalidOperationError,
    ServiceUnavailableError,
)
from app.repositories.budget_repo import BudgetRepository
from app.repositories.category_repo import get_active_category_by_id
from app.schemas.budget import BudgetListResponse, BudgetRead, BudgetWrite

THRESHOLD_WARNING = 0.7
THRESHOLD_CRITICAL = 0.9
THRESHOLD_EXCEEDED = 1.0


@dataclass(frozen=True)
class ThresholdConfig:
    warning: float = THRESHOLD_WARNING
    critical: float = THRESHOLD_CRITICAL
    exceeded: float = THRESHOLD_EXCEEDED


class BudgetService:
    """Budget use-cases with deterministic validation and upsert behavior."""

    THRESHOLDS = ThresholdConfig()

    @staticmethod
    def list(db: Session) -> BudgetListResponse:
        try:
            rows = BudgetRepository.list_active(db)
            return BudgetListResponse(items=[_to_read(row) for row in rows])
        except SQLAlchemyError as exc:
            raise ServiceUnavailableError(
                "Database operation failed while listing budgets."
            ) from exc

    @staticmethod
    def upsert(db: Session, payload: BudgetWrite) -> BudgetRead:
        _validate_scope_category(db, payload)
        try:
            budget = BudgetRepository.upsert_active(db, payload)
            db.commit()
            db.refresh(budget)
            return _to_read(budget)
        except ConflictError:
            db.rollback()
            raise
        except IntegrityError as exc:
            db.rollback()
            raise ConflictError("Budget already exists for scope/period/category.") from exc
        except SQLAlchemyError as exc:
            db.rollback()
            raise ServiceUnavailableError(
                "Database operation failed while saving budget."
            ) from exc


def _validate_scope_category(db: Session, payload: BudgetWrite) -> None:
    if payload.scope == "overall" and payload.category_id is not None:
        raise InvalidOperationError("overall budget scope requires category_id to be null.")

    if payload.scope == "category":
        if payload.category_id is None:
            raise InvalidOperationError("category budget scope requires category_id.")
        category = get_active_category_by_id(db, payload.category_id)
        if category is None or category.is_archived:
            raise InvalidOperationError("category_id must reference an active category.")


def _to_read(budget) -> BudgetRead:
    return BudgetRead(
        id=budget.id,
        scope=budget.scope,
        period=budget.period,
        amount_limit=budget.amount_limit,
        category_id=budget.category_id,
        created_at=budget.created_at,
        updated_at=budget.updated_at,
    )
