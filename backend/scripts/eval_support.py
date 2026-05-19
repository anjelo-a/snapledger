from __future__ import annotations

import json
import subprocess
from collections.abc import Iterator
from contextlib import contextmanager
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker

from app.core.category_seeds import DEFAULT_CATEGORY_SEEDS
from app.db.base import Base
from app.db.session import get_db
from app.main import app
from app.models.category import Category


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for idx, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        try:
            payload = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"Invalid JSONL at line {idx}: {exc}") from exc
        if not isinstance(payload, dict):
            raise ValueError(f"Invalid JSONL at line {idx}: expected object")
        rows.append(payload)
    return rows


def rate_string(numerator: int, denominator: int) -> str:
    if denominator == 0:
        return "0.0% (0/0)"
    return f"{round((numerator / denominator) * 100, 2)}% ({numerator}/{denominator})"


def load_commit_sha() -> str:
    try:
        output = subprocess.check_output(
            ["git", "rev-parse", "HEAD"],
            text=True,
            stderr=subprocess.DEVNULL,
        ).strip()
        return output or "unknown"
    except Exception:
        return "unknown"


def write_artifact(
    *,
    output_dir: Path,
    dataset_path: Path,
    mode: str,
    payload: dict[str, Any],
) -> tuple[Path, Path]:
    output_dir.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now(tz=UTC).strftime("%Y%m%dT%H%M%SZ")
    base = f"{mode}_{dataset_path.stem}_{timestamp}"
    json_path = output_dir / f"{base}.json"
    txt_path = output_dir / f"{base}.txt"
    json_path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    summary_lines = [
        f"mode={mode}",
        f"dataset={dataset_path}",
        f"commit_sha={payload.get('commit_sha')}",
        f"timestamp_utc={payload.get('timestamp_utc')}",
        "",
        "metrics:",
    ]
    for key, value in payload.get("metrics", {}).items():
        summary_lines.append(f"- {key}: {value}")
    txt_path.write_text("\n".join(summary_lines) + "\n", encoding="utf-8")
    return json_path, txt_path


@contextmanager
def isolated_test_client(db_path: Path) -> Iterator[TestClient]:
    db_path.parent.mkdir(parents=True, exist_ok=True)
    if db_path.exists():
        db_path.unlink()
    engine = create_engine(
        f"sqlite:///{db_path}",
        connect_args={"check_same_thread": False},
        future=True,
    )
    testing_session_local = sessionmaker(
        bind=engine,
        autoflush=False,
        autocommit=False,
        future=True,
    )
    Base.metadata.create_all(bind=engine)
    with testing_session_local() as seed_db:
        for idx, name in enumerate(DEFAULT_CATEGORY_SEEDS, start=1):
            seed_db.add(
                Category(
                    id=f"seed-{idx}",
                    name=name,
                    is_default=True,
                    is_archived=False,
                )
            )
        seed_db.commit()

    def override_get_db() -> Iterator[Session]:
        db = testing_session_local()
        try:
            yield db
        finally:
            db.close()

    app.dependency_overrides[get_db] = override_get_db
    app.state.testing_session_local = testing_session_local
    try:
        yield TestClient(app)
    finally:
        app.dependency_overrides.clear()
        if hasattr(app.state, "testing_session_local"):
            del app.state.testing_session_local
        Base.metadata.drop_all(bind=engine)
        engine.dispose()
        if db_path.exists():
            db_path.unlink()
