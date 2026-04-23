from datetime import datetime
from decimal import Decimal

from pydantic import Field

from app.core.security import StrictSchema


class BudgetWrite(StrictSchema):
    scope: str = Field(pattern="^(overall|category)$")
    period: str = Field(pattern="^(weekly|monthly)$")
    amount_limit: Decimal = Field(gt=Decimal("0"), max_digits=12, decimal_places=2)
    category_id: str | None = None


class BudgetRead(BudgetWrite):
    id: str
    created_at: datetime
    updated_at: datetime


class BudgetListResponse(StrictSchema):
    items: list[BudgetRead]
