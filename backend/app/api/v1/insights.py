from fastapi import APIRouter, HTTPException

from app.schemas.insight import InsightGenerateRequest, InsightGenerateResponse

router = APIRouter(prefix="/insights", tags=["insights"])


@router.post("/generate", response_model=InsightGenerateResponse)
def generate_insight(_payload: InsightGenerateRequest) -> InsightGenerateResponse:
    raise HTTPException(status_code=501, detail="Insights are scheduled for Phase 5.")
