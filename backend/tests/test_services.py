from __future__ import annotations

from datetime import date

import pytest

from app.core.errors import (
    ConflictError,
    InvalidOperationError,
    NotFoundError,
    ServiceUnavailableError,
)
from app.schemas.category import CategoryCreate, CategoryUpdate
from app.schemas.expense import ExpensePatch, ExpenseWrite
from app.services.category_service import CategoryService
from app.services.receipt_service import ReceiptService


class _NoopDb:
    def __init__(self) -> None:
        self.committed = False
        self.rolled_back = False

    def commit(self) -> None:
        self.committed = True

    def rollback(self) -> None:
        self.rolled_back = True

    def refresh(self, _obj) -> None:
        return


def _expense_payload() -> ExpenseWrite:
    return ExpenseWrite(
        source="manual",
        merchant="Service Test",
        expense_date=date(2026, 4, 24),
        total_amount="150.00",
        currency="PHP",
        items=[],
    )


def test_receipt_service_get_not_found_maps_domain_error(monkeypatch: pytest.MonkeyPatch) -> None:
    db = _NoopDb()

    monkeypatch.setattr(
        "app.services.receipt_service.ExpenseRepository.get_active_by_id",
        lambda *_args, **_kwargs: None,
    )

    with pytest.raises(NotFoundError):
        ReceiptService.get(db, "missing-id")


def test_receipt_service_patch_not_found_rolls_back(monkeypatch: pytest.MonkeyPatch) -> None:
    db = _NoopDb()
    monkeypatch.setattr(
        "app.services.receipt_service.ExpenseRepository.update_active",
        lambda *_args, **_kwargs: None,
    )

    with pytest.raises(NotFoundError):
        ReceiptService.patch(db, "missing-id", ExpensePatch(merchant="x"))
    assert db.rolled_back is True


def test_category_service_create_conflict(monkeypatch: pytest.MonkeyPatch) -> None:
    db = _NoopDb()

    class _Obj:
        pass

    monkeypatch.setattr(
        "app.services.category_service.find_active_by_normalized_name",
        lambda *_args, **_kwargs: _Obj(),
    )

    with pytest.raises(ConflictError):
        CategoryService.create(db, CategoryCreate(name="Food"))


def test_category_service_patch_default_category_invalid_operation(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    db = _NoopDb()

    class _Category:
        id = "seed-1"
        is_default = True

    monkeypatch.setattr(
        "app.services.category_service.get_active_category_by_id",
        lambda *_args, **_kwargs: _Category(),
    )

    with pytest.raises(InvalidOperationError):
        CategoryService.patch(db, "seed-1", CategoryUpdate(name="Renamed"))


def test_receipt_service_create_db_failure_becomes_service_unavailable(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from sqlalchemy.exc import SQLAlchemyError

    db = _NoopDb()

    def _boom(*_args, **_kwargs):
        raise SQLAlchemyError("db down")

    monkeypatch.setattr("app.services.receipt_service.ExpenseRepository.create", _boom)

    with pytest.raises(ServiceUnavailableError):
        ReceiptService.create(db, _expense_payload())
