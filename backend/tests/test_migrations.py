from pathlib import Path

from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine, inspect, text


def test_phase0_migration_creates_core_tables_and_seeds(tmp_path: Path) -> None:
    db_path = tmp_path / "phase0.db"
    database_url = f"sqlite:///{db_path}"

    backend_dir = Path(__file__).resolve().parents[1]
    alembic_cfg = Config(str(backend_dir / "alembic.ini"))
    alembic_cfg.set_main_option("sqlalchemy.url", database_url)

    command.upgrade(alembic_cfg, "head")

    engine = create_engine(database_url)
    inspector = inspect(engine)

    expected_tables = {
        "categories",
        "expenses",
        "expense_items",
        "budgets",
        "insights",
        "sync_mutation_log",
    }
    assert expected_tables.issubset(set(inspector.get_table_names()))

    expense_indexes = {idx["name"] for idx in inspector.get_indexes("expenses")}
    assert {
        "ix_expenses_expense_date",
        "ix_expenses_category_id",
        "ix_expenses_merchant",
        "ix_expenses_total_amount",
    }.issubset(expense_indexes)

    sync_columns = {column["name"] for column in inspector.get_columns("sync_mutation_log")}
    assert {
        "idempotency_key",
        "entity",
        "operation",
        "entity_id",
        "status",
        "response_summary",
        "error_summary",
        "created_at",
        "updated_at",
    }.issubset(sync_columns)

    sync_pk = inspector.get_pk_constraint("sync_mutation_log")
    assert sync_pk["constrained_columns"] == ["idempotency_key"]

    sync_indexes = {idx["name"] for idx in inspector.get_indexes("sync_mutation_log")}
    assert {
        "ix_sync_mutation_log_entity_id",
        "ix_sync_mutation_log_status",
        "ix_sync_mutation_log_updated_at",
    }.issubset(sync_indexes)

    alembic_version_columns = inspector.get_columns("alembic_version")
    assert len(alembic_version_columns) == 1
    assert alembic_version_columns[0]["name"] == "version_num"
    assert getattr(alembic_version_columns[0]["type"], "length", None) == 64

    with engine.begin() as conn:
        count = conn.execute(
            text("SELECT COUNT(*) FROM categories WHERE is_default = 1")
        ).scalar_one()
        assert count >= 10

        category_names = {
            row[0] for row in conn.execute(text("SELECT name FROM categories WHERE is_default = 1"))
        }

    assert {"Food", "Transport", "Groceries", "Utilities", "Bills", "Other"}.issubset(
        category_names
    )
