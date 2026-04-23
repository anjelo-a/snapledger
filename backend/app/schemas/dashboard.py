from decimal import Decimal

from app.core.security import StrictSchema


class BudgetStatus(StrictSchema):
    budget_id: str
    spent: Decimal
    limit: Decimal
    ratio: float
    threshold_level: str


class TrendPoint(StrictSchema):
    period_label: str
    amount: Decimal


class CategoryBreakdown(StrictSchema):
    category_id: str | None
    category_name: str
    amount: Decimal


class RecentActivity(StrictSchema):
    expense_id: str
    merchant: str
    amount: Decimal
    expense_date: str


class DashboardResponse(StrictSchema):
    budget_statuses: list[BudgetStatus]
    trends: list[TrendPoint]
    category_breakdown: list[CategoryBreakdown]
    recent_activity: list[RecentActivity]
