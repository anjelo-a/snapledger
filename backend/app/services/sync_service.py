from __future__ import annotations

import base64
import json
from datetime import UTC, date, datetime
from decimal import Decimal
from typing import Any

from pydantic import Field, ValidationError
from sqlalchemy import and_, delete, or_, select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session, selectinload

from app.core.errors import ServiceUnavailableError
from app.core.security import StrictSchema
from app.models.expense import Expense
from app.models.expense_item import ExpenseItem
from app.models.sync_mutation_log import SyncMutationLog
from app.repositories.expense_repo import ExpenseRepository
from app.schemas.expense import ExpenseItemWrite, ExpenseWrite
from app.schemas.sync import (
    SyncAccountDeleteResponse,
    SyncMutation,
    SyncMutationResult,
    SyncPullChange,
    SyncPullResponse,
    SyncPushRequest,
    SyncPushResponse,
)

SYNC_PULL_PAGE_SIZE = 100


class SyncPayloadValidationError(Exception):
    pass


class _SyncExpensePatch(StrictSchema):
    source: str | None = Field(default=None, pattern="^(manual|scan)$")
    merchant: str | None = Field(default=None, min_length=1, max_length=160)
    expense_date: date | None = None
    total_amount: Decimal | None = Field(
        default=None,
        gt=Decimal("0"),
        max_digits=12,
        decimal_places=2,
    )
    currency: str | None = Field(default=None, min_length=3, max_length=3)
    category_id: str | None = None
    notes: str | None = Field(default=None, max_length=2000)
    items: list[ExpenseItemWrite] | None = Field(default=None, max_length=200)


class SyncService:
    """Phase 4 receipts-first sync workflows."""

    @staticmethod
    def push(db: Session, payload: SyncPushRequest, *, owner_key: str) -> SyncPushResponse:
        results: list[SyncMutationResult] = []
        recorded_results: dict[str, SyncMutationResult] = {}
        try:
            for mutation in payload.mutations:
                if mutation.idempotency_key in recorded_results:
                    results.append(recorded_results[mutation.idempotency_key])
                    continue

                log_key = _namespaced_idempotency_key(owner_key, mutation.idempotency_key)
                existing = db.get(SyncMutationLog, log_key)
                if existing is not None:
                    result = _result_from_log(existing)
                    recorded_results[mutation.idempotency_key] = result
                    results.append(result)
                    continue

                result = _apply_mutation(db, mutation, owner_key=owner_key)
                db.add(_log_from_result(result, namespaced_idempotency_key=log_key))
                recorded_results[mutation.idempotency_key] = result
                results.append(result)

            db.commit()
        except SyncPayloadValidationError:
            db.rollback()
            raise
        except SQLAlchemyError as exc:
            db.rollback()
            raise ServiceUnavailableError(
                "Database operation failed while pushing sync batch."
            ) from exc

        return SyncPushResponse(
            accepted=sum(1 for result in results if result.status == "accepted"),
            rejected=sum(1 for result in results if result.status == "rejected"),
            results=results,
        )

    @staticmethod
    def pull(db: Session, cursor: str, *, owner_key: str) -> SyncPullResponse:
        try:
            cursor_key = _decode_pull_cursor(cursor)
            stmt = (
                select(Expense)
                .where(Expense.owner_key == owner_key)
                .options(selectinload(Expense.items))
            )
            if cursor_key is not None:
                updated_at, expense_id = cursor_key
                stmt = stmt.where(
                    or_(
                        Expense.updated_at > updated_at,
                        and_(
                            Expense.updated_at == updated_at,
                            Expense.id > expense_id,
                        ),
                    )
                )

            stmt = stmt.order_by(Expense.updated_at.asc(), Expense.id.asc()).limit(
                SYNC_PULL_PAGE_SIZE + 1
            )
            expenses = list(db.scalars(stmt).all())
        except SyncPayloadValidationError:
            raise
        except SQLAlchemyError as exc:
            raise ServiceUnavailableError(
                "Database operation failed while pulling sync changes."
            ) from exc

        has_more = len(expenses) > SYNC_PULL_PAGE_SIZE
        page = expenses[:SYNC_PULL_PAGE_SIZE]
        changes = [_to_pull_change(expense) for expense in page]
        next_cursor = cursor
        if page:
            last = page[-1]
            next_cursor = _encode_pull_cursor(last.updated_at, last.id)

        return SyncPullResponse(
            cursor=next_cursor,
            has_more=has_more,
            changes=changes,
        )

    @staticmethod
    def delete_account(db: Session, *, owner_key: str) -> SyncAccountDeleteResponse:
        try:
            expenses = list(
                db.scalars(
                    select(Expense)
                    .where(Expense.owner_key == owner_key)
                    .options(selectinload(Expense.items))
                ).all()
            )
            deleted_expenses = len(expenses)
            for expense in expenses:
                db.delete(expense)

            deleted_logs = db.execute(
                delete(SyncMutationLog).where(
                    SyncMutationLog.idempotency_key.like(f"{owner_key}::%"),
                )
            ).rowcount or 0
            db.commit()
        except SQLAlchemyError as exc:
            db.rollback()
            raise ServiceUnavailableError(
                "Database operation failed while deleting synced account data."
            ) from exc

        return SyncAccountDeleteResponse(
            deleted_expenses=deleted_expenses,
            deleted_logs=deleted_logs,
        )


