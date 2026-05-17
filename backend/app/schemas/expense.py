from datetime import date, datetime
from decimal import Decimal

from pydantic import Field, field_validator, model_validator

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
    ocr_lines: list[str] | None = Field(default=None, min_length=1, max_length=500)
    image_base64: str | None = Field(default=None, min_length=32, max_length=12_000_000)
    image_mime_type: str | None = Field(default=None, max_length=32)
    locale: str | None = Field(default=None, max_length=20)
    currency_hint: str | None = Field(default=None, min_length=3, max_length=3)

    @field_validator("ocr_lines")
    @classmethod
    def validate_ocr_lines(cls, value: list[str] | None) -> list[str] | None:
        if value is None:
            return None
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

    @field_validator("image_base64")
    @classmethod
    def normalize_image_base64(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        if normalized.startswith("data:") and "," in normalized:
            normalized = normalized.split(",", 1)[1]
        if not normalized:
            raise ValueError("image_base64 must not be blank.")
        return normalized

    @field_validator("image_mime_type")
    @classmethod
    def normalize_image_mime_type(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip().lower()
        if normalized not in {"image/jpeg", "image/png", "image/webp"}:
            raise ValueError("image_mime_type must be image/jpeg, image/png, or image/webp.")
        return normalized

    @field_validator("currency_hint")
    @classmethod
    def normalize_currency_hint(cls, value: str | None) -> str | None:
        return value.upper() if value is not None else None

    @field_validator("locale")
    @classmethod
    def normalize_locale(cls, value: str | None) -> str | None:
        return value.strip() if value is not None else None

    @model_validator(mode="after")
    def validate_union_input(self) -> "ReceiptProcessRequest":
        ocr_lines = self.ocr_lines
        image_base64 = self.image_base64
        if not ocr_lines and not image_base64:
            raise ValueError("Either ocr_lines or image_base64 is required.")
        if image_base64 and not self.image_mime_type:
            raise ValueError("image_mime_type is required when image_base64 is provided.")
        if self.image_mime_type and not image_base64:
            raise ValueError("image_mime_type requires image_base64.")
        return self


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
