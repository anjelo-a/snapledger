"""Add case-insensitive trimmed active-category uniqueness index.

Revision ID: 0002_category_normalized_name_unique
Revises: 0001_phase0_schema
Create Date: 2026-04-28
"""

from __future__ import annotations

from alembic import op

# revision identifiers, used by Alembic.
revision = "0002_category_normalized_name_unique"
down_revision = "0001_phase0_schema"
branch_labels = None
depends_on = None


def upgrade() -> None:
    bind = op.get_bind()
    dialect = bind.dialect.name

    if dialect == "postgresql":
        op.execute(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS uq_categories_active_name_normalized
            ON categories (LOWER(BTRIM(name)))
            WHERE deleted_at IS NULL;
            """
        )
    else:
        op.execute(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS uq_categories_active_name_normalized
            ON categories (lower(trim(name)))
            WHERE deleted_at IS NULL;
            """
        )


def downgrade() -> None:
    op.execute("DROP INDEX IF EXISTS uq_categories_active_name_normalized;")
