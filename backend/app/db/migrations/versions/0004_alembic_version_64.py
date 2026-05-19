"""Widen alembic version_num to avoid long revision-id failures.

Revision ID: 0004_alembic_version_64
Revises: 0003_sync_mutation_log
Create Date: 2026-05-18
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision = "0004_alembic_version_64"
down_revision = "0003_sync_mutation_log"
branch_labels = None
depends_on = None


def upgrade() -> None:
    with op.batch_alter_table("alembic_version") as batch_op:
        batch_op.alter_column(
            "version_num",
            existing_type=sa.String(length=32),
            type_=sa.String(length=64),
            existing_nullable=False,
        )


def downgrade() -> None:
    with op.batch_alter_table("alembic_version") as batch_op:
        batch_op.alter_column(
            "version_num",
            existing_type=sa.String(length=64),
            type_=sa.String(length=32),
            existing_nullable=False,
        )
