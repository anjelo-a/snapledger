from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import Field, model_validator

from app.core.security import StrictSchema

InsightPeriod = Literal["weekly", "monthly"]
InsightTemplateKey = Literal[
    "top_category",
    "spending_trend",
    "budget_status",
    "saving_opportunity",
]


class InsightGenerateRequest(StrictSchema):
    period: InsightPeriod
    focus_category_id: str | None = None
    metrics: InsightMetrics | None = None


class InsightGenerateResponse(StrictSchema):
    text: str
    action_tip: str | None = None
    metrics: InsightMetrics
    generated_at: datetime


class InsightChatRequest(StrictSchema):
    period: InsightPeriod
    template_key: InsightTemplateKey | None = None
    question: str | None = Field(default=None, min_length=1, max_length=240)
    metrics: InsightMetrics | None = None

    @model_validator(mode="after")
    def validate_prompt_source(self) -> "InsightChatRequest":
        if self.template_key is None and self.question is None:
            raise ValueError("template_key or question is required.")
        return self


class InsightChatResult(StrictSchema):
    answer: str
    action_tip: str | None = None
    question_label: str
    prompt_source: Literal["template", "custom", "guardrail"]
    suggested_template_keys: list[InsightTemplateKey] = Field(default_factory=list)


class InsightChatResponse(StrictSchema):
    schema_version: str = "1.0"
    agent_name: str = "insight_agent"
    task_id: str
    status: Literal["success", "partial", "blocked", "failed"]
    result: InsightChatResult
    warnings: list[str]
    errors: list[str]


class InsightMetricTopCategory(StrictSchema):
    id: str | None = None
    name: str
    amount: str


class InsightMetricTrendPoint(StrictSchema):
    period: str
    amount: str


class InsightMetrics(StrictSchema):
    period: InsightPeriod
    current_period_total: str
    previous_period_total: str
    period_delta: str
    period_delta_pct: str | None = None
    top_category: InsightMetricTopCategory | None = None
    budget_count: int = Field(ge=0)
    budget_alert_count: int = Field(ge=0)
    recent_activity_count: int = Field(ge=0)
    trend_points: list[InsightMetricTrendPoint] = Field(default_factory=list)


InsightGenerateRequest.model_rebuild()
InsightGenerateResponse.model_rebuild()
InsightChatRequest.model_rebuild()
