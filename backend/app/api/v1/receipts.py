from datetime import date
from decimal import Decimal
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from app.db.session import get_db
from app.schemas.expense import (
    ExpenseListResponse,
    ExpensePatch,
    ExpenseRead,
    ExpenseWrite,
    ParsedReceiptCandidate,
    ReceiptProcessRequest,
)
from app.services.parser_service import parse_receipt
from app.services.receipt_service import ReceiptNotFoundError, ReceiptService

router = APIRouter(prefix="/receipts", tags=["receipts"])


@router.post("", response_model=ExpenseRead)
def create_receipt(
    payload: ExpenseWrite,
    db: Annotated[Session, Depends(get_db)],
) -> ExpenseRead:
    try:
        return ReceiptService.create(db, payload)
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc


@router.get("/{receipt_id}", response_model=ExpenseRead)
def get_receipt(
    receipt_id: str,
    db: Annotated[Session, Depends(get_db)],
) -> ExpenseRead:
    try:
        return ReceiptService.get(db, receipt_id)
    except ReceiptNotFoundError as exc:
        raise HTTPException(
            status_code=404,
            detail=f"Receipt not found for id={receipt_id}.",
        ) from exc
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc


@router.get("", response_model=ExpenseListResponse)
def list_receipts(
    db: Annotated[Session, Depends(get_db)],
    date_from: Annotated[date | None, Query()] = None,
    date_to: Annotated[date | None, Query()] = None,
    merchant_query: Annotated[str | None, Query(max_length=160)] = None,
    category_id: Annotated[str | None, Query()] = None,
    amount_min: Annotated[Decimal | None, Query(gt=Decimal("0"))] = None,
    amount_max: Annotated[Decimal | None, Query(gt=Decimal("0"))] = None,
    cursor: Annotated[str | None, Query()] = None,
    limit: Annotated[int, Query(ge=1, le=100)] = 20,
) -> ExpenseListResponse:
    if date_from and date_to and date_from > date_to:
        raise HTTPException(status_code=400, detail="date_from must be on or before date_to.")

    if amount_min is not None and amount_max is not None and amount_min > amount_max:
        raise HTTPException(
            status_code=400,
            detail="amount_min must be less than or equal to amount_max.",
        )

    try:
        return ReceiptService.list(
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
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc


@router.patch("/{receipt_id}", response_model=ExpenseRead)
def patch_receipt(
    receipt_id: str,
    payload: ExpensePatch,
    db: Annotated[Session, Depends(get_db)],
) -> ExpenseRead:
    try:
        return ReceiptService.patch(db, receipt_id, payload)
    except ReceiptNotFoundError as exc:
        raise HTTPException(
            status_code=404,
            detail=f"Receipt not found for id={receipt_id}.",
        ) from exc
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc


@router.delete("/{receipt_id}")
def delete_receipt(
    receipt_id: str,
    db: Annotated[Session, Depends(get_db)],
) -> dict[str, str]:
    try:
        ReceiptService.soft_delete(db, receipt_id)
        return {"status": "deleted"}
    except ReceiptNotFoundError as exc:
        raise HTTPException(
            status_code=404,
            detail=f"Receipt not found for id={receipt_id}.",
        ) from exc
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc


@router.post("/process", response_model=ParsedReceiptCandidate)
def process_receipt(payload: ReceiptProcessRequest) -> ParsedReceiptCandidate:
    return parse_receipt(payload)
