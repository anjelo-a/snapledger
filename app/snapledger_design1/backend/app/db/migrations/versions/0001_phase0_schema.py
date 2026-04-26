"""Phase 0 foundational schema.

Revision ID: 0001_phase0_schema
Revises:
Create Date: 2026-04-23
"""

from __future__ import annotations

from datetime import UTC, datetime

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision = "0001_phase0_schema"
down_revision = None
branch_labels = None
depends_on = None

_default_categories = (
    "Food",
    "Transport",
    "Groceries",
    "Utilities",
    "Shopping",
    "Health",
    "Entertainment",
    "Education",
    "Bills",
    "Other",
)


def upgrade() -> None:
    op.create_table(
        "categories",
        sa.Column("id", sa.String(length=64), nullable=False),
        sa.Column("name", sa.String(length=64), nullable=False),
        sa.Column("is_default", sa.Boolean(), nullable=False, server_default=sa.text("false")),
        sa.Column("is_archived", sa.Boolean(), nullable=False, server_default=sa.text("false")),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.text("CURRENT_TIMESTAMP"),
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.text("CURRENT_TIMESTAMP"),
        ),
        sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("name"),
    )
    op.create_index("ix_categories_deleted_at", "categories", ["deleted_at"])

    op.create_table(
        "expenses",
        sa.Column("id", sa.String(length=64), nullable=False),
        sa.Column("source", sa.String(length=16), nullable=False),
        sa.Column("merchant", sa.String(length=160), nullable=False),
        sa.Column("expense_date", sa.Date(), nullable=False),
        sa.Column("total_amount", sa.Numeric(precision=12, scale=2), nullable=False),
        sa.Column("currency", sa.String(length=3), nullable=False, server_default="PHP"),
        sa.Column("notes", sa.Text(), nullable=True),
        sa.Column("category_id", sa.String(length=64), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.text("CURRENT_TIMESTAMP"),
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.text("CURRENT_TIMESTAMP"),
        ),
        sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(["category_id"], ["categories.id"]),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_expenses_deleted_at", "expenses", ["deleted_at"])
    op.create_index("ix_expenses_expense_date", "expenses", ["expense_date"])
    op.create_index("ix_expenses_category_id", "expenses", ["category_id"])
    op.create_index("ix_expenses_merchant", "expenses", ["merchant"])
    op.create_index("ix_expenses_total_amount", "expenses", ["total_amount"])

    op.create_table(
        "expense_items",
        sa.Column("id", sa.String(length=64), nullable=False),
        sa.Column("expense_id", sa.String(length=64), nullable=False),
        sa.Column("name", sa.String(length=160), nullable=False),
        sa.Column("amount", sa.Numeric(precision=12, scale=2), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.text("CURRENT_TIMESTAMP"),
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.text("CURRENT_TIMESTAMP"),
        ),
        sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(["expense_id"], ["expenses.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_expense_items_deleted_at", "expense_items", ["deleted_at"])
    op.create_index("ix_expense_items_expense_id", "expense_items", ["expense_id"])

    op.create_table(
        "budgets",
        sa.Column("id", sa.String(length=64), nullable=False),
        sa.Column("scope", sa.String(length=16), nullable=False),
        sa.Column("period", sa.String(length=16), nullable=False),
        sa.Column("amount_limit", sa.Numeric(precision=12, scale=2), nullable=False),
        sa.Column("category_id", sa.String(length=64), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.text("CURRENT_TIMESTAMP"),
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.text("CURRENT_TIMESTAMP"),
        ),
        sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(["category_id"], ["categories.id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "scope",
            "period",
            "category_id",
            name="uq_budget_scope_period_category",
        ),
    )
    op.create_index("ix_budgets_deleted_at", "budgets", ["deleted_at"])

    op.create_table(
        "insights",
        sa.Column("id", sa.String(length=64), nullable=False),
        sa.Column("period", sa.String(length=16), nullable=False),
        sa.Column("focus_category_id", sa.String(length=64), nullable=True),
        sa.Column("text", sa.Text(), nullable=False),
        sa.Column("action_tip", sa.Text(), nullable=True),
        sa.Column("metrics", sa.JSON(), nullable=False),
        sa.Column(
            "generated_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.text("CURRENT_TIMESTAMP"),
        ),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.text("CURRENT_TIMESTAMP"),
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.text("CURRENT_TIMESTAMP"),
        ),
        sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(["focus_category_id"], ["categories.id"]),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_insights_deleted_at", "insights", ["deleted_at"])

    now = datetime.now(UTC)
    category_rows = [
        {
            "id": f"seed-{idx}",
            "name": name,
            "is_default": True,
            "is_archived": False,
            "created_at": now,
            "updated_at": now,
            "deleted_at": None,
        }
        for idx, name in enumerate(_default_categories, start=1)
    ]
    op.bulk_insert(
        sa.table(
            "categories",
            sa.column("id", sa.String),
            sa.column("name", sa.String),
            sa.column("is_default", sa.Boolean),
            sa.column("is_archived", sa.Boolean),
            sa.column("created_at", sa.DateTime(timezone=True)),
            sa.column("updated_at", sa.DateTime(timezone=True)),
            sa.column("deleted_at", sa.DateTime(timezone=True)),
        ),
        category_rows,
    )


def downgrade() -> None:
    op.drop_index("ix_insights_deleted_at", table_name="insights")
    op.drop_table("insights")

    op.drop_index("ix_budgets_deleted_at", table_name="budgets")
    op.drop_table("budgets")

    op.drop_index("ix_expense_items_expense_id", table_name="expense_items")
    op.drop_index("ix_expense_items_deleted_at", table_name="expense_items")
    op.drop_table("expense_items")

    op.drop_index("ix_expenses_total_amount", table_name="expenses")
    op.drop_index("ix_expenses_merchant", table_name="expenses")
    op.drop_index("ix_expenses_category_id", table_name="expenses")
    op.drop_index("ix_expenses_expense_date", table_name="expenses")
    op.drop_index("ix_expenses_deleted_at", table_name="expenses")
    op.drop_table("expenses")

    op.drop_index("ix_categories_deleted_at", table_name="categories")
    op.drop_table("categories")
