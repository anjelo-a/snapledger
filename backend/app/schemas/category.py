from datetime import datetime

from pydantic import Field

from app.core.security import StrictSchema


class CategoryRead(StrictSchema):
    id: str
    name: str = Field(min_length=1, max_length=64)
    is_default: bool
    is_archived: bool
    created_at: datetime
    updated_at: datetime


class CategoryCreate(StrictSchema):
    name: str = Field(min_length=1, max_length=64)


class CategoryUpdate(StrictSchema):
    name: str | None = Field(default=None, min_length=1, max_length=64)
    is_archived: bool | None = None


class CategoryListResponse(StrictSchema):
    items: list[CategoryRead]
