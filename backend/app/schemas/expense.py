from datetime import date, datetime
from decimal import Decimal

from pydantic import Field, field_validator

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

    @field_validator("ocr_lines")
    @classmethod
    def validate_ocr_lines(cls, value: list[str]) -> list[str]:
        total_chars = 0
        for line in value:
            if not line:
                raise ValueError("ocr_lines entries must not be blank.")
            if len(line) > 500:
                raise ValueError("ocr_lines entries must be at most 500 characters long.")
            total_chars += len(line)
        if total_chars > 20000:
            raise ValueError("ocr_lines total text length must be at most 20000 characters.")
        return value

    @field_validator("currency_hint")
    @classmethod
    def normalize_currency_hint(cls, value: str | None) -> str | None:
        return value.upper() if value is not None else None


class ParsedReceiptFieldConfidence(StrictSchema):
    merchant: float | None = Field(default=None, ge=0, le=1)
    expense_date: float | None = Field(default=None, ge=0, le=1)
    total_amount: float | None = Field(default=None, ge=0, le=1)
    items: float | None = Field(default=None, ge=0, le=1)


class ParsedReceiptCandidate(StrictSchema):
    merchant: str | None = None
    expense_date: date | None = None
    total_amount: Decimal | None = None
    items: list[ExpenseItemWrite] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)
    warning_codes: list[str] = Field(default_factory=list, max_length=50)
    field_confidence: ParsedReceiptFieldConfidence | None = None
