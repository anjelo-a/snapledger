from datetime import datetime

from pydantic import ConfigDict, Field

from app.core.security import StrictSchema


class InsightGenerateRequest(StrictSchema):
    period: str = Field(default="daily", pattern="^(daily|weekly|monthly)$")
    focus_category_id: str | None = None
    force_refresh: bool = False


class InsightGenerateResponse(StrictSchema):
    text: str
    action_tip: str | None = None
    metrics: dict
    generated_at: datetime
    is_fallback: bool = False


class GeminiInsightOutput(StrictSchema):
    model_config = ConfigDict(extra="forbid")

    text: str = Field(min_length=1, max_length=280)
    action_tip: str | None = Field(default=None, max_length=160)