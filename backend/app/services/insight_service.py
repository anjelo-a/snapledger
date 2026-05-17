from __future__ import annotations

import json
import logging
from datetime import UTC, datetime
from decimal import Decimal
from typing import Any

import httpx
from pydantic import Field, ValidationError
from sqlalchemy.orm import Session

from app.core.config import get_settings
from app.core.security import StrictSchema
from app.schemas.dashboard import DashboardResponse
from app.schemas.insight import InsightGenerateRequest, InsightGenerateResponse
from app.services.dashboard_service import DashboardService

logger = logging.getLogger(__name__)

_GEMINI_INSIGHT_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "properties": {
        "text": {
            "type": "string",
            "description": "One concise, user-facing spending insight based only on metrics.",
        },
        "action_tip": {
            "type": ["string", "null"],
            "description": "One short practical next step, or null.",
        },
    },
    "required": ["text", "action_tip"],
}


class _GeminiInsightOutput(StrictSchema):
    text: str = Field(min_length=1, max_length=240)
    action_tip: str | None = Field(default=None, max_length=180)


class InsightService:
    """Read-only narrative insight over deterministic dashboard metrics."""

    @staticmethod
    def generate(db: Session, payload: InsightGenerateRequest) -> InsightGenerateResponse:
        dashboard = DashboardService.get(db)
        metrics = _build_metrics(dashboard, payload)

        output = _fallback_output(metrics)
        settings = get_settings()
        if settings.gemini_api_key:
            try:
                output = _call_gemini_insight(metrics, payload.period)
            except Exception as exc:
                logger.warning(
                    "gemini_insight_fallback reason=%s",
                    exc.__class__.__name__,
                )

        return InsightGenerateResponse(
            text=output.text,
            action_tip=output.action_tip,
            metrics=metrics,
            generated_at=datetime.now(UTC),
        )


def _build_metrics(
    dashboard: DashboardResponse,
    payload: InsightGenerateRequest,
) -> dict[str, Any]:
    trend_points = dashboard.trends
    current_total = trend_points[-1].amount if trend_points else Decimal("0.00")
    previous_total = trend_points[-2].amount if len(trend_points) >= 2 else Decimal("0.00")
    delta = current_total - previous_total
    delta_pct: Decimal | None = None
    if previous_total > 0:
        delta_pct = (delta / previous_total * Decimal("100")).quantize(Decimal("0.01"))

    top_category = dashboard.category_breakdown[0] if dashboard.category_breakdown else None
    budget_alert_count = sum(
        1
        for budget in dashboard.budget_statuses
        if budget.threshold_level in {"warning", "critical", "exceeded"}
    )

    return {
        "period": payload.period,
        "current_period_total": str(current_total.quantize(Decimal("0.01"))),
        "previous_period_total": str(previous_total.quantize(Decimal("0.01"))),
        "period_delta": str(delta.quantize(Decimal("0.01"))),
        "period_delta_pct": str(delta_pct) if delta_pct is not None else None,
        "top_category": (
            {
                "id": top_category.category_id,
                "name": top_category.category_name,
                "amount": str(top_category.amount.quantize(Decimal("0.01"))),
            }
            if top_category
            else None
        ),
        "budget_count": len(dashboard.budget_statuses),
        "budget_alert_count": budget_alert_count,
        "recent_activity_count": len(dashboard.recent_activity),
        "trend_points": [
            {"period": point.period_label, "amount": str(point.amount.quantize(Decimal("0.01")))}
            for point in trend_points
        ],
    }


def _call_gemini_insight(metrics: dict[str, Any], period: str) -> _GeminiInsightOutput:
    settings = get_settings()
    endpoint = (
        f"https://generativelanguage.googleapis.com/v1beta/models/"
        f"{settings.gemini_model}:generateContent"
    )
    prompt = (
        "Goal: Write one concise SnapLedger dashboard insight from trusted deterministic metrics.\n"
        "Allowed scope: Use only the JSON metrics payload below.\n"
        "Forbidden actions: Do not calculate money from raw records, invent categories, inspect "
        "receipts, change budgets, or imply certainty not supported by the metrics.\n"
        "Tool policy: No tools. Read-only narration only.\n"
        "Output schema: {text:string, action_tip:string|null}.\n"
        "Done criteria: Text is specific, calm, and under 240 characters; action_tip is practical "
        "and under 180 characters.\n"
        f"Period: {period}\n"
        f"Metrics JSON: {json.dumps(metrics, separators=(',', ':'))}"
    )
    request_payload = {
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {
            "responseMimeType": "application/json",
            "responseJsonSchema": _GEMINI_INSIGHT_SCHEMA,
            "temperature": 0.2,
            "maxOutputTokens": 180,
        },
    }
    timeout_seconds = min(settings.gemini_timeout_seconds, 8.0)
    with httpx.Client(timeout=timeout_seconds) as client:
        response = client.post(
            endpoint,
            headers={"x-goog-api-key": settings.gemini_api_key or ""},
            json=request_payload,
        )
        response.raise_for_status()
        body = response.json()
    response_text = (
        body["candidates"][0]["content"]["parts"][0].get("text")
        if body.get("candidates")
        else "{}"
    )
    logger.info(
        "gemini_insight_response model=%s text_len=%s candidates=%s",
        settings.gemini_model,
        len(response_text or ""),
        len(body.get("candidates") or []),
    )
    try:
        return _GeminiInsightOutput.model_validate_json(response_text or "{}")
    except ValidationError as exc:
        raise ValueError("Gemini insight response failed validation.") from exc


def _fallback_output(metrics: dict[str, Any]) -> _GeminiInsightOutput:
    top_category = metrics.get("top_category")
    current_total = metrics["current_period_total"]
    delta = Decimal(metrics["period_delta"])
    if top_category:
        category_name = top_category["name"]
        category_amount = top_category["amount"]
        text = (
            f"{category_name} leads your tracked spending at PHP {category_amount}, "
            f"with this period total at PHP {current_total}."
        )
        tip = f"Review upcoming {category_name.lower()} purchases before your next save."
    elif delta > 0:
        text = (
            f"Tracked spending is up by PHP {delta.quantize(Decimal('0.01'))} "
            "versus last period."
        )
        tip = "Check recent activity for the biggest new purchase."
    else:
        text = (
            "No strong spending pattern yet. A few more tracked receipts will make "
            "insights sharper."
        )
        tip = "Scan or add your next receipt to build a clearer trend."
    return _GeminiInsightOutput(text=text[:240], action_tip=tip[:180])
