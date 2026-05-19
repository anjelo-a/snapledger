from __future__ import annotations

from datetime import UTC, date, datetime
from pathlib import Path

from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker

from app.db.base import Base
from app.models.expense import Expense
from app.models.expense_item import ExpenseItem
from app.repositories.expense_repo import ExpenseRepository
from app.schemas.expense import ExpenseItemWrite, ExpensePatch, ExpenseWrite


def _new_db(tmp_path: Path) -> Session:
    db_path = tmp_path / "repo_test.db"
    engine = create_engine(
        f"sqlite:///{db_path}",
        connect_args={"check_same_thread": False},
        future=True,
    )
    Base.metadata.create_all(bind=engine)
    testing_session_local = sessionmaker(
        bind=engine,
        autoflush=False,
        autocommit=False,
        future=True,
    )
    return testing_session_local()


def _payload(merchant: str, total_amount: str) -> ExpenseWrite:
    return ExpenseWrite(
        source="manual",
        merchant=merchant,
        expense_date=date(2026, 4, 24),
        total_amount=total_amount,
        currency="PHP",
        items=[ExpenseItemWrite(name="Item", amount=total_amount)],
    )


def test_soft_delete_scopes_active_reads(tmp_path: Path) -> None:
    db = _new_db(tmp_path)
    try:
        expense = ExpenseRepository.create(db, _payload("Scoped", "111.00"))
        db.commit()

        assert ExpenseRepository.get_active_by_id(db, expense.id) is not None
        assert ExpenseRepository.soft_delete_active(db, expense.id) is True
        db.commit()

        assert ExpenseRepository.get_active_by_id(db, expense.id) is None
        rows, _ = ExpenseRepository.list_active(
            db,
            date_from=None,
            date_to=None,
            merchant_query=None,
            category_id=None,
            amount_min=None,
            amount_max=None,
            cursor=None,
            limit=20,
        )
        assert all(row.id != expense.id for row in rows)
    finally:
        db.close()


def test_item_replacement_soft_deletes_old_items(tmp_path: Path) -> None:
    db = _new_db(tmp_path)
    try:
        expense = ExpenseRepository.create(db, _payload("Replace", "120.00"))
        db.commit()

        updated = ExpenseRepository.update_active(
            db,
            expense.id,
            ExpensePatch(
                items=[
                    ExpenseItemWrite(name="New A", amount="70.00"),
                    ExpenseItemWrite(name="New B", amount="50.00"),
                ]
            ),
        )
        assert updated is not None
        db.commit()

        active = ExpenseRepository.get_active_by_id(db, expense.id)
        assert active is not None
        active_items = [item for item in active.items if item.deleted_at is None]
        assert len(active_items) == 2
        names = {item.name for item in active_items}
        assert names == {"New A", "New B"}

        total_items = list(db.query(ExpenseItem).filter(ExpenseItem.expense_id == expense.id).all())
        deleted_count = len([item for item in total_items if item.deleted_at is not None])
        assert deleted_count >= 1
    finally:
        db.close()


def test_cursor_encoding_decoding_boundary_behavior(tmp_path: Path) -> None:
    db = _new_db(tmp_path)
    try:
        created_ids: list[str] = []
        for idx in range(4):
            expense = ExpenseRepository.create(db, _payload(f"CursorRepo-{idx}", f"{200 + idx}.00"))
            db.flush()
            created_ids.append(expense.id)
        db.commit()

        page_1, cursor_1 = ExpenseRepository.list_active(
            db,
            date_from=None,
            date_to=None,
            merchant_query=None,
            category_id=None,
            amount_min=None,
            amount_max=None,
            cursor=None,
            limit=2,
        )
        assert len(page_1) == 2
        assert cursor_1 is not None

        page_2, cursor_2 = ExpenseRepository.list_active(
            db,
            date_from=None,
            date_to=None,
            merchant_query=None,
            category_id=None,
            amount_min=None,
            amount_max=None,
            cursor=cursor_1,
            limit=2,
        )
        assert len(page_2) == 2
        assert cursor_2 is None

        merged_ids = [item.id for item in page_1 + page_2]
        assert len(merged_ids) == len(set(merged_ids))
        assert set(merged_ids) == set(created_ids)

        invalid_page, _ = ExpenseRepository.list_active(
            db,
            date_from=None,
            date_to=None,
            merchant_query=None,
            category_id=None,
            amount_min=None,
            amount_max=None,
            cursor="not-a-real-cursor",
            limit=2,
        )
        assert len(invalid_page) == 2
    finally:
        db.close()


def test_cursor_pagination_honors_subsecond_created_at_order(tmp_path: Path) -> None:
    db = _new_db(tmp_path)
    try:
        base_date = date(2026, 4, 24)
        oldest_same_second = Expense(
            id="receipt-z",
            source="scan",
            merchant="Older in same second",
            expense_date=base_date,
            total_amount="30.00",
            currency="PHP",
            created_at=datetime(2026, 4, 24, 12, 0, 0, 100000, tzinfo=UTC),
            updated_at=datetime(2026, 4, 24, 12, 0, 0, 100000, tzinfo=UTC),
        )
        newest_same_second = Expense(
            id="receipt-b",
            source="scan",
            merchant="Newest in same second",
            expense_date=base_date,
            total_amount="40.00",
            currency="PHP",
            created_at=datetime(2026, 4, 24, 12, 0, 0, 900000, tzinfo=UTC),
            updated_at=datetime(2026, 4, 24, 12, 0, 0, 900000, tzinfo=UTC),
        )
        older_second = Expense(
            id="receipt-a",
            source="scan",
            merchant="Older second",
            expense_date=base_date,
            total_amount="20.00",
            currency="PHP",
            created_at=datetime(2026, 4, 24, 11, 59, 59, 900000, tzinfo=UTC),
            updated_at=datetime(2026, 4, 24, 11, 59, 59, 900000, tzinfo=UTC),
        )

        db.add_all([oldest_same_second, newest_same_second, older_second])
        db.commit()

        page_1, cursor_1 = ExpenseRepository.list_active(
            db,
            date_from=None,
            date_to=None,
            merchant_query=None,
            category_id=None,
            amount_min=None,
            amount_max=None,
            cursor=None,
            limit=1,
        )
        assert [item.id for item in page_1] == ["receipt-b"]
        assert cursor_1 is not None

        page_2, _ = ExpenseRepository.list_active(
            db,
            date_from=None,
            date_to=None,
            merchant_query=None,
            category_id=None,
            amount_min=None,
            amount_max=None,
            cursor=cursor_1,
            limit=5,
        )
        page_2_ids = [item.id for item in page_2]
        assert "receipt-z" in page_2_ids
        assert "receipt-a" in page_2_ids
    finally:
        db.close()
