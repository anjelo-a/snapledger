"""Add sync mutation log for Phase 4 receipts-first sync.

Revision ID: 0003_sync_mutation_log
Revises: 0002_category_normalized_name_unique
Create Date: 2026-05-01
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision = "0003_sync_mutation_log"
down_revision = "0002_category_normalized_name_unique"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "sync_mutation_log",
        sa.Column("idempotency_key", sa.String(length=128), nullable=False),
        sa.Column("entity", sa.String(length=32), nullable=False),
        sa.Column("operation", sa.String(length=32), nullable=False),
        sa.Column("entity_id", sa.String(length=64), nullable=True),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("response_summary", sa.Text(), nullable=True),
        sa.Column("error_summary", sa.Text(), nullable=True),
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
        sa.PrimaryKeyConstraint("idempotency_key"),
    )
    op.create_index("ix_sync_mutation_log_entity_id", "sync_mutation_log", ["entity_id"])
    op.create_index("ix_sync_mutation_log_status", "sync_mutation_log", ["status"])
    op.create_index("ix_sync_mutation_log_updated_at", "sync_mutation_log", ["updated_at"])


def downgrade() -> None:
    op.drop_index("ix_sync_mutation_log_updated_at", table_name="sync_mutation_log")
    op.drop_index("ix_sync_mutation_log_status", table_name="sync_mutation_log")
    op.drop_index("ix_sync_mutation_log_entity_id", table_name="sync_mutation_log")
    op.drop_table("sync_mutation_log")