def _apply_mutation(db: Session, mutation: SyncMutation, *, owner_key: str) -> SyncMutationResult:
    if mutation.entity != "expense":
        return SyncMutationResult(
            idempotency_key=mutation.idempotency_key,
            entity=mutation.entity,
            operation=mutation.operation,
            status="rejected",
            code="unsupported_entity_phase4",
            message="Only expense sync mutations are supported in Phase 4 receipts-first sync.",
        )

    receipt_id = _extract_receipt_id(mutation.payload)
    if mutation.operation == "create":
        return _create_expense(db, mutation, receipt_id, owner_key=owner_key)
    if mutation.operation == "update":
        return _update_expense(db, mutation, receipt_id, owner_key=owner_key)
    return _delete_expense(db, mutation, receipt_id, owner_key=owner_key)


def _create_expense(
    db: Session,
    mutation: SyncMutation,
    receipt_id: str,
    *,
    owner_key: str,
) -> SyncMutationResult:
    if _get_expense(db, receipt_id, owner_key=owner_key) is not None:
        return _rejected_result(
            mutation=mutation,
            entity_id=receipt_id,
            code="expense_already_exists",
            message=f"Expense already exists for id={receipt_id}.",
        )

    payload = _validate_payload(ExpenseWrite, mutation.payload, exclude_id=True)
    expense = Expense(
        id=receipt_id,
        owner_key=owner_key,
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
    return _accepted_result(mutation=mutation, entity_id=receipt_id)


def _update_expense(
    db: Session,
    mutation: SyncMutation,
    receipt_id: str,
    *,
    owner_key: str,
) -> SyncMutationResult:
    payload = _validate_payload(_SyncExpensePatch, mutation.payload, exclude_id=True)
    expense = _get_expense(db, receipt_id, owner_key=owner_key)
    if expense is None:
        return _rejected_result(
            mutation=mutation,
            entity_id=receipt_id,
            code="expense_not_found",
            message=f"Expense not found for id={receipt_id}.",
    )

    updates = payload.model_dump(exclude_unset=True, exclude={"items"})
    null_required_fields = {
        field
        for field in ("source", "merchant", "expense_date", "total_amount", "currency")
        if field in updates and updates[field] is None
    }
    if null_required_fields:
        fields = ", ".join(sorted(null_required_fields))
        raise SyncPayloadValidationError(f"Update payload cannot null required fields: {fields}.")

    for key, value in updates.items():
        setattr(expense, key, value)

    if payload.items is not None:
        ExpenseRepository._replace_items(db, expense, payload.items)

    db.flush()
    return _accepted_result(mutation=mutation, entity_id=receipt_id)


def _delete_expense(
    db: Session,
    mutation: SyncMutation,
    receipt_id: str,
    *,
    owner_key: str,
) -> SyncMutationResult:
    unexpected_fields = set(mutation.payload.keys()) - {"id"}
    if unexpected_fields:
        fields = ", ".join(sorted(unexpected_fields))
        raise SyncPayloadValidationError(f"Delete payload only supports id; got: {fields}.")

    deleted = _soft_delete_expense(db, receipt_id, owner_key=owner_key)
    if not deleted:
        return _rejected_result(
            mutation=mutation,
            entity_id=receipt_id,
            code="expense_not_found",
            message=f"Expense not found for id={receipt_id}.",
        )

    return _accepted_result(mutation=mutation, entity_id=receipt_id)


def _extract_receipt_id(payload: dict[str, Any]) -> str:
    receipt_id = payload.get("id")
    if not isinstance(receipt_id, str) or not receipt_id.strip():
        raise SyncPayloadValidationError("Expense sync payload requires non-empty string id.")
    if len(receipt_id) > 64:
        raise SyncPayloadValidationError("Expense sync payload id must be at most 64 characters.")
    return receipt_id.strip()


def _validate_payload(
    model: type[ExpenseWrite] | type[_SyncExpensePatch],
    payload: dict[str, Any],
    *,
    exclude_id: bool,
) -> ExpenseWrite | _SyncExpensePatch:
    model_payload = dict(payload)
    if exclude_id:
        model_payload.pop("id", None)
    try:
        return model.model_validate(model_payload)
    except ValidationError as exc:
        raise SyncPayloadValidationError(str(exc)) from exc


def _accepted_result(*, mutation: SyncMutation, entity_id: str) -> SyncMutationResult:
    return SyncMutationResult(
        idempotency_key=mutation.idempotency_key,
        entity=mutation.entity,
        operation=mutation.operation,
        status="accepted",
        entity_id=entity_id,
        message="Expense sync mutation accepted.",
    )


def _rejected_result(
    *,
    mutation: SyncMutation,
    entity_id: str | None,
    code: str,
    message: str,
) -> SyncMutationResult:
    return SyncMutationResult(
        idempotency_key=mutation.idempotency_key,
        entity=mutation.entity,
        operation=mutation.operation,
        status="rejected",
        code=code,
        message=message,
        entity_id=entity_id,
    )


def _log_from_result(
    result: SyncMutationResult,
    *,
    namespaced_idempotency_key: str,
) -> SyncMutationLog:
    summary = json.dumps(result.model_dump(mode="json"), separators=(",", ":"))
    return SyncMutationLog(
        idempotency_key=namespaced_idempotency_key,
        entity=result.entity,
        operation=result.operation,
        entity_id=result.entity_id,
        status=result.status,
        response_summary=summary if result.status == "accepted" else None,
        error_summary=summary if result.status == "rejected" else None,
    )


def _result_from_log(log: SyncMutationLog) -> SyncMutationResult:
    summary = log.response_summary if log.status == "accepted" else log.error_summary
    if summary:
        return SyncMutationResult.model_validate(json.loads(summary))

    return SyncMutationResult(
        idempotency_key=log.idempotency_key,
        entity=log.entity,  # type: ignore[arg-type]
        operation=log.operation,  # type: ignore[arg-type]
        status=log.status,  # type: ignore[arg-type]
        entity_id=log.entity_id,
    )


def _to_pull_change(expense: Expense) -> SyncPullChange:
    operation = "delete" if expense.deleted_at is not None else "upsert"
    payload = None
    if operation == "upsert":
        payload = {
            "source": expense.source,
            "merchant": expense.merchant,
            "expense_date": expense.expense_date.isoformat(),
            "total_amount": f"{expense.total_amount:.2f}",
            "currency": expense.currency,
            "category_id": expense.category_id,
            "notes": expense.notes,
            "items": [
                {
                    "name": item.name,
                    "amount": f"{item.amount:.2f}",
                }
                for item in _active_items(expense)
            ],
        }

    return SyncPullChange(
        entity="expense",
        operation=operation,
        id=expense.id,
        updated_at=_normalize_datetime(expense.updated_at),
        payload=payload,
    )


def _get_expense(db: Session, receipt_id: str, *, owner_key: str) -> Expense | None:
    stmt = (
        select(Expense)
        .where(
            Expense.id == receipt_id,
            Expense.owner_key == owner_key,
            Expense.deleted_at.is_(None),
        )
        .options(selectinload(Expense.items))
    )
    return db.scalar(stmt)


def _soft_delete_expense(db: Session, receipt_id: str, *, owner_key: str) -> bool:
    expense = _get_expense(db, receipt_id, owner_key=owner_key)
    if expense is None:
        return False

    deleted_at = datetime.now(UTC)
    expense.deleted_at = deleted_at
    for item in expense.items:
        if item.deleted_at is None:
            item.deleted_at = deleted_at

    db.flush()
    return True


def _namespaced_idempotency_key(owner_key: str, idempotency_key: str) -> str:
    return f"{owner_key}::{idempotency_key}"


def _active_items(expense: Expense) -> list[ExpenseItem]:
    return sorted(
        (item for item in expense.items if item.deleted_at is None),
        key=lambda item: (_normalize_datetime(item.created_at), item.id),
    )


def _encode_pull_cursor(updated_at: datetime, expense_id: str) -> str:
    payload = {
        "updated_at": _normalize_datetime(updated_at).isoformat(),
        "id": expense_id,
    }
    raw = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    return base64.urlsafe_b64encode(raw).decode("utf-8")


def _decode_pull_cursor(cursor: str) -> tuple[datetime, str] | None:
    if cursor == "0":
        return None

    try:
        decoded = base64.urlsafe_b64decode(cursor.encode("utf-8"))
        payload = json.loads(decoded.decode("utf-8"))
        updated_at = payload["updated_at"]
        expense_id = payload["id"]
    except Exception as exc:
        raise SyncPayloadValidationError("Invalid sync cursor.") from exc

    if not isinstance(updated_at, str) or not isinstance(expense_id, str) or not expense_id:
        raise SyncPayloadValidationError("Invalid sync cursor.")

    try:
        return _normalize_datetime(datetime.fromisoformat(updated_at)), expense_id
    except ValueError as exc:
        raise SyncPayloadValidationError("Invalid sync cursor.") from exc


def _normalize_datetime(value: datetime) -> datetime:
    if value.tzinfo is None:
        return value.replace(tzinfo=UTC)
    return value.astimezone(UTC)
