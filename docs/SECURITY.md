# SnapLedger Security

## Security posture by phase
Current staging posture:
- Backend is deployed on Cloud Run and internet-reachable.
- Cloud SQL is the runtime datastore.
- Full user authentication is still deferred, so compensating controls must stay enabled.

If private staging or external testers are introduced:
- Move these controls earlier immediately:
  - HTTPS/TLS everywhere
  - strict CORS allowlist
  - rate limiting
  - real authentication and authorization
  - stronger audit logging

Current backend implementation status:
- Optional security gates are implemented and env-toggle controlled:
  - API key requirement (`REQUIRE_API_KEY`, `API_KEY`)
  - CORS allowlist (`CORS_ALLOWED_ORIGINS`)
  - in-memory rate limiting (`RATE_LIMIT_ENABLED`, `RATE_LIMIT_REQUESTS`, `RATE_LIMIT_WINDOW_SECONDS`)
  - HTTPS enforcement (`ENFORCE_HTTPS`)

## Secrets handling
- Gemini API key stored on backend only via environment variable.
- No Gemini or backend secret in Android app.
- `.env` files excluded from VCS.
- Rotate keys periodically and on suspected exposure.

## Android local data safety
MVP:
- Use app sandbox and OS device encryption baseline.
- No SQLCipher required in foundation phases.

Hardening later:
- Evaluate SQLCipher only if threat model or release posture requires it.

## Validation rules
- Strict request validation via Pydantic.
- Enforce numeric ranges for amounts.
- Enforce valid date/time parsing.
- Reject unknown fields and malformed payloads.
- Cap item list lengths and text lengths.

## Logging and redaction
- Never log API keys, auth tokens, or headers with credentials.
- Avoid logging full OCR raw text in production logs.
- Redact sensitive notes fields where applicable.

## Network security
- Release builds must disallow cleartext traffic.
- Use HTTPS for any non-local deployment.
- Keep CORS locked to explicit origins when exposed.
- For Cloud Run, avoid `Allow unauthenticated` without at least API key gating in staging.

## Access and least privilege
- DB user must have least required privileges for app schema only.
- No superuser DB credentials in app runtime.
- Limit backend process permissions to needed network and storage access.
- Prefer a dedicated Cloud Run service account over the default compute service account.

## Data retention and delete behavior
- Use soft delete (`deleted_at`) on mutable core entities for sync safety.
- Hard purge only via explicit maintenance policy, never implicit in user delete path.

## Dependency hygiene
- Pin dependency versions.
- Run vulnerability scanning in CI.
- Patch high/critical issues before external exposure.
