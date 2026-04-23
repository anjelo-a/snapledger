from fastapi import APIRouter, HTTPException, Query

from app.schemas.expense import (
    ExpenseListResponse,
    ExpensePatch,
    ExpenseRead,
    ExpenseWrite,
    ParsedReceiptCandidate,
    ReceiptProcessRequest,
)
from app.services.parser_service import parse_receipt

router = APIRouter(prefix="/receipts", tags=["receipts"])


@router.post("", response_model=ExpenseRead)
def create_receipt(_payload: ExpenseWrite) -> ExpenseRead:
    raise HTTPException(status_code=501, detail="Receipt create is scheduled for Phase 1.")


@router.get("/{receipt_id}", response_model=ExpenseRead)
def get_receipt(receipt_id: str) -> ExpenseRead:
    raise HTTPException(
        status_code=501,
        detail=f"Receipt lookup not implemented for id={receipt_id}.",
    )


@router.get("", response_model=ExpenseListResponse)
def list_receipts(
    date_from: str | None = Query(default=None),
    date_to: str | None = Query(default=None),
    merchant_query: str | None = Query(default=None),
    category_id: str | None = Query(default=None),
    amount_min: str | None = Query(default=None),
    amount_max: str | None = Query(default=None),
    cursor: str | None = Query(default=None),
    limit: int = Query(default=20, ge=1, le=100),
) -> ExpenseListResponse:
    _ = (date_from, date_to, merchant_query, category_id, amount_min, amount_max, cursor, limit)
    raise HTTPException(status_code=501, detail="Receipt listing is scheduled for Phase 1.")


@router.patch("/{receipt_id}", response_model=ExpenseRead)
def patch_receipt(receipt_id: str, _payload: ExpensePatch) -> ExpenseRead:
    raise HTTPException(
        status_code=501,
        detail=f"Receipt patch not implemented for id={receipt_id}.",
    )


@router.delete("/{receipt_id}")
def delete_receipt(receipt_id: str) -> dict[str, str]:
    raise HTTPException(
        status_code=501,
        detail=f"Receipt delete not implemented for id={receipt_id}.",
    )


@router.post("/process", response_model=ParsedReceiptCandidate)
def process_receipt(payload: ReceiptProcessRequest) -> ParsedReceiptCandidate:
    return parse_receipt(payload)
