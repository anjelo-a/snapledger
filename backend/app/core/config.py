import os
from functools import lru_cache


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


@lru_cache
def get_settings() -> Settings:
    return Settings()
