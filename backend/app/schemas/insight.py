from datetime import datetime

from pydantic import Field

from app.core.security import StrictSchema


class InsightGenerateRequest(StrictSchema):
    period: str = Field(pattern="^(weekly|monthly)$")
    focus_category_id: str | None = None


class InsightGenerateResponse(StrictSchema):
    text: str
    action_tip: str | None = None
    metrics: dict
    generated_at: datetime
