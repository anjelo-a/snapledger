"""Add owner_key to expenses for profile-scoped sync.

Revision ID: 0005_expense_owner_key
Revises: 0004_alembic_version_64
Create Date: 2026-05-19
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision = "0005_expense_owner_key"
down_revision = "0004_alembic_version_64"
branch_labels = None
depends_on = None


def upgrade() -> None:
    with op.batch_alter_table("expenses") as batch_op:
        batch_op.add_column(
            sa.Column(
                "owner_key",
                sa.String(length=191),
                nullable=False,
                server_default="public",
            )
        )
        batch_op.create_index("ix_expenses_owner_key", ["owner_key"], unique=False)


def downgrade() -> None:
    with op.batch_alter_table("expenses") as batch_op:
        batch_op.drop_index("ix_expenses_owner_key")
        batch_op.drop_column("owner_key")
