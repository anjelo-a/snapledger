from __future__ import annotations

import os
from logging.config import fileConfig

from alembic import context
from alembic.ddl.impl import DefaultImpl
from sqlalchemy import (
    Column,
    MetaData,
    PrimaryKeyConstraint,
    String,
    Table,
    engine_from_config,
    pool,
)

from app.core.config import get_settings
from app.db.base import Base
from app.models import (  # noqa: F401
    Budget,
    Category,
    Expense,
    ExpenseItem,
    Insight,
    SyncMutationLog,
)

config = context.config

if config.config_file_name is not None:
    fileConfig(config.config_file_name)

settings = get_settings()
configured_url = config.get_main_option("sqlalchemy.url")
database_url = os.getenv("DATABASE_URL") or configured_url or settings.database_url
config.set_main_option("sqlalchemy.url", database_url)
target_metadata = Base.metadata


def _version_table_impl_with_wider_revision_id(
    self: DefaultImpl,
    *,
    version_table: str,
    version_table_schema: str | None,
    version_table_pk: bool,
    **kw: object,
) -> Table:
    table = Table(
        version_table,
        MetaData(),
        Column("version_num", String(64), nullable=False),
        schema=version_table_schema,
    )
    if version_table_pk:
        table.append_constraint(
            PrimaryKeyConstraint("version_num", name=f"{version_table}_pkc")
        )
    return table


DefaultImpl.version_table_impl = _version_table_impl_with_wider_revision_id


def run_migrations_offline() -> None:
    url = config.get_main_option("sqlalchemy.url")
    context.configure(
        url=url,
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
        compare_type=True,
        compare_server_default=True,
    )

    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    connectable = engine_from_config(
        config.get_section(config.config_ini_section, {}),
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )

    with connectable.connect() as connection:
        context.configure(
            connection=connection,
            target_metadata=target_metadata,
            compare_type=True,
            compare_server_default=True,
        )

        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
