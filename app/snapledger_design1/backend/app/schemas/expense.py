from datetime import date, datetime
from decimal import Decimal

from pydantic import Field

from app.core.security import PaginationQuery, StrictSchema


class ExpenseItemWrite(StrictSchema):
    name: str = Field(min_length=1, max_length=160)
    amount: Decimal = Field(gt=Decimal("0"), max_digits=12, decimal_places=2)


class ExpenseItemRead(ExpenseItemWrite):
    id: str


class ExpenseWrite(StrictSchema):
    source: str = Field(pattern="^(manual|scan)$")
    merchant: str = Field(min_length=1, max_length=160)
    expense_date: date
    total_amount: Decimal = Field(gt=Decimal("0"), max_digits=12, decimal_places=2)
    currency: str = Field(default="PHP", min_length=3, max_length=3)
    category_id: str | None = None
    notes: str | None = Field(default=None, max_length=2000)
    items: list[ExpenseItemWrite] = Field(default_factory=list, max_length=200)


class ExpensePatch(StrictSchema):
    merchant: str | None = Field(default=None, min_length=1, max_length=160)
    expense_date: date | None = None
    total_amount: Decimal | None = Field(
        default=None,
        gt=Decimal("0"),
        max_digits=12,
        decimal_places=2,
    )
    category_id: str | None = None
    notes: str | None = Field(default=None, max_length=2000)
    items: list[ExpenseItemWrite] | None = Field(default=None, max_length=200)


class ExpenseRead(StrictSchema):
    id: str
    source: str
    merchant: str
    expense_date: date
    total_amount: Decimal
    currency: str
    category_id: str | None
    notes: str | None
    items: list[ExpenseItemRead]
    created_at: datetime
    updated_at: datetime


class ExpenseListQuery(PaginationQuery):
    date_from: date | None = None
    date_to: date | None = None
    merchant_query: str | None = Field(default=None, max_length=160)
    category_id: str | None = None
    amount_min: Decimal | None = Field(
        default=None,
        gt=Decimal("0"),
        max_digits=12,
        decimal_places=2,
    )
    amount_max: Decimal | None = Field(
        default=None,
        gt=Decimal("0"),
        max_digits=12,
        decimal_places=2,
    )


class ExpenseListResponse(StrictSchema):
    items: list[ExpenseRead]
    next_cursor: str | None = None


class ReceiptProcessRequest(StrictSchema):
    ocr_lines: list[str] = Field(min_length=1, max_length=500)
    locale: str | None = Field(default=None, max_length=20)
    currency_hint: str | None = Field(default=None, min_length=3, max_length=3)


class ParsedReceiptCandidate(StrictSchema):
    merchant: str | None = None
    expense_date: date | None = None
    total_amount: Decimal | None = None
    items: list[ExpenseItemWrite] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)
