from __future__ import annotations

from datetime import date
from decimal import Decimal

from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from app.models.expense import Expense
from app.repositories.expense_repo import ExpenseRepository
from app.schemas.expense import (
    ExpenseItemRead,
    ExpenseListResponse,
    ExpensePatch,
    ExpenseRead,
    ExpenseWrite,
)


class ReceiptNotFoundError(Exception):
    pass


class ReceiptService:
    """Receipt use-cases with deterministic CRUD behavior."""

    @staticmethod
    def create(db: Session, payload: ExpenseWrite) -> ExpenseRead:
        try:
            expense = ExpenseRepository.create(db, payload)
            db.commit()
            db.refresh(expense)
            return _to_read(expense)
        except SQLAlchemyError as exc:
            db.rollback()
            raise RuntimeError("Database operation failed while creating receipt.") from exc

    @staticmethod
    def get(db: Session, receipt_id: str) -> ExpenseRead:
        try:
            expense = ExpenseRepository.get_active_by_id(db, receipt_id)
        except SQLAlchemyError as exc:
            raise RuntimeError("Database operation failed while loading receipt.") from exc

        if expense is None:
            raise ReceiptNotFoundError
        return _to_read(expense)

    @staticmethod
    def list(
        db: Session,
        *,
        date_from: date | None,
        date_to: date | None,
        merchant_query: str | None,
        category_id: str | None,
        amount_min: Decimal | None,
        amount_max: Decimal | None,
        cursor: str | None,
        limit: int,
    ) -> ExpenseListResponse:
        try:
            items, next_cursor = ExpenseRepository.list_active(
                db,
                date_from=date_from,
                date_to=date_to,
                merchant_query=merchant_query,
                category_id=category_id,
                amount_min=amount_min,
                amount_max=amount_max,
                cursor=cursor,
                limit=limit,
            )
        except SQLAlchemyError as exc:
            raise RuntimeError("Database operation failed while listing receipts.") from exc

        return ExpenseListResponse(
            items=[_to_read(item) for item in items],
            next_cursor=next_cursor,
        )

    @staticmethod
    def patch(db: Session, receipt_id: str, payload: ExpensePatch) -> ExpenseRead:
        try:
            expense = ExpenseRepository.update_active(db, receipt_id, payload)
            if expense is None:
                db.rollback()
                raise ReceiptNotFoundError

            db.commit()
            db.refresh(expense)
            return _to_read(expense)
        except ReceiptNotFoundError:
            raise
        except SQLAlchemyError as exc:
            db.rollback()
            raise RuntimeError("Database operation failed while updating receipt.") from exc

    @staticmethod
    def soft_delete(db: Session, receipt_id: str) -> None:
        try:
            deleted = ExpenseRepository.soft_delete_active(db, receipt_id)
            if not deleted:
                db.rollback()
                raise ReceiptNotFoundError
            db.commit()
        except ReceiptNotFoundError:
            raise
        except SQLAlchemyError as exc:
            db.rollback()
            raise RuntimeError("Database operation failed while deleting receipt.") from exc


def _to_read(expense: Expense) -> ExpenseRead:
    active_items = [item for item in expense.items if item.deleted_at is None]
    return ExpenseRead(
        id=expense.id,
        source=expense.source,
        merchant=expense.merchant,
        expense_date=expense.expense_date,
        total_amount=expense.total_amount,
        currency=expense.currency,
        category_id=expense.category_id,
        notes=expense.notes,
        items=[
            ExpenseItemRead(
                id=item.id,
                name=item.name,
                amount=item.amount,
            )
            for item in active_items
        ],
        created_at=expense.created_at,
        updated_at=expense.updated_at,
    )
