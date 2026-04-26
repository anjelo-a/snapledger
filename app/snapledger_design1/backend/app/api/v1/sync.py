from fastapi import APIRouter, HTTPException, Query

from app.schemas.sync import SyncPullResponse, SyncPushRequest, SyncPushResponse

router = APIRouter(prefix="/sync", tags=["sync"])


@router.post("/push", response_model=SyncPushResponse)
def push_sync(_payload: SyncPushRequest) -> SyncPushResponse:
    raise HTTPException(status_code=501, detail="Sync push is scheduled for Phase 4.")


@router.get("/pull", response_model=SyncPullResponse)
def pull_sync(cursor: str = Query(default="0")) -> SyncPullResponse:
    _ = cursor
    raise HTTPException(status_code=501, detail="Sync pull is scheduled for Phase 4.")
