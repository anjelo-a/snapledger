from datetime import datetime

from sqlalchemy import JSON, DateTime, ForeignKey, String, Text, func
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base, TimestampMixin


class Insight(TimestampMixin, Base):
    __tablename__ = "insights"

    period: Mapped[str] = mapped_column(String(16), nullable=False)
    focus_category_id: Mapped[str | None] = mapped_column(
        ForeignKey("categories.id"),
        nullable=True,
    )
    text: Mapped[str] = mapped_column(Text, nullable=False)
    action_tip: Mapped[str | None] = mapped_column(Text, nullable=True)
    metrics: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)
    generated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
