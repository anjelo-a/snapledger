import json
import os
from datetime import UTC, datetime

import google.generativeai as genai
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models.insight import Insight
from app.schemas.insight import (
    GeminiInsightOutput,
    InsightGenerateRequest,
    InsightGenerateResponse,
)
from app.services.dashboard_service import DashboardService


def get_fallback_insight(metrics_summary: dict) -> dict:
    return {
        "text": "Your spending is tracking normally against your recent averages. Keep logging your receipts to maintain accurate records.",
        "action_tip": "Review your weekly budget to ensure you are on track."
    }


class InsightService:

    @staticmethod
    def generate(db: Session, payload: InsightGenerateRequest) -> InsightGenerateResponse:
        today_start = datetime.now(UTC).replace(hour=0, minute=0, second=0, microsecond=0)

        if not payload.force_refresh:
            stmt = select(Insight).where(
                Insight.period == payload.period,
                Insight.generated_at >= today_start
            ).order_by(Insight.generated_at.desc())

            existing = db.scalars(stmt).first()
            if existing:
                return InsightGenerateResponse(
                    text=existing.text,
                    action_tip=existing.action_tip,
                    metrics=existing.metrics,
                    generated_at=existing.generated_at,
                    is_fallback=False
                )

        dashboard_data = DashboardService.get(db)

        metrics_summary = {
            "budget_statuses": [b.model_dump() for b in dashboard_data.budget_statuses],
            "trends": [t.model_dump() for t in dashboard_data.trends]
        }

        api_key = os.getenv("GEMINI_API_KEY")
        fallback_used = False
        generated_data = None

        if not api_key:
            generated_data = get_fallback_insight(metrics_summary)
            fallback_used = True
        else:
            try:
                genai.configure(api_key=api_key)
                model = genai.GenerativeModel(os.getenv("GEMINI_MODEL", "gemini-1.5-flash"))

                prompt = f"""
                Write one concise spending insight from these trusted metrics.
                Do not calculate money. Do not invent facts. Do not mention unavailable data.
                Return only JSON:
                {{"text": "...", "action_tip": "..."}}

                Metrics:
                {json.dumps(metrics_summary)}
                """

                response = model.generate_content(
                    prompt,
                    generation_config=genai.GenerationConfig(
                        response_mime_type="application/json",
                        temperature=0.2
                    )
                )

                parsed = json.loads(response.text)
                validated = GeminiInsightOutput.model_validate(parsed)
                generated_data = {"text": validated.text, "action_tip": validated.action_tip}

            except Exception:
                generated_data = get_fallback_insight(metrics_summary)
                fallback_used = True

        new_insight = Insight(
            period=payload.period,
            focus_category_id=payload.focus_category_id,
            text=generated_data["text"],
            action_tip=generated_data.get("action_tip"),
            metrics=metrics_summary,
            generated_at=datetime.now(UTC)
        )

        db.add(new_insight)
        db.commit()
        db.refresh(new_insight)

        return InsightGenerateResponse(
            text=new_insight.text,
            action_tip=new_insight.action_tip,
            metrics=new_insight.metrics,
            generated_at=new_insight.generated_at,
            is_fallback=fallback_used
        )