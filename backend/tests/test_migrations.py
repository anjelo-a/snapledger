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

    expected_tables = {"categories", "expenses", "expense_items", "budgets", "insights"}
    assert expected_tables.issubset(set(inspector.get_table_names()))

    expense_indexes = {idx["name"] for idx in inspector.get_indexes("expenses")}
    assert {
        "ix_expenses_expense_date",
        "ix_expenses_category_id",
        "ix_expenses_merchant",
        "ix_expenses_total_amount",
    }.issubset(expense_indexes)

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
