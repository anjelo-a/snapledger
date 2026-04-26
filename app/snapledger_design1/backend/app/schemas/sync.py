from datetime import datetime
from typing import Literal

from pydantic import Field

from app.core.security import StrictSchema


class SyncMutation(StrictSchema):
    idempotency_key: str = Field(min_length=8, max_length=128)
    entity: Literal["expense", "budget", "category"]
    operation: Literal["create", "update", "delete"]
    payload: dict
    occurred_at: datetime


class SyncPushRequest(StrictSchema):
    mutations: list[SyncMutation] = Field(min_length=1, max_length=200)


class SyncPushResponse(StrictSchema):
    accepted: int
    rejected: int


class SyncPullResponse(StrictSchema):
    cursor: str
    has_more: bool
    changes: list[dict]
