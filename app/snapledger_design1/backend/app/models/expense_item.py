from decimal import Decimal

from sqlalchemy import ForeignKey, Numeric, String
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base, TimestampMixin


class ExpenseItem(TimestampMixin, Base):
    __tablename__ = "expense_items"

    expense_id: Mapped[str] = mapped_column(ForeignKey("expenses.id"), nullable=False)
    name: Mapped[str] = mapped_column(String(160), nullable=False)
    amount: Mapped[Decimal] = mapped_column(Numeric(12, 2), nullable=False)

    expense = relationship("Expense", back_populates="items")
