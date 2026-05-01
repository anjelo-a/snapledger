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


class SyncMutationResult(StrictSchema):
    idempotency_key: str
    entity: Literal["expense", "budget", "category"]
    operation: Literal["create", "update", "delete"]
    status: Literal["accepted", "rejected"]
    code: str | None = None
    message: str | None = None
    entity_id: str | None = None


class SyncPushResponse(StrictSchema):
    accepted: int
    rejected: int
    results: list[SyncMutationResult] = Field(default_factory=list)


class SyncPullChange(StrictSchema):
    entity: Literal["expense"]
    operation: Literal["upsert", "delete"]
    id: str
    updated_at: datetime
    payload: dict | None = None


class SyncPullResponse(StrictSchema):
    cursor: str
    has_more: bool
    changes: list[SyncPullChange]
