import os
from functools import lru_cache
from pathlib import Path


def _load_local_env_files() -> None:
    candidates = (
        Path.cwd() / ".env.local",
        Path.cwd() / ".env",
        Path(__file__).resolve().parents[2] / ".env.local",
        Path(__file__).resolve().parents[2] / ".env",
    )
    for env_path in candidates:
        if not env_path.exists() or not env_path.is_file():
            continue
        for raw_line in env_path.read_text(encoding="utf-8").splitlines():
            line = raw_line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            key = key.strip()
            value = value.strip().strip("'\"")
            if key and key not in os.environ:
                os.environ[key] = value


_load_local_env_files()


class Settings:
    def __init__(self) -> None:
        self.app_name: str = "SnapLedger API"
        self.api_v1_prefix: str = "/v1"
        self.app_env: str = os.getenv("APP_ENV", "development")
        self.database_url: str = os.getenv("DATABASE_URL", "sqlite:///./snapledger.db")
        self.cors_allowed_origins: list[str] = [
            origin.strip()
            for origin in os.getenv("CORS_ALLOWED_ORIGINS", "").split(",")
            if origin.strip()
        ]
        self.require_api_key: bool = os.getenv("REQUIRE_API_KEY", "false").lower() == "true"
        self.api_key: str | None = os.getenv("API_KEY")
        self.rate_limit_enabled: bool = os.getenv("RATE_LIMIT_ENABLED", "false").lower() == "true"
        self.rate_limit_requests: int = int(os.getenv("RATE_LIMIT_REQUESTS", "120"))
        self.rate_limit_window_seconds: int = int(os.getenv("RATE_LIMIT_WINDOW_SECONDS", "60"))
        self.enforce_https: bool = os.getenv("ENFORCE_HTTPS", "false").lower() == "true"
        self.gemini_api_key: str | None = os.getenv("GEMINI_API_KEY")
        self.gemini_model: str = os.getenv("GEMINI_MODEL", "gemini-2.5-flash")
        self.gemini_timeout_seconds: float = float(os.getenv("GEMINI_TIMEOUT_SECONDS", "20"))


@lru_cache
def get_settings() -> Settings:
    return Settings()
