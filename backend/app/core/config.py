import os
from functools import lru_cache


class Settings:
    app_name: str = "SnapLedger API"
    api_v1_prefix: str = "/v1"
    app_env: str = os.getenv("APP_ENV", "development")
    database_url: str = os.getenv("DATABASE_URL", "sqlite:///./snapledger.db")


@lru_cache
def get_settings() -> Settings:
    return Settings()
