from pydantic import BaseModel, ConfigDict, Field


class StrictSchema(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class PaginationQuery(StrictSchema):
    cursor: str | None = None
    limit: int = Field(default=20, ge=1, le=100)
