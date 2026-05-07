# SnapLedger Performance

## Performance targets

### Android UX targets
- Cold start median: under 1.8s on mid-tier modern Android device.
- Input-to-render latency in entry/review: under 100ms for normal interactions.
- Smooth scrolling for history lists: 60fps target under typical dataset.

### OCR targets
- Capture to structured review prefill:
  - p50 under 2.5s
  - p95 under 4.0s

### API targets
- Core CRUD endpoints p95 under 250ms.
- Dashboard endpoint p95 under 600ms.
- Insight endpoint timeout at 8s with graceful fallback.

### Sync targets
- 100 queued mutations synced in under 8s on stable network.
- Sync retries must be exponential and non-blocking to local usage.

## Data/query expectations
- Room indexes required on date, category_id, merchant, total_amount.
- History queries must avoid full table scans on common filters.
- Pagination default page size: 50 entries.
- Use stable keys in Compose lazy lists.

## Caching and computation rules
- Compute dashboard aggregates on demand in MVP.
- Do not add summary tables early.
- Add persisted summary tables only when profiling shows repeated query cost exceeds targets.

## Gemini usage limits
- One automatic insight generation per dashboard day.
- Optional user-triggered regenerate.
- De-duplicate identical requests within 24h.

## Profiling and measurement guidance
Android:
- Use Macrobenchmark for startup and scroll.
- Use Baseline Profiles before release builds.
- Profile OCR and dashboard flows with Android Studio Profiler.

Backend:
- Capture endpoint latency, error rates, and DB query timings.
- Track sync batch durations and failure causes.

Receipt extraction baseline cadence:
- Run deterministic extraction perf sweep nightly (`/v1/receipts/process` with OCR-line dataset).
- Track p50/p95/p99 latency, timeout rate, 429 rate, and non-200 class breakdown.
- Store timestamped artifacts with commit SHA for trend analysis.
- Treat p95 regressions above +15% as merge-blocking unless explicitly accepted in PR notes.
- Treat sub-5 percentage point deltas as noise at small sample sizes unless repeated across 2+ runs.

## Minimum acceptable release bar
- No target misses above 10% on two consecutive benchmark runs.
- No regressions in startup, OCR latency, or dashboard responsiveness before phase sign-off.

## Current backend note (April 28, 2026)
- Phase 1 backend feature scope is complete.
- Dedicated backend performance hardening and benchmark gate enforcement remain planned follow-up work.
