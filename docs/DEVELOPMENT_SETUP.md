# SnapLedger Development Setup

This guide is the end-to-end setup for co-builders working on Android + backend with local and cloud environments.

## 1) Prerequisites

- Android Studio (current stable)
- JDK 17+ (project uses Kotlin JVM toolchain 21 in Gradle)
- Python 3.11+
- Google Cloud project access (for shared cloud backend)
- `gcloud` CLI (for cloud checks and logs)

## 2) Repository layout and canonical paths

Use these as the source of truth:
- Android app: `app/`
- Backend: `backend/`
- Docs: `docs/`

Note:
- There is also a nested `snapledger/` directory containing duplicated legacy copies.
- Unless explicitly needed, do active development in the root modules above.

## 3) Android to backend connection

Android base URL is configured from `local.properties` and injected into `BuildConfig.BACKEND_BASE_URL`.

Code references:
- `app/build.gradle.kts`
- `app/src/main/java/com/snapledger/core/network/NetworkConfig.kt`

Set this in root `local.properties`:

```properties
SNAPLEDGER_BACKEND_BASE_URL=http://10.0.2.2:8000/
```

Use cases:
- Android emulator -> local backend on your machine: `http://10.0.2.2:8000/`
- Physical phone -> local backend: `http://<your-lan-ip>:8000/`
- Shared cloud backend: `https://snapledger-backend-75893256027.asia-southeast1.run.app/`

Important:
- Include trailing `/` (the build script normalizes this, but keep it explicit).

## 4) Local backend setup

```bash
cd backend
python -m venv .venv
source .venv/bin/activate
pip install -e .[dev]
```

Create local env:

```bash
cp .env.example .env.local
```

Run backend:

```bash
cd backend
python -m uvicorn app.main:app --reload
```

Health check:

```bash
curl -sS http://127.0.0.1:8000/health
```

Expected:

```json
{"status":"ok"}
```

## 5) Database modes

### Local dev DB (default)

Set in `backend/.env.local`:

```properties
DATABASE_URL=sqlite:///./snapledger.db
```

Good for:
- quick feature work
- offline backend iteration

### Local PostgreSQL (optional)

```properties
DATABASE_URL=postgresql+psycopg://postgres:postgres@localhost:5432/snapledger
```

### Shared Cloud SQL (staging/prod-like)

Use Cloud SQL Unix socket URL in Cloud Run secret:

```text
postgresql+psycopg://DB_USER:DB_PASS@/snapledger?host=/cloudsql/PROJECT_ID:REGION:INSTANCE_NAME
```

## 6) Migrations

Apply migrations after DB changes:

```bash
cd backend
alembic upgrade head
```

When adding schema changes:
1. Create migration revision.
2. Run tests.
3. Validate upgrade path from previous revision.

## 7) Cloud Run deployment baseline

Current shared service:
- URL: `https://snapledger-backend-75893256027.asia-southeast1.run.app`
- Region: `asia-southeast1`

Build/deploy shape:
- Source deploy from GitHub via Cloud Build trigger
- Build context: `/backend`
- Buildpacks runtime process from `backend/Procfile`
- Container port: `8080`
- Cloud SQL connection attached

Minimum runtime envs in Cloud Run:
- `APP_ENV=production`
- `DATABASE_URL` (Secret Manager)

Optional OCR envs:
- `GEMINI_API_KEY` (Secret Manager)
- `GEMINI_MODEL`
- `GEMINI_FALLBACK_MODEL`
- `GEMINI_TIMEOUT_SECONDS`

## 8) API/security toggles and current behavior

Backend supports:
- `REQUIRE_API_KEY` + `API_KEY`
- `CORS_ALLOWED_ORIGINS`
- `RATE_LIMIT_ENABLED`
- `ENFORCE_HTTPS`

Current gap:
- Android Retrofit clients do not yet attach `x-api-key` headers.
- If `REQUIRE_API_KEY=true`, Android calls will return `401` until an interceptor/header wiring is implemented.

Recommendation today:
- Keep `REQUIRE_API_KEY=false` in shared dev unless/until Android header support is added.

## 9) Frontend/backend smoke test flow

1. Backend `/health` returns 200.
2. Android app points to intended base URL.
3. Scan flow can call `POST /v1/receipts/process` (if Gemini key configured).
4. Review confirm can call `POST /v1/receipts/confirm`.
5. Sync worker can hit `/v1/sync/push` and `/v1/sync/pull`.

## 10) Troubleshooting quick hits

Cloud Run "failed to start/listen on PORT":
- Do not override container command to `python` for buildpack images.
- Keep buildpack default process or define process via `backend/Procfile`.

401 Unauthorized from cloud backend:
- `REQUIRE_API_KEY=true` but client sends no `x-api-key`.

Android cannot reach local backend:
- Emulator should use `10.0.2.2` not `localhost`.
- Check firewall and backend bind address.

DB errors on startup:
- Check `DATABASE_URL` secret format.
- Verify Cloud SQL instance connection is attached to the Cloud Run service.
- Run `alembic upgrade head`.

## 11) Team workflow conventions

- Do not commit secrets or `.env.local`.
- Use Secret Manager for cloud secrets.
- Keep deterministic finance logic in deterministic backend services.
- Use API contracts in `docs/API.md` as the source of truth.
