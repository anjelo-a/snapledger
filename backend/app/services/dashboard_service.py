from __future__ import annotations

from collections import defaultdict
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal

from sqlalchemy import Select, select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from app.core.errors import ServiceUnavailableError
from app.models.category import Category
from app.models.expense import Expense
from app.repositories.budget_repo import BudgetRepository
from app.schemas.dashboard import (
    BudgetStatus,
    CategoryBreakdown,
    DashboardResponse,
    RecentActivity,
    TrendPoint,
)
from app.services.budget_service import BudgetService

_ZERO = Decimal("0.00")
_TREND_POINTS = 6
_RECENT_ACTIVITY_LIMIT = 10


class DashboardService:
    """Deterministic dashboard read-model aggregations."""

    @staticmethod
    def get(db: Session) -> DashboardResponse:
        try:
            budgets = BudgetRepository.list_active(db)
            expenses = _list_active_expenses(db)
            categories = _list_active_categories(db)
        except SQLAlchemyError as exc:
            raise ServiceUnavailableError(
                "Database operation failed while loading dashboard."
            ) from exc

        category_lookup = {item.id: item.name for item in categories}
        budget_statuses = _build_budget_statuses(budgets, expenses)
        trends = _build_monthly_trends(expenses)
        breakdown = _build_category_breakdown(expenses, category_lookup)
        recent = _build_recent_activity(expenses)
        return DashboardResponse(
            budget_statuses=budget_statuses,
            trends=trends,
            category_breakdown=breakdown,
            recent_activity=recent,
        )


def _list_active_expenses(db: Session) -> list[Expense]:
    stmt: Select[tuple[Expense]] = select(Expense).where(Expense.deleted_at.is_(None))
    return list(db.scalars(stmt).all())


def _list_active_categories(db: Session) -> list[Category]:
    stmt: Select[tuple[Category]] = select(Category).where(
        Category.deleted_at.is_(None),
        Category.is_archived.is_(False),
    )
    return list(db.scalars(stmt).all())


def _build_budget_statuses(budgets, expenses: list[Expense]) -> list[BudgetStatus]:
    today = datetime.now(UTC).date()
    statuses: list[BudgetStatus] = []
    for budget in budgets:
        start_date = _period_start(today, budget.period)
        spent = _sum_expenses(
            expenses,
            date_from=start_date,
            date_to=today,
            category_id=budget.category_id if budget.scope == "category" else None,
        )
        limit = Decimal(budget.amount_limit).quantize(Decimal("0.01"))
        ratio = float(spent / limit) if limit > 0 else 0.0
        statuses.append(
            BudgetStatus(
                budget_id=budget.id,
                spent=spent,
                limit=limit,
                ratio=ratio,
                threshold_level=_threshold_level(ratio),
            )
        )
    return statuses


def _build_monthly_trends(expenses: list[Expense]) -> list[TrendPoint]:
    today = datetime.now(UTC).date()
    first_of_this_month = date(today.year, today.month, 1)
    points: list[TrendPoint] = []
    for offset in reversed(range(_TREND_POINTS)):
        month_start = _add_months(first_of_this_month, -offset)
        month_end = _month_end(month_start)
        amount = _sum_expenses(expenses, date_from=month_start, date_to=month_end)
        points.append(
            TrendPoint(
                period_label=month_start.strftime("%Y-%m"),
                amount=amount,
            )
        )
    return points


def _build_category_breakdown(
    expenses: list[Expense], category_lookup: dict[str, str]
) -> list[CategoryBreakdown]:
    totals: dict[str | None, Decimal] = defaultdict(lambda: _ZERO)
    for expense in expenses:
        key = expense.category_id
        totals[key] = totals[key] + Decimal(expense.total_amount)

    rows = sorted(
        totals.items(),
        key=lambda item: (item[1], item[0] or ""),
        reverse=True,
    )
    output: list[CategoryBreakdown] = []
    for category_id, amount in rows:
        output.append(
            CategoryBreakdown(
                category_id=category_id,
                category_name=category_lookup.get(category_id or "", "Uncategorized"),
                amount=amount.quantize(Decimal("0.01")),
            )
        )
    return output


def _build_recent_activity(expenses: list[Expense]) -> list[RecentActivity]:
    ordered = sorted(
        expenses,
        key=lambda item: (item.expense_date, item.created_at, item.id),
        reverse=True,
    )
    output: list[RecentActivity] = []
    for expense in ordered[:_RECENT_ACTIVITY_LIMIT]:
        output.append(
            RecentActivity(
                expense_id=expense.id,
                merchant=expense.merchant,
                amount=Decimal(expense.total_amount).quantize(Decimal("0.01")),
                expense_date=expense.expense_date.isoformat(),
            )
        )
    return output


def _sum_expenses(
    expenses: list[Expense],
    *,
    date_from: date,
    date_to: date,
    category_id: str | None = None,
) -> Decimal:
    total = _ZERO
    for expense in expenses:
        if expense.expense_date < date_from or expense.expense_date > date_to:
            continue
        if category_id is not None and expense.category_id != category_id:
            continue
        total += Decimal(expense.total_amount)
    return total.quantize(Decimal("0.01"))


def _period_start(today: date, period: str) -> date:
    if period == "weekly":
        return today - timedelta(days=today.weekday())
    return date(today.year, today.month, 1)


def _threshold_level(ratio: float) -> str:
    thresholds = BudgetService.THRESHOLDS
    if ratio >= thresholds.exceeded:
        return "exceeded"
    if ratio >= thresholds.critical:
        return "critical"
    if ratio >= thresholds.warning:
        return "warning"
    return "normal"


def _add_months(input_date: date, months: int) -> date:
    month_index = input_date.month - 1 + months
    year = input_date.year + month_index // 12
    month = month_index % 12 + 1
    return date(year, month, 1)


def _month_end(month_start: date) -> date:
    if month_start.month == 12:
        next_month_start = date(month_start.year + 1, 1, 1)
    else:
        next_month_start = date(month_start.year, month_start.month + 1, 1)
    return next_month_start - timedelta(days=1)
