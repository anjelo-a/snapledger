from __future__ import annotations

import base64
import json
from datetime import UTC, date, datetime
from decimal import Decimal

from sqlalchemy import Select, and_, func, or_, select
from sqlalchemy.orm import Session, selectinload

from app.models.expense import Expense
from app.models.expense_item import ExpenseItem
from app.schemas.expense import ExpenseItemWrite, ExpensePatch, ExpenseWrite


class ExpenseRepository:
    """DB-backed expense operations for active (non-deleted) receipts."""

    @staticmethod
    def create(db: Session, payload: ExpenseWrite) -> Expense:
        expense = Expense(
            source=payload.source,
            merchant=payload.merchant,
            expense_date=payload.expense_date,
            total_amount=payload.total_amount,
            currency=payload.currency,
            category_id=payload.category_id,
            notes=payload.notes,
        )
        db.add(expense)
        db.flush()

        for item in payload.items:
            db.add(
                ExpenseItem(
                    expense_id=expense.id,
                    name=item.name,
                    amount=item.amount,
                )
            )

        db.flush()
        db.refresh(expense)
        return expense

    @staticmethod
    def get_active_by_id(db: Session, receipt_id: str) -> Expense | None:
        stmt: Select[tuple[Expense]] = (
            select(Expense)
            .where(Expense.id == receipt_id, Expense.deleted_at.is_(None))
            .options(selectinload(Expense.items))
        )
        expense = db.scalar(stmt)
        if expense is None:
            return None
        return expense

    @staticmethod
    def list_active(
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
    ) -> tuple[list[Expense], str | None]:
        stmt: Select[tuple[Expense]] = select(Expense).where(Expense.deleted_at.is_(None))

        if date_from is not None:
            stmt = stmt.where(Expense.expense_date >= date_from)
        if date_to is not None:
            stmt = stmt.where(Expense.expense_date <= date_to)
        if merchant_query:
            stmt = stmt.where(Expense.merchant.ilike(f"%{merchant_query}%"))
        if category_id:
            stmt = stmt.where(Expense.category_id == category_id)
        if amount_min is not None:
            stmt = stmt.where(Expense.total_amount >= amount_min)
        if amount_max is not None:
            stmt = stmt.where(Expense.total_amount <= amount_max)

        decoded_cursor = _decode_cursor(cursor)
        if decoded_cursor is not None:
            cursor_date, cursor_created_at, cursor_id = decoded_cursor
            # SQLite strftime("%f") formats fractional seconds to millisecond precision (SS.SSS).
            cursor_created_at_text = cursor_created_at.strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]
            created_at_key = func.strftime("%Y-%m-%d %H:%M:%f", Expense.created_at)
            # Sort order: expense_date desc, created_at desc, id desc
            stmt = stmt.where(
                or_(
                    Expense.expense_date < cursor_date,
                    and_(
                        Expense.expense_date == cursor_date,
                        created_at_key < cursor_created_at_text,
                    ),
                    and_(
                        Expense.expense_date == cursor_date,
                        created_at_key == cursor_created_at_text,
                        Expense.id < cursor_id,
                    ),
                )
            )

        stmt = stmt.order_by(
            Expense.expense_date.desc(),
            Expense.created_at.desc(),
            Expense.id.desc(),
        )
        stmt = stmt.options(selectinload(Expense.items)).limit(limit + 1)

        rows = list(db.scalars(stmt).all())
        has_more = len(rows) > limit
        page_rows = rows[:limit]

        next_cursor = None
        if has_more and page_rows:
            last = page_rows[-1]
            next_cursor = _encode_cursor(
                expense_date=last.expense_date,
                created_at=last.created_at,
                expense_id=last.id,
            )

        return page_rows, next_cursor

    @staticmethod
    def update_active(db: Session, receipt_id: str, payload: ExpensePatch) -> Expense | None:
        expense = ExpenseRepository.get_active_by_id(db, receipt_id)
        if expense is None:
            return None

        updates = payload.model_dump(exclude_unset=True, exclude={"items"})
        for key, value in updates.items():
            setattr(expense, key, value)

        if payload.items is not None:
            ExpenseRepository._replace_items(db, expense, payload.items)

        db.flush()
        db.refresh(expense)
        return expense

    @staticmethod
    def soft_delete_active(db: Session, receipt_id: str) -> bool:
        expense = ExpenseRepository.get_active_by_id(db, receipt_id)
        if expense is None:
            return False

        deleted_at = datetime.now(UTC)
        expense.deleted_at = deleted_at
        for item in expense.items:
            if item.deleted_at is None:
                item.deleted_at = deleted_at

        db.flush()
        return True

    @staticmethod
    def _replace_items(db: Session, expense: Expense, items: list[ExpenseItemWrite]) -> None:
        deleted_at = datetime.now(UTC)
        for item in expense.items:
            if item.deleted_at is None:
                item.deleted_at = deleted_at

        for item in items:
            db.add(
                ExpenseItem(
                    expense_id=expense.id,
                    name=item.name,
                    amount=item.amount,
                )
            )


def _encode_cursor(*, expense_date: date, created_at: datetime, expense_id: str) -> str:
    payload = {
        "expense_date": expense_date.isoformat(),
        "created_at": created_at.isoformat(),
        "id": expense_id,
    }
    raw = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    return base64.urlsafe_b64encode(raw).decode("utf-8")


def _decode_cursor(cursor: str | None) -> tuple[date, datetime, str] | None:
    if not cursor:
        return None

    try:
        decoded = base64.urlsafe_b64decode(cursor.encode("utf-8"))
        payload = json.loads(decoded.decode("utf-8"))
        return (
            date.fromisoformat(payload["expense_date"]),
            datetime.fromisoformat(payload["created_at"]),
            str(payload["id"]),
        )
    except Exception:
        return None
